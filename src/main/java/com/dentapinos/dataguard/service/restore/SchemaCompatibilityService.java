package com.dentapinos.dataguard.service.restore;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.dto.SchemaCompatibilityAnalysisDto;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Сервис для анализа совместимости схемы резервной копии с целевой базой данных.
 * <p>
 * Предоставляет методы для проверки совместимости структуры таблиц резервной копии
 * со структурой существующей базы данных, а также для обработки несоответствий
 * в соответствии с заданной политикой восстановления ({@link com.dentapinos.dataguard.enums.policy.SchemaPolicy}).
 * </p>
 *
 * @see DatabaseMetadataReader Сервис для чтения метаданных базы данных
 * @see com.dentapinos.dataguard.enums.policy.SchemaPolicy Политика обработки несоответствий схемы
 * @see RestorePolicy Политика восстановления, включающая настройки схемы
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SchemaCompatibilityService {

    private final DatabaseMetadataReader metadataService;

    /**
     * Анализирует совместимость схемы резервной копии с целевой базой данных.
     * <p>
     * Выполняет сравнение таблиц и колонок между резервной копией и целевой базой данных,
     * определяя отсутствующие таблицы, колонки и избыточные колонки.
     * </p>
     *
     * @param dbCredentials     Учётные данные для подключения к целевой базе данных
     * @param backup            Объект резервной копии с метаданными схемы
     * @param targetDatabase    Имя целевой базы данных для проверки совместимости
     * @return {@link SchemaCompatibilityAnalysisDto} с результатами анализа:
     *         <ul>
     *           <li>compatibleStrict — true, если схемы идентичны (без отсутствующих и избыточных колонок)</li>
     *           <li>compatibleRelaxed — true, если все таблицы и колонки из бэкапа существуют в целевой БД</li>
     *         </ul>
     * @throws IllegalStateException если произошла ошибка при чтении метаданных целевой базы данных
     */
    public SchemaCompatibilityAnalysisDto analyzeCompatibility(
            DbCredentials dbCredentials,
            BackupFile backup,
            String targetDatabase
    ) {
        SchemaMeta backupSchema = backup.schema();

        List<String> backupTableNames = backupSchema.tables().stream()
                .map(TableMeta::name)
                .toList();

        SchemaMeta currentSchema = metadataService.readSchema(
                dbCredentials,
                targetDatabase,
                backupTableNames
        );

        Map<String, TableMeta> currentByName = currentSchema.tables().stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));

        List<String> missingTables = new ArrayList<>();
        List<String> missingColumns = new ArrayList<>();
        List<String> extraColumns = new ArrayList<>();

        for (TableMeta backupTable : backupSchema.tables()) {
            String tableName = backupTable.name();
            TableMeta currentTable = currentByName.get(tableName);

            if (currentTable == null) {
                missingTables.add(tableName);
            } else {
                analyzeTableSchema(backupTable, currentTable, missingColumns, extraColumns);
            }
        }

        boolean compatibleStrict = missingTables.isEmpty() && missingColumns.isEmpty() && extraColumns.isEmpty();
        boolean compatibleRelaxed = missingTables.isEmpty() && missingColumns.isEmpty();

        return new SchemaCompatibilityAnalysisDto(
                compatibleStrict,
                compatibleRelaxed,
                new ArrayList<>(),
                missingTables,
                List.of()
        );
    }

    /**
     * Внутренний метод для анализа различий в схеме отдельной таблицы.
     * <p>
     * Сравнивает структуру таблицы из резервной копии с таблицей в целевой базе данных,
     * собирая списки отсутствующих и избыточных колонок.
     * </p>
     *
     * @param backupTable        Таблица из резервной копии
     * @param currentTable       Соответствующая таблица в целевой базе данных
     * @param missingColumns     Список для добавления имен колонок, отсутствующих в целевой БД (формат: "table.column")
     * @param extraColumns       Список для добавления имен избыточных колонок (формат: "table.column")
     */
    private void analyzeTableSchema(TableMeta backupTable, TableMeta currentTable,
                                    List<String> missingColumns, List<String> extraColumns) {
        String tableName = backupTable.name();

        Map<String, ColumnMeta> backupCols = backupTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));
        Map<String, ColumnMeta> currentCols = currentTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));

        for (String colName : backupCols.keySet()) {
            if (!currentCols.containsKey(colName)) {
                missingColumns.add(tableName + "." + colName);
            }
        }

        for (String colName : currentCols.keySet()) {
            if (!backupCols.containsKey(colName)) {
                extraColumns.add(tableName + "." + colName);
            }
        }
    }

    /**
     * Проверяет или корректирует схему для импорта данных.
     * <p>
     * Выполняет аналогичный анализ совместимости, но с последующим выполнением действий
     * в соответствии с заданной политикой восстановления: бросает исключение, логирует предупреждение
     * или создает отсутствующие объекты.
     * </p>
     *
     * @param dbCredentials     Учётные данные для подключения к целевой базе данных
     * @param backup            Объект резервной копии с метаданными схемы
     * @param targetDatabase    Имя целевой базы данных для импорта
     * @param policy            Политика восстановления с настройками обработки несоответствий
     * @throws IllegalStateException если политикой STRICT_SCHEMA обнаружено несоответствие схемы
     */
    public void validateOrAdjustForImport(
            DbCredentials dbCredentials,
            BackupFile backup,
            String targetDatabase,
            RestorePolicy policy
    ) {
        SchemaMeta backupSchema = backup.schema();

        List<String> backupTableNames = backupSchema.tables().stream()
                .map(TableMeta::name)
                .toList();

        SchemaMeta currentSchema = metadataService.readSchema(
                dbCredentials,
                targetDatabase,
                backupTableNames
        );

        Map<String, TableMeta> currentByName = currentSchema.tables().stream()
                .collect(Collectors.toMap(TableMeta::name, t -> t));

        for (TableMeta backupTable : backupSchema.tables()) {
            String tableName = backupTable.name();
            TableMeta currentTable = currentByName.get(tableName);

            if (currentTable == null) {
                handleMissingTable(dbCredentials, targetDatabase, backupTable, policy);
            } else {
                handleExistingTableSchemaDiff(dbCredentials, targetDatabase, backupTable, currentTable, policy);
            }
        }
    }

    /**
     * Обрабатывает случай отсутствия таблицы в целевой базе данных.
     * <p>
     * Выполняет действия в соответствии с {@link com.dentapinos.dataguard.enums.policy.SchemaPolicy}:
     * <ul>
     *   <li>STRICT_SCHEMA — бросает исключение</li>
     *   <li>RELAXED_SCHEMA — логирует предупреждение (данные будут пропущены)</li>
     *   <li>AUTO_CREATE_TABLES — логирует информацию о необходимости создания таблицы</li>
     * </ul>
     *
     * @param dbCredentials     Учётные данные для подключения к целевой базе данных
     * @param targetDatabase    Имя целевой базы данных
     * @param backupTable       Метаданные таблицы из резервной копии
     * @param policy            Политика восстановления
     * @throws IllegalStateException если политика STRICT_SCHEMA и таблица не найдена
     */
    private void handleMissingTable(
            DbCredentials dbCredentials,
            String targetDatabase,
            TableMeta backupTable,
            RestorePolicy policy
    ) {
        String tableName = backupTable.name();
        switch (policy.schemaPolicy()) {
            case STRICT_SCHEMA -> throw new IllegalStateException(
                    "Таблица " + tableName + " из резервной копии не найдена в целевой базе данных " + targetDatabase);
            case RELAXED_SCHEMA -> // просто логируем — эту таблицу потом можно будет пропустить при импорте
                // (в importData проверим наличие таблицы, и если её нет — пропустим данные)
                    log.warn("Таблица {} из резервной копии не найдена в целевой БД {}, данные будут пропущены", tableName, targetDatabase);
            case AUTO_CREATE_TABLES -> log.info("Таблица {} не найдена в целевой БД {}, создаём на основе схемы из резервной копии", tableName, targetDatabase);
        }
    }

    /**
     * Обрабатывает различия в схеме существующей таблицы.
     * <p>
     * Сравнивает колонки таблицы из резервной копии с колонками в целевой базе данных
     * и выполняет действия в соответствии с {@link com.dentapinos.dataguard.enums.policy.SchemaPolicy}:
     * <ul>
     *   <li>STRICT_SCHEMA — бросает исключение при любом несоответствии</li>
     *   <li>RELAXED_SCHEMA — логирует предупреждения, но позволяет продолжить</li>
     *   <li>AUTO_CREATE_TABLES — логирует информацию о действиях по корректировке</li>
     * </ul>
     *
     * @param dbCredentials     Учётные данные для подключения к целевой базе данных
     * @param targetDatabase    Имя целевой базы данных
     * @param backupTable       Метаданные таблицы из резервной копии
     * @param currentTable      Метаданные существующей таблицы в целевой базе данных
     * @param policy            Политика восстановления
     * @throws IllegalStateException если политикой STRICT_SCHEMA обнаружено несоответствие колонок
     */
    private void handleExistingTableSchemaDiff(
            DbCredentials dbCredentials,
            String targetDatabase,
            TableMeta backupTable,
            TableMeta currentTable,
            RestorePolicy policy
    ) {
        String tableName = backupTable.name();

        Map<String, ColumnMeta> backupCols = backupTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));
        Map<String, ColumnMeta> currentCols = currentTable.columns().stream()
                .collect(Collectors.toMap(ColumnMeta::name, c -> c));

        // Колонки, которые есть в бэкапе, но нет в целевой БД
        for (String colName : backupCols.keySet()) {
            if (!currentCols.containsKey(colName)) {
                switch (policy.schemaPolicy()) {
                    case STRICT_SCHEMA -> throw new IllegalStateException(
                            "Колонка " + tableName + "." + colName + " есть в резервной копии, но отсутствует в целевой БД");
                    case RELAXED_SCHEMA -> log.info(
                            "Колонка {}.{} есть в резервной копии, но отсутствует в целевой БД; значение будет проигнорировано при импорте",
                            tableName, colName
                    );
                    case AUTO_CREATE_TABLES -> log.info("Добавляем отсутствующую колонку {}.{} в целевую БД {}", tableName, colName, targetDatabase);
                }
            }
        }

        // Колонки, которые есть в целевой БД, но нет в бэкапе
        for (String colName : currentCols.keySet()) {
            if (!backupCols.containsKey(colName)) {
                switch (policy.schemaPolicy()) {
                    case STRICT_SCHEMA -> throw new IllegalStateException(
                            "Колонка " + tableName + "." + colName + " есть в целевой БД, но отсутствует в резервной копии");
                    case RELAXED_SCHEMA, AUTO_CREATE_TABLES -> // Ок: при вставке они получат DEFAULT/NULL
                            log.info(
                                    "Колонка {}.{} есть в целевой БД, но отсутствует в резервной копии; будет заполнено значением по умолчанию/NULL",
                                    tableName, colName
                            );
                }
            }
        }
    }
}
