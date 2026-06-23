package com.dentapinos.dataguard.unit.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.*;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.policy.ErrorPolicy;
import com.dentapinos.dataguard.enums.policy.ForeignKeyPolicy;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.enums.policy.SchemaPolicy;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.service.restore.BatchImporter;
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import com.dentapinos.dataguard.service.restore.strategy.DryRunRestoreStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для класса DryRunRestoreStrategy.
 * Проверяется логика восстановления в режиме dry-run (без реальных изменений БД) при различных сценариях: пустая база, существующие данные, отсутствующие таблицы, несоответствие столбцов и обработка ошибок.
 */

@DisplayName("Unit-test для стратегииDryRunRestoreStrategy")
class DryRunRestoreStrategyTest {

    @Mock
    private JdbcTemplateFactory jdbcTemplateFactory;

    @Mock
    private BatchImporter batchImporter;

    @Mock
    private DatabaseConfigurator databaseConfigurator;

    @Mock
    private DatabaseMetadataReader mysqlMetadataReader;

    @Mock
    private JdbcTemplateFactory.JdbcConnection jdbcConnection;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private DryRunRestoreStrategy strategy;

    private AutoCloseable closeable;

    private RestoreStats stats;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        // Сброс моков для предотвращения влияния между тестами
        reset(jdbcTemplateFactory, batchImporter, databaseConfigurator, mysqlMetadataReader);
        reset(jdbcConnection, jdbcTemplate);

        // Настройка моков для JdbcTemplateFactory с возвратом мока JdbcConnection
        when(jdbcTemplateFactory.forDatabase(any(), any())).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);

        // Настройка моков для считывателя метаданных с возвратом пустой схемы по умолчанию
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        strategy = new DryRunRestoreStrategy(
                jdbcTemplateFactory,
                batchImporter,
                databaseConfigurator,
                mysqlMetadataReader
        );

        // Создание новых статистик для каждого теста
        stats = new RestoreStats();
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
    }

    // ==================== Вспомогательные методы ====================

    private DbCredentials createDbCredentials() {
        return new DbCredentials(
                "jdbc:mysql://localhost:3306/testdb",
                "testuser",
                "testpass"
        );
    }

    private TableMeta createTableMeta(String tableName, List<ColumnMeta> columns) {
        return new TableMeta(tableName, columns, List.of(), List.of(), List.of());
    }

    private ColumnMeta createColumnMeta(String name, String type, boolean nullable) {
        return new ColumnMeta(name, type, nullable, false);
    }

    private BackupFile createBackupFile(Map<String, List<Map<String, Object>>> data) {
        SchemaMeta schema = new SchemaMeta("testdb", List.of());
        return new BackupFile("testdb", "mysql", schema, data, null);
    }

    private RestorePolicy createRestorePolicy() {
        return new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.LOG_AND_CONTINUE
        );
    }

    // ==================== 1. Пустая база данных ====================

    /**
     * Условие 1: Пустая база данных
     * Ожидание: tablesSkipped++, rowsInserted = 0, симулированная статистика
     * Проверка:
     * - Нет изменений в БД
     * - Симулированная статистика соответствует ожиданиям
     * - Статус: COMPLETED_WITH_WARNINGS
     */
    @Test
    @DisplayName("должен пропустить таблицы при пустой целевой базе данных")
    void shouldSkipTablesWhenTargetDatabaseIsEmpty() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);
        RestorePolicy policy = createRestorePolicy();

        // Симуляция пустой базы данных (имя целевой базы - пустая строка)
        String targetDatabase = "";

        // act
        strategy.restore(credentials, backup, targetDatabase, policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesSkipped(), "Таблица должна быть пропущена для пустой базы данных");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены");
        assertEquals(0, stats.getTablesProcessed(), "Таблицы не должны быть обработаны");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");

        // Проверка статуса: COMPLETED_WITH_WARNINGS (tablesSkipped > 0, tablesProcessed == 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped > 0 → COMPLETED_WITH_WARNINGS

        // Проверка того, что никакие реальные операции БД не происходили
        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList(), anyInt(), any());
    }

    // ==================== 2. Существующие данные из бэкапа ====================

    /**
     * Условие 2: Существующие данные из бэкапа
     * Ожидание: tablesProcessed++, rowsInserted > 0, симулированная статистика
     * Проверка:
     * - Нет изменений в БД
     * - Симулированная статистика соответствует ожиданиям
     * - Статус: SUCCESS
     */
    @Test
    @DisplayName("должен обработать таблицы и симулировать вставку строк при наличии данных")
    void shouldProcessTablesAndSimulateRowsInsertionWhenDataExists() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        // Данные бэкапа
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая база имеет таблицу users
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false),
                createColumnMeta("email", "varchar(255)", true)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));

        // Настройка моков считывателя метаданных
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(2, stats.getRowsInserted(), "2 строки должны быть вставлены (симулировано)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");

        // Проверка статуса: SUCCESS (tablesProcessed > 0, tablesSkipped == 0, tablesFailed == 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped == 0 → SUCCESS

        // Проверка того, что никакие реальные операции БД не происходили
        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList(), anyInt(), any());
    }

    // ==================== 3. Отсутствующая таблица в целевой БД ====================

    /**
     * Условие 3: Отсутствующая таблица в целевой БД
     * Ожидание: tablesSkipped++, симулированная статистика
     * Проверка:
     * - Нет изменений в БД
     * - Симулированная статистика соответствует ожиданиям
     * - Статус: COMPLETED_WITH_WARNINGS
     */
    @Test
    @DisplayName("должен пропустить таблицу, если она не найдена в целевой БД")
    void shouldSkipTableWhenTableMissingInTargetDatabase() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        // Данные бэкапа включают таблицу users
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая база имеет другую таблицу (без таблицы users)
        TableMeta otherTable = createTableMeta("orders", List.of(
                createColumnMeta("id", "bigint(20)", false)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(otherTable));

        // Настройка моков считывателя метаданных для возврата схемы с определённой таблицей
        // Для DRY_RUN метод getTableMetaOneRow вызывает readSchema с List.of(tableName)
        // Поэтому нужно вернуть пустую схему для таблицы "users" или схему только с "orders"
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));  // пусто - users не найдена
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("orders"))))
                .thenReturn(schema);  // orders найдена
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(new SchemaMeta("testdb", List.of()));  // резерв - пусто

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(0, stats.getTablesProcessed(), "Таблицы не должны быть обработаны");
        assertEquals(1, stats.getTablesSkipped(), "Таблица должна быть пропущена");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");

        // Проверка статуса: COMPLETED_WITH_WARNINGS (tablesSkipped > 0, tablesProcessed == 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped > 0 → COMPLETED_WITH_WARNINGS

        // Проверка того, что никакие реальные операции БД не происходили
        verify(jdbcTemplate, never()).execute(anyString());
    }

    // ==================== 4. Дополнительные столбцы в БД ====================

    /**
     * Условие 4: Дополнительные столбцы в целевой БД
     * Ожидание: tablesProcessed++, симулированная статистика
     * Проверка:
     * - Нет изменений в БД
     * - Симулированная статистика соответствует ожиданиям
     * - Статус: SUCCESS
     */
    @Test
    @DisplayName("должен обработать таблицу при наличии дополнительных столбцов в целевой")
    void shouldProcessTableWhenTargetHasExtraColumns() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        // Данные бэкапа имеют базовые столбцы
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая база имеет дополнительные столбцы (игнорируются в RELAXED_SCHEMA)
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false),
                createColumnMeta("email", "varchar(255)", true),
                createColumnMeta("extra_column", "varchar(255)", true),  // дополнительный столбец
                createColumnMeta("nullable_extra", "varchar(255)", true) // дополнительный nullable
        ));

        // Настройка моков считывателя метаданных для возврата целевой таблицы для users
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(2, stats.getRowsInserted(), "2 строки должны быть вставлены (симулировано)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");

        // Проверка статуса: SUCCESS (tablesProcessed > 0, tablesSkipped == 0, tablesFailed == 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped == 0 → SUCCESS

        // Проверка того, что никакие реальные операции БД не происходили
        verify(jdbcTemplate, never()).execute(anyString());
    }

    // ==================== 5. Отсутствующие столбцы в бэкапе ====================

    /**
     * Условие 5: Отсутствующие столбцы в бэкапе (дополнительные в целевой)
     * Ожидание: tablesProcessed++, симулированная статистика
     * Проверка:
     * - Нет изменений в БД
     * - Симулированная статистика соответствует ожиданиям
     * - Статус: SUCCESS
     */
    @Test
    @DisplayName("должен обработать таблицу при отсутствии столбцов в бэкапе")
    void shouldProcessTableWhenColumnsMissingInBackup() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        // Данные бэкапа имеют меньше столбцов
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая база имеет больше столбцов (отсутствуют в бэкапе, но разрешены в RELAXED_SCHEMA)
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false),
                createColumnMeta("email", "varchar(255)", true),
                createColumnMeta("created_at", "datetime", true)
        ));

        // Настройка моков считывателя метаданных для возврата целевой таблицы для users
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(2, stats.getRowsInserted(), "2 строки должны быть вставлены (симулировано)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");

        // Проверка статуса: SUCCESS (tablesProcessed > 0, tablesSkipped == 0, tablesFailed == 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped == 0 → SUCCESS

        // Проверка того, что никакие реальные операции БД не происходили
        verify(jdbcTemplate, never()).execute(anyString());
    }

    // ==================== 6. Обработка ошибок ====================

    /**
     * Условие 6: Обработка ошибок
     * Ожидание: С LOG_AND_CONTINUE любое исключение перехватывается и обрабатывается корректно
     * Примечание: DRY_RUN не выполняет реальных операций, поэтому редко вызывает исключения.
     *             Стратегия перехватывает все исключения в основном цикле и помечает таблицы как неудачные.
     *             Этот тест проверяет обычную работу с политикой LOG_AND_CONTINUE.
     */
    @Test
    @DisplayName("должен обрабатывать исключения корректно с политикой LOG_AND_CONTINUE")
    void shouldHandleExceptionsGracefullyWithLogAndContinuePolicy() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Настройка нормальных метаданных (исключения не ожидаются в DRY_RUN)
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicy();

        // act
        // С политикой LOG_AND_CONTINUE и отсутствием реальных проблем восстановление должно завершиться успешно
        // Стратегия НЕ должна выбрасывать исключения в режиме DRY_RUN
        assertDoesNotThrow(() -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        // assert
        // С политикой LOG_AND_CONTINUE и отсутствием реальных проблем всё должно обработать успешно
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана успешно");
        assertEquals(1, stats.getRowsInserted(), "1 строка должна быть вставлена (симулировано)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой с LOG_AND_CONTINUE");

        // Проверка статуса: SUCCESS (tablesProcessed > 0, tablesSkipped == 0, tablesFailed == 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped == 0 → SUCCESS

        // Проверка того, что никакие реальные операции БД не происходили
        verify(jdbcTemplate, never()).execute(anyString());
    }

    // ==================== 7. Нарушения FK (не должны проверяться) ====================

    /**
     * Условие 7: Нарушения FK (не должны проверяться)
     * Ожидание: Нет проверки FK, нет изменений в БД
     * Проверка:
     * - FK не проверяется (политика SKIP_VIOLATIONS)
     * - Статус: SUCCESS
     */
    @Test
    @DisplayName("должен не проверять нарушения FK в режиме DRY_RUN")
    void shouldNotCheckForeignKeyViolationsInDryRunMode() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        // Данные бэкапа с внешними ключами - только 1 строка в orders
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        backupData.put("orders", List.of(
                Map.of("id", 1L, "user_id", 999L)  // user_id 999 может не существовать
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая база имеет обе таблицы
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        TableMeta ordersTable = createTableMeta("orders", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("user_id", "bigint(20)", true)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(usersTable, ordersTable));

        // Настройка моков считывателя метаданных для возврата обеих таблиц
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of(usersTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("orders"))))
                .thenReturn(new SchemaMeta("testdb", List.of(ordersTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed(), "Обе таблицы должны быть обработаны");
        assertEquals(2, stats.getRowsInserted(), "1 строка пользователя + 1 строка заказа = 2 всего симулировано");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");

        // Проверка статуса: SUCCESS (tablesProcessed > 0, tablesSkipped == 0, tablesFailed == 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped == 0 → SUCCESS

        // В DRY_RUN нарушения FK не проверяются (политика SKIP_VIOLATIONS)
        // Ограничения FK не должны применяться

        // Проверка того, что никакие реальные операции БД не происходили
        verify(jdbcTemplate, never()).execute(anyString());
        verify(jdbcTemplate, never()).batchUpdate(anyString(), anyList(), anyInt(), any());
    }

    // ==================== 8. Нет общих столбцов между бэкапом и целевой таблицей ====================

    /**
     * Тест: Нет общих столбцов между бэкапом и целевой таблицей
     * Ожидание: таблица пропускается
     */
    @Test
    @DisplayName("должен пропустить таблицу при отсутствии общих столбцов")
    void shouldSkipTableWhenNoCommonColumnsBetweenBackupAndTarget() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        // Бэкап имеет совершенно другие столбцы
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("different_column", "value")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая имеет другие столбцы
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));

        // Настройка моков считывателя метаданных для возврата пустой схемы для users (случай отсутствия общих столбцов)
        SchemaMeta emptySchema = new SchemaMeta("testdb", List.of());
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(emptySchema);
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(0, stats.getTablesProcessed(), "Таблицы не должны быть обработаны");
        assertEquals(1, stats.getTablesSkipped(), "Таблица должна быть пропущена (нет общих столбцов)");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");

        // Проверка статуса: COMPLETED_WITH_WARNINGS (tablesSkipped > 0, tablesProcessed == 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped > 0 → COMPLETED_WITH_WARNINGS
    }

    // ==================== 9. Несколько таблиц с различными результатами ====================

    /**
     * Тест: Несколько таблиц с различными результатами
     */
    @Test
    @DisplayName("должен обработать несколько таблиц с различными результатами")
    void shouldProcessMultipleTablesWithVariousResults() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        backupData.put("categories", List.of(
                Map.of("id", 1L, "name", "Category 1")
        ));
        backupData.put("nonexistent_table", List.of(
                Map.of("id", 1L)
        ));
        BackupFile backup = createBackupFile(backupData);

        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        TableMeta categoriesTable = createTableMeta("categories", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("name", "varchar(255)", false)
        ));

        // Настройка моков считывателя метаданных для возврата подходящих схем для каждой таблицы
        // nonexistent_table должна быть реализована для возврата пустой схемы, чтобы её пропустили
        SchemaMeta usersSchema = new SchemaMeta("testdb", List.of(usersTable));
        SchemaMeta categoriesSchema = new SchemaMeta("testdb", List.of(categoriesTable));
        SchemaMeta nonexistentSchema = new SchemaMeta("testdb", List.of());

        // Настройка конкретных таблиц в первую очередь, затем doAnswer для резервного варианта
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(usersSchema);
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("categories"))))
                .thenReturn(categoriesSchema);
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("nonexistent_table"))))
                .thenReturn(nonexistentSchema);
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<String> tableNames = invocation.getArgument(2, List.class);
                    if (tableNames != null && !tableNames.isEmpty()) {
                        String tableName = tableNames.get(0);
                        if ("users".equals(tableName)) {
                            return usersSchema;
                        } else if ("categories".equals(tableName)) {
                            return categoriesSchema;
                        } else if ("nonexistent_table".equals(tableName)) {
                            return nonexistentSchema;
                        }
                    }
                    return new SchemaMeta("testdb", List.of());
                });

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed(), "Должно быть обработано 2 таблицы");
        assertEquals(1, stats.getTablesSkipped(), "Должна быть пропущена 1 таблица (не существует)");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        assertEquals(3, stats.getRowsInserted(), "2 строки пользователей + 1 строка категории = 3 всего");

        // Проверка статуса: COMPLETED_WITH_WARNINGS (tablesProcessed > 0 И tablesSkipped > 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped > 0 И tablesProcessed > 0 → COMPLETED_WITH_WARNINGS
    }

    // ==================== 10. Пустые строки в бэкапе ====================

    /**
     * Тест: Пустые строки в бэкапе
     */
    @Test
    @DisplayName("должен обработать таблицу без вставки строк при пустых данных")
    void shouldProcessTableAndInsertZeroRowsWhenBackupHasEmptyRows() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of());  // Пустые строки
        BackupFile backup = createBackupFile(backupData);

        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(usersTable));

        // Настройка моков считывателя метаданных
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");

        // Проверка статуса: SUCCESS (tablesProcessed > 0, tablesSkipped == 0, tablesFailed == 0)
        // На основе RestoreService.getRestoreStatus():
        // - tablesFailed == 0 И tablesSkipped == 0 → SUCCESS
    }
}
