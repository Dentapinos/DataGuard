package com.dentapinos.dataguard.service.restore;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.RestoreStats;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.RestoreStatus;
import com.dentapinos.dataguard.exception.*;
import com.dentapinos.dataguard.report.RestoreReport;
import com.dentapinos.dataguard.report.RestoreSummary;
import com.dentapinos.dataguard.service.restore.order.RestoreOrderService;
import com.dentapinos.dataguard.service.restore.strategy.RestoreStrategy;
import com.dentapinos.dataguard.service.restore.strategy.RestoreStrategyFactory;
import com.dentapinos.dataguard.storage.BackupFileNamingService;
import com.dentapinos.dataguard.storage.BackupFileReader;
import com.dentapinos.dataguard.storage.BackupStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * <p>Сервис восстановления данных из бэкапа в уже существующую базу данных.
 * </p>
 * Основной публичный метод:
 *  - загружает файл бэкапа,
 *  - определяет политику восстановления,
 *  - проверяет и при необходимости подстраивает схему целевой БД,
 *  - импортирует данные,
 *  - формирует отчёт (RestoreReport) с итоговой статистикой.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RestoreService {

    private final RestorePolicyService restorePolicyService;
    private final RestoreStrategyFactory restoreStrategyFactory;
    private final BackupStorage backupStorage;
    private final BackupFileNamingService fileNameParser;
    private final BackupFileReader backupFileReader;
    private final SchemaCompatibilityService schemaCompatibilityService;
    private final RestoreOrderService restoreOrderService;

    /**
     * <p>Восстанавливает данные в существующую базу данных из указанного бэкапа.
     * </p>
     * Шаги:
     *  1) Фиксация времени начала.
     *  2) Загрузка полного бэкапа.
     *  3) Определение политики восстановления в зависимости от режима.
     *  4) Проверка / корректировка схемы целевой БД под импорт.
     *  5) Фильтрация таблиц (если указаны).
     *  6) Определение порядка восстановления таблиц на основе графа зависимостей.
     *  7) Импорт данных (таблицы, строки) с подсчётом статистики.
     *  8) Формирование сводного отчёта и вычисление статуса восстановления.
     *
     * @param dbCredentials   учетные данные подключения к целевой БД
     * @param tier            уровень (слой) бэкапа (например, полный/инкрементальный)
     * @param backupName      имя бэкап‑файла
     * @param targetDatabase  имя целевой базы данных, куда идёт восстановление
     * @param mode            режим восстановления (например, MERGE, OVERWRITE и т.п.)
     * @param tables          список таблиц для восстановления (если null/пусто — восстанавливаются все)
     * @return RestoreReport -подробный отчёт о выполненном восстановлении
     */
    public RestoreReport restoreToExistingDatabase(
            DbCredentials dbCredentials,
            BackupTier tier,
            String backupName,
            String targetDatabase,
            RestoreMode mode,
            List<String> tables
    ) {

        // === 3. Определяем политику восстановления на основе режима (RestoreMode) ===
        RestorePolicy policy;
        try {
            policy = restorePolicyService.policyFor(mode);
        } catch (InvalidRestoreModeException e) {
            String errorMsg = "Неизвестный режим восстановления: " + mode;
            log.error("[RESTORE_POLICY] {}", errorMsg, e);
            throw e;
        }

        // === 1. Фиксируем время начала операции восстановления ===
        Instant startedAt = Instant.now();

        // === 2. Загружаем полный бэкап как доменную сущность BackupFile ===
        String dirDBName = fileNameParser.parseDatabaseName(backupName);
        BackupFile backupFile;
        try (InputStream is = backupStorage.load(tier, dirDBName, backupName)) {
            backupFile = backupFileReader.readBackupFile(is);
        } catch (BackupFileReadingException e) {
            // Ошибка в ZIP-архиве (отсутствует backup.json или поврежден ZIP)
            String errorMessage = "Восстановление невозможно: ZIP-архив поврежден или не содержит обязательную запись backup.json. Бэкап: '" + backupName + "'";
            log.error(errorMessage, e);
            throw new RestoreZipException(errorMessage);
        } catch (BackupFileNotFoundException e) {
            // Перехватываем BackupFileNotFoundException и превращаем в BackupStorageException
            // для единообразия обработки ошибок хранилища
            String errorMessage = "Бэкап не найден: '" + backupName + "'";
            log.error(errorMessage, e);
            throw new BackupStorageException(errorMessage, e);
        } catch (IOException e) {
            // Для других ошибок ввода-вывода при загрузке используем BackupStorageException
            String errorMessage = "Ошибка загрузки бэкапа '" + backupName + "' из хранилища";
            log.error(errorMessage, e);
            throw new BackupStorageException(errorMessage, e);
        }

        // === 3.5. Фильтрация таблиц (если указаны) ===
        BackupFile filteredBackup = TableFilter.filterTables(backupFile, tables);
        log.info("[TABLE_FILTER] Фильтрация таблиц: originalTables={}, filteredTables={}",
                backupFile.data().size(), filteredBackup.data().size());

        // === 3.5. Проверка совместимости схемы бэкапа и целевой БД ===
        log.info("[SCHEMA_CHECK] Проверка совместимости схемы бэкапа '{}' с целевой БД '{}'", backupFile.database(), targetDatabase);
        try {
            schemaCompatibilityService.validateOrAdjustForImport(dbCredentials, filteredBackup, targetDatabase, policy);
        } catch (IllegalStateException e) {
            String errorMsg = "Несовместимость схемы при восстановлении бэкапа '" + backupName + "' в БД '" + targetDatabase + "': " + e.getMessage();
            log.error("[SCHEMA_CHECK] {}", errorMsg, e);
            throw new IllegalStateException(errorMsg, e);
        }

        // === 4. Определяем порядок восстановления таблиц ===
        log.info("[RESTORE_ORDER] Определение порядка восстановления таблиц");
        List<String> restoreOrder;
        try {
            // Определяем порядок на основе внешних ключей (graph-based)
            restoreOrder = restoreOrderService.determineRestoreOrder(
                    dbCredentials,
                    targetDatabase,
                    tables // используем отфильтрованный список таблиц
            );
        } catch (Exception e) {
            log.warn("[RESTORE_ORDER] Ошибка при определении порядка восстановления: {}", e.getMessage());
            log.info("[RESTORE_ORDER] Используем порядок таблиц из бэкапа как fallback");
            restoreOrder = null; // Используем порядок из бэкапа
        }
        log.info("[RESTORE_ORDER] Порядок восстановления: {}", restoreOrder != null ? restoreOrder : "(из бэкапа)");

        // === 5. Импорт данных из бэкапа в целевую базу ===
        // RestoreStats будет накапливать статистику:
        //  - сколько таблиц обработано / пропущено / с ошибками
        //  - сколько строк вставлено / обновлено / пропущено
        //  - помодульно по таблицам.
        RestoreStats stats = new RestoreStats();

        // === 5.1. Обработка таблиц, которые запрошены, но отсутствуют в бэкапе ===.
        // Подсчитываем таблицы, которые есть в списке tables, но нет в filteredBackup.data()
        Map<String, List<Map<String, Object>>> backupData = filteredBackup.data();
        int tablesMissingInBackup = 0;
        if (tables != null && !tables.isEmpty()) {
            for (String tableName : tables) {
                if (!backupData.containsKey(tableName)) {
                    tablesMissingInBackup++;
                    log.warn("[TABLE_SKIPPED] Таблица '{}' запрошена, но отсутствует в бэкапе", tableName);
                    stats.setTablesSkipped(stats.getTablesSkipped() + 1);
                }
            }
        }
        log.info("[TABLE_SUMMARY] Запрошено таблиц: {}, в бэкапе: {}, пропущено (нет в бэкапе): {}",
                tables != null ? tables.size() : 0, backupData.size(), tablesMissingInBackup);

        // Получаем стратегию на основе режима восстановления
        RestoreStrategy strategy = restoreStrategyFactory.getStrategy(mode);

        try {
            strategy.restore(
                    dbCredentials,
                    filteredBackup,
                    targetDatabase,
                    policy,
                    stats, backupName
            );
        } catch (Exception e) {
            String errorMessage = "Неожиданная ошибка во время восстановления";
            log.error(errorMessage, e);
            throw new RestoreOperationException(
                    errorMessage,
                    null,
                    null,
                    0,
                    e.getMessage(),
                    e
            );
        }

        // === 7. Фиксация времени окончания операции восстановления ===
        Instant finishedAt = Instant.now();

        // === 8. Формируем сводку по результатам (агрегация статистики) ===
        RestoreSummary summary = new RestoreSummary(
                stats.getTablesTotal(),
                stats.getTablesProcessed(),
                stats.getTablesFailed(),
                stats.getTablesSkipped(),
                stats.getRowsInserted(),
                stats.getRowsUpdated(),
                stats.getRowsSkipped(),
                Map.copyOf(stats.getRowsPerTableInserted()),
                Map.copyOf(stats.getRowsPerTableUpdated()),
                Map.copyOf(stats.getRowsPerTableSkipped())
        );

        // === 9. Определяем общий статус восстановления на основе сводки ===
        RestoreStatus status = getRestoreStatus(summary);

        // === 10. Возвращаем итоговый отчёт о восстановлении ===
        return new RestoreReport(
                startedAt,
                finishedAt,
                targetDatabase,
                mode,
                status,
                summary
        );
    }

    private static RestoreStatus getRestoreStatus(RestoreSummary summary) {
        RestoreStatus status;
        if (summary.tablesFailed() == 0 && summary.tablesSkipped() == 0) {
            // Все таблицы успешно восстановлены
            status = RestoreStatus.SUCCESS;
        } else if (summary.tablesProcessed() > 0) {
            // Есть успешно обработанные таблицы, но были ошибки/пропуски
            status = RestoreStatus.COMPLETED_WITH_WARNINGS;
        } else {
            // Ни одной таблицы не удалось обработать до конца
            status = RestoreStatus.FAILED;
        }
        return status;
    }


}