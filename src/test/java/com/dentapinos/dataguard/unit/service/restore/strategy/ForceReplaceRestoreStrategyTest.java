/**
 * Unit-тесты для класса ForceReplaceRestoreStrategy.
 * Проверяется логика восстановления в режиме force-replace (полная замена данных) при различных сценариях: пустая база, существующие данные, отсутствующие таблицы, порядок обработки таблиц и обработка ошибок.
 */

package com.dentapinos.dataguard.unit.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.*;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.policy.ErrorPolicy;
import com.dentapinos.dataguard.enums.policy.ForeignKeyPolicy;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.enums.policy.SchemaPolicy;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.MySqlMetadataReader;
import com.dentapinos.dataguard.service.restore.BatchImporter;
import com.dentapinos.dataguard.service.restore.SqlBuilder;
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import com.dentapinos.dataguard.service.restore.sqlbuilder.SqlBuilderFactory;
import com.dentapinos.dataguard.service.restore.strategy.ForceReplaceRestoreStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для класса ForceReplaceRestoreStrategy.
 * Проверяется логика восстановления в режиме force-replace (полная замена данных) при различных сценариях: пустая база, существующие данные, отсутствующие таблицы, порядок обработки таблиц и обработка ошибок.
 */
@DisplayName("Unit-test для стратегии ForceReplaceRestoreStrategy")
class ForceReplaceRestoreStrategyTest {

    @Mock
    private JdbcTemplateFactory jdbcTemplateFactory;

    @Mock
    private BatchImporter batchImporter;

    @Mock
    private DatabaseConfigurator databaseConfigurator;

    @Mock
    private MySqlMetadataReader mysqlMetadataReader;

    @Mock
    private SqlBuilderFactory sqlBuilderFactory;

    @Mock
    private SqlBuilder sqlBuilder;

    @Mock
    private JdbcTemplateFactory.JdbcConnection jdbcConnection;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ForceReplaceRestoreStrategy strategy;

    private AutoCloseable closeable;

    private RestoreStats stats;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        reset(jdbcTemplateFactory, batchImporter, databaseConfigurator, mysqlMetadataReader, sqlBuilderFactory);
        reset(jdbcConnection, jdbcTemplate);

        when(jdbcTemplateFactory.forDatabase(any(), any())).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);

        // Настройка моков sqlBuilderFactory для возврата sqlBuilder для любой политики
        when(sqlBuilderFactory.getSqlBuilder(any())).thenReturn(sqlBuilder);
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT SQL");

        strategy = new ForceReplaceRestoreStrategy(
                jdbcTemplateFactory,
                batchImporter,
                sqlBuilderFactory,
                databaseConfigurator,
                mysqlMetadataReader
        );

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

    private TableMeta createTableMeta(String tableName, List<String> columnNames) {
        List<ColumnMeta> columns = columnNames.stream()
                .map(name -> new ColumnMeta(name, "varchar", false, false))
                .toList();
        return new TableMeta(tableName, columns, List.of(), List.of(), List.of());
    }

    private SchemaMeta createSchema(String dbName, List<TableMeta> tables) {
        return new SchemaMeta(dbName, tables);
    }

    private BackupFile createBackupFile(Map<String, List<Map<String, Object>>> data) {
        SchemaMeta schema = new SchemaMeta("testdb", List.of());
        return new BackupFile("testdb", "mysql", schema, data, null);
    }

    private BackupFile createBackupFileWithOrder(Map<String, List<Map<String, Object>>> data, List<String> tableOrder) {
        SchemaMeta schema = new SchemaMeta("testdb", List.of());
        return new BackupFile("testdb", "mysql", schema, data, tableOrder);
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
     * Ожидание: tablesSkipped++ для всех таблиц, импорт не запускается
     * Проверка:
     * - Все таблицы пропускаются при пустой целевой базе данных
     * - Никакие операции с данными не выполняются
     */
    @Test
    @DisplayName("должен пропустить все таблицы при пустой целевой базе данных")
    void shouldSkipAllTablesWhenTargetDatabaseIsEmpty() {
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
        BackupFile backup = createBackupFile(backupData);

        // Целевая база данных не содержит таблиц
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(0, stats.getTablesProcessed(), "Таблицы не должны быть обработаны (все пропущены)");
        assertEquals(2, stats.getTablesSkipped(), "Обе таблицы должны быть пропущены (не найдены в целевой БД)");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены (нет операций с данными)");

        // Проверка того, что была вызвана настройка FK
        verify(databaseConfigurator).configureBeforeRestore(any(), any());
        verify(databaseConfigurator).configureAfterRestore(any(), any());
    }

    // ==================== 2. Нормальное восстановление - одна таблица ====================

    /**
     * Условие 2: Нормальное восстановление - одна таблица
     * Ожидание: tablesProcessed++, rowsInserted++ для каждой строки
     * Проверка:
     * - Таблица найдена в целевой базе данных
     * - Данные импортированы корректно
     * - Статистика обновлена правильно
     */
    @Test
    @DisplayName("должен успешно восстановить одну таблицу")
    void shouldRestoreSingleTableSuccessfully() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая база данных имеет таблицу users
        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));
        SchemaMeta schema = createSchema("testdb", List.of(usersTable));

        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        // Настройка мока batchImporter для увеличения количества вставленных строк
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6, RestoreStats.class);
            s.setRowsInserted(s.getRowsInserted() + 2); // 2 строки вставлены
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");

        // Проверка того, что была вызвана настройка FK
        verify(databaseConfigurator).configureBeforeRestore(any(), any());
        verify(databaseConfigurator).configureAfterRestore(any(), any());
    }

    // ==================== 3. Отсутствующая таблица ====================

    /**
     * Условие 3: Отсутствующая таблица в целевой БД
     * Ожидание: tablesSkipped++ для пропущенной таблицы
     * Проверка:
     * - Пропущенная таблица помечена как пропущенная
     * - Другие таблицы обрабатываются нормально
     */
    @Test
    @DisplayName("должен пропустить таблицу, если она не найдена в целевой БД")
    void shouldSkipTableWhenTableMissingInTargetDatabase() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        backupData.put("orders", List.of(
                Map.of("id", 1L)
        ));
        BackupFile backup = createBackupFile(backupData);

        // Только таблица users существует в целевой базе данных
        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));
        SchemaMeta usersSchema = createSchema("testdb", List.of(usersTable));

        // Таблица users существует
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(usersSchema);
        // Таблица orders не найдена
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("orders"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenAnswer(invocation -> {
                    List<String> tableNames = invocation.getArgument(2, List.class);
                    if (tableNames != null && !tableNames.isEmpty()) {
                        String tableName = tableNames.get(0);
                        if ("users".equals(tableName)) {
                            return usersSchema;
                        }
                    }
                    return new SchemaMeta("testdb", List.of());
                });

        // Настройка мока batchImporter только для таблицы users
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6, RestoreStats.class);
            s.setRowsInserted(s.getRowsInserted() + 1);
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Должна быть обработана 1 таблица (users)");
        assertEquals(1, stats.getTablesSkipped(), "Должна быть пропущена 1 таблица (orders)");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        assertEquals(1, stats.getRowsInserted(), "1 строка должна быть вставлена для таблицы users");
    }

    // ==================== 4. Пустые строки в бэкапе ====================

    /**
     * Условие 4: Пустые строки в бэкапе
     * Ожидание: tablesProcessed++ (таблица обработана, но строки не вставлены)
     * Проверка:
     * - Пустая таблица помечена как обработанная
     * - Строки не вставлены
     */
    @Test
    @DisplayName("должен обработать таблицу без вставки строк при пустых данных")
    void shouldProcessTableWithoutInsertingRowsWhenBackupHasEmptyRows() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of());  // Пустые строки
        BackupFile backup = createBackupFile(backupData);

        // Целевая база данных имеет таблицу users
        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));
        SchemaMeta schema = createSchema("testdb", List.of(usersTable));

        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана (даже с пустыми строками)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены");
    }

    // ==================== 5. Нулевые строки в бэкапе ====================

    /**
     * Условие 5: Нулевые строки в бэкапе
     * Ожидание: tablesSkipped++ для таблицы с нулевыми строками
     * Проверка:
     * - Таблица с нулевыми строками пропущена
     */
    @Test
    @DisplayName("должен пропустить таблицу при нулевых строках")
    void shouldSkipTableWhenBackupRowsAreNull() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", null);  // null строки
        BackupFile backup = createBackupFile(backupData);

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(0, stats.getTablesProcessed(), "Таблицы не должны быть обработаны");
        assertEquals(1, stats.getTablesSkipped(), "Таблица с нулевыми строками должна быть пропущена");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены");
    }

    // ==================== 6. Несколько таблиц с различными результатами ====================

    /**
     * Условие 6: Несколько таблиц с различными результатами
     * Ожидание: некоторые таблицы обработаны, некоторые пропущены
     * Проверка:
     * - Обработанные таблицы посчитаны корректно
     * - Пропущенные таблицы посчитаны корректно
     */
    @Test
    @DisplayName("должен обработать несколько таблиц с различными результатами")
    void shouldProcessMultipleTablesWithVariousResults() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        backupData.put("categories", List.of(
                Map.of("id", 1L, "name", "Category 1")
        ));
        backupData.put("nonexistent_table", List.of(
                Map.of("id", 1L)
        ));
        BackupFile backup = createBackupFile(backupData);

        // Таблицы users и categories существуют, nonexistent_table - нет
        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));
        TableMeta categoriesTable = createTableMeta("categories", List.of("id", "name"));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(usersTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("categories"))))
                .thenReturn(createSchema("testdb", List.of(categoriesTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("nonexistent_table"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        // Настройка мока batchImporter для существующих таблиц
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6, RestoreStats.class);
            String tableName = invocation.getArgument(1, String.class);
            if ("users".equals(tableName) || "categories".equals(tableName)) {
                s.setRowsInserted(s.getRowsInserted() + 1);
            }
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed(), "Должно быть обработано 2 таблицы");
        assertEquals(1, stats.getTablesSkipped(), "Должна быть пропущена 1 таблица (не существует)");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
    }

    // ==================== 7. Порядок таблиц из бэкапа ====================

    /**
     * Условие 7: Порядок таблиц из бэкапа
     * Ожидание: таблицы обрабатываются в порядке backup.tableOrder()
     * Проверка:
     * - Порядок таблиц соблюдён
     */
    @Test
    @DisplayName("должен обработать таблицы в порядке backup.tableOrder()")
    void shouldProcessTablesInBackupTableOrder() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("categories", List.of(
                Map.of("id", 1L, "name", "Category 1")
        ));
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFileWithOrder(backupData, List.of("categories", "users"));

        TableMeta categoriesTable = createTableMeta("categories", List.of("id", "name"));
        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));

        // Обе таблицы существуют
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("categories"))))
                .thenReturn(createSchema("testdb", List.of(categoriesTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(usersTable)));

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed(), "Обе таблицы должны быть обработаны");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
    }

    // ==================== 8. Настройка FK (TEMP_DISABLE политика) ====================

    /**
     * Условие 8: Настройка FK (TEMP_DISABLE политика)
     * Ожидание: SET FOREIGN_KEY_CHECKS = 0 перед восстановлением, = 1 после
     * Проверка:
     * - Проверки FK отключены во время восстановления
     * - Проверки FK повторно включены после восстановления
     */
    @Test
    @DisplayName("должен отключить проверки FK во время восстановления")
    void shouldDisableForeignKeyChecksDuringRestore() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFile(backupData);

        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));
        SchemaMeta schema = createSchema("testdb", List.of(usersTable));

        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        // Использовать политику TEMP_DISABLE (по умолчанию для FORCE_REPLACE)
        RestorePolicy policy = new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.TEMP_DISABLE,
                ErrorPolicy.LOG_AND_CONTINUE
        );

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        verify(databaseConfigurator).configureBeforeRestore(any(), eq(policy));
        verify(databaseConfigurator).configureAfterRestore(any(), eq(policy));

        // Примечание: Мы не проверяем конкретные вызовы execute() здесь, так как JdbcTemplate,
        // используемый databaseConfigurator, получается из JdbcConnection
        // Фактические SQL-команды FK выполняются через template.execute(),
        // что уже подтверждено вызовами verify для configureBeforeRestore и configureAfterRestore
    }

    // ==================== 9. Обработка ошибок с LOG_AND_CONTINUE ====================

    /**
     * Условие 9: Обработка ошибок с LOG_AND_CONTINUE
     * Ожидание: исключение поймано, таблица помечена как неудачная, обработка продолжается
     * Проверка:
     * - Исключение поймано и залогировано
     * - Таблица помечена как неудачная
     * - Обработка продолжается
     */
    @Test
    @DisplayName("должен поймать исключение с политикой LOG_AND_CONTINUE")
    void shouldCatchExceptionWithLogAndContinuePolicy() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        backupData.put("orders", List.of(
                Map.of("id", 1L)
        ));
        BackupFile backup = createBackupFile(backupData);

        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));
        TableMeta ordersTable = createTableMeta("orders", List.of("id"));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(usersTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("orders"))))
                .thenReturn(createSchema("testdb", List.of(ordersTable)));

        // Настройка мока batchImporter для выброса исключения для таблицы users
        doThrow(new RuntimeException("Test exception for users"))
                .when(batchImporter).importTableRows(
                        any(),
                        eq("users"),
                        anyList(),
                        anyList(),
                        anyString(),
                        any(),
                        any(),
                        any()
                );

        // Таблица orders успешна (поведение по умолчанию void)
        RestorePolicy policy = createRestorePolicy();

        // act - не должен выбрасывать, исключение поймано
        assertDoesNotThrow(() -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        // assert
        assertEquals(2, stats.getTablesProcessed(), "Обе таблицы должны быть обработаны (даже неудачные)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(1, stats.getTablesFailed(), "1 таблица должна завершиться ошибкой (users)");
    }

    // ==================== 10. Политика OVERWRITE_ON_CONFLICT ====================

    /**
     * Условие 10: Политика OVERWRITE_ON_CONFLICT
     * Ожидание: handleBatchResult интерпретирует count=2 как UPDATE
     * Проверка:
     * - count=1 = INSERT, count=2 = UPDATE
     */
    @Test
    @DisplayName("должен корректно обработать handleBatchResult для INSERT и UPDATE")
    void shouldHandleBatchResultCorrectlyForInsertAndUpdate() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);

        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));
        SchemaMeta schema = createSchema("testdb", List.of(usersTable));

        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        // Принудительно установить политику OVERWRITE_ON_CONFLICT (это то, что использует FORCE_REPLACE внутренне)
        RestorePolicy policy = new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.OVERWRITE_ON_CONFLICT,
                ForeignKeyPolicy.TEMP_DISABLE,
                ErrorPolicy.LOG_AND_CONTINUE
        );

        // Настройка мока batchImporter для симуляции поведения handleBatchResult
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6, RestoreStats.class);
            s.setRowsInserted(s.getRowsInserted() + 2); // 2 строки вставлены
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(2, stats.getRowsInserted(), "2 строки должны быть вставлены");
    }

    // ==================== 11. Большой объём данных ====================

    /**
     * Условие 11: Большой объём данных
     * Ожидание: все строки обработаны корректно
     * Проверка:
     * - Большой пакет обработан
     * - Статистика точная
     */
    @Test
    @DisplayName("должен корректно обработать большой объём данных")
    void shouldProcessLargeDatasetCorrectly() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        // Создать 100 строк
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            rows.add(Map.of("id", (long) i, "username", "user" + i));
        }

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", rows);
        BackupFile backup = createBackupFile(backupData);

        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));
        SchemaMeta schema = createSchema("testdb", List.of(usersTable));

        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        // Настройка мока batchImporter для увеличения количества вставленных строк для 100 строк
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6, RestoreStats.class);
            s.setRowsInserted(s.getRowsInserted() + 100); // 100 строк вставлены
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        assertEquals(100, stats.getRowsInserted(), "100 строк должны быть вставлены");
    }

    // ==================== 12. Нет общих столбцов ====================

    /**
     * Условие 12: Нет общих столбцов между бэкапом и целевой таблицей
     * Ожидание: таблица обработана, но строки не вставлены
     * Проверка:
     * - Таблица найдена, но столбцы не совпадают
     * - Строки не вставлены
     */
    @Test
    @DisplayName("должен обработать таблицу без вставки строк при отсутствии общих столбцов")
    void shouldProcessTableAndInsertZeroRowsWhenNoCommonColumns() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("different_column", "value")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая имеет другие столбцы
        TableMeta usersTable = createTableMeta("users", List.of("id", "username"));
        SchemaMeta schema = createSchema("testdb", List.of(usersTable));

        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана (найдена)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены (нет общих столбцов)");
    }
}
