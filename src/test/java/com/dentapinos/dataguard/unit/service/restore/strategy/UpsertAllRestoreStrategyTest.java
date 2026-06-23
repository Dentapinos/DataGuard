package com.dentapinos.dataguard.unit.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.*;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.policy.ErrorPolicy;
import com.dentapinos.dataguard.enums.policy.ForeignKeyPolicy;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.enums.policy.SchemaPolicy;
import com.dentapinos.dataguard.exception.RestoreOperationException;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.service.restore.BatchImporter;
import com.dentapinos.dataguard.service.restore.SqlBuilder;
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import com.dentapinos.dataguard.service.restore.sqlbuilder.SqlBuilderFactory;
import com.dentapinos.dataguard.service.restore.strategy.UpsertAllRestoreStrategy;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для стратегии полного восстановления с upsert.
 * Проверяет поведение стратегии UpsertAllRestoreStrategy при обработке различных сценариев восстановления данных.
 */
@DisplayName("Unit-test для стратегии полного восстановления с upsert")
class UpsertAllRestoreStrategyTest {

    @Mock
    private JdbcTemplateFactory jdbcTemplateFactory;

    @Mock
    private BatchImporter batchImporter;

    @Mock
    private DatabaseConfigurator databaseConfigurator;

    @Mock
    private DatabaseMetadataReader mysqlMetadataReader;

    @Mock
    private SqlBuilderFactory sqlBuilderFactory;

    @Mock
    private SqlBuilder sqlBuilder;

    @Mock
    private JdbcTemplateFactory.JdbcConnection jdbcConnection;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private UpsertAllRestoreStrategy strategy;

    private AutoCloseable closeable;

    private RestoreStats stats;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        reset(jdbcTemplateFactory, batchImporter, databaseConfigurator, mysqlMetadataReader, sqlBuilderFactory);
        reset(jdbcConnection, jdbcTemplate);

        when(jdbcTemplateFactory.forDatabase(any(), any())).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);

        when(sqlBuilderFactory.getSqlBuilder(eq(RowConflictPolicy.OVERWRITE_ON_CONFLICT))).thenReturn(sqlBuilder);

        strategy = new UpsertAllRestoreStrategy(
                jdbcTemplateFactory,
                batchImporter,
                databaseConfigurator,
                mysqlMetadataReader,
                sqlBuilderFactory
        );

        stats = new RestoreStats();
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
    }

    // ==================== Helper Methods ====================

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

    private BackupFile createBackupFileWithOrder(Map<String, List<Map<String, Object>>> data, List<String> tableOrder) {
        SchemaMeta schema = new SchemaMeta("testdb", List.of());
        return new BackupFile("testdb", "mysql", schema, data, tableOrder);
    }

    private RestorePolicy createRestorePolicyRelaxed() {
        return new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.OVERWRITE_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.LOG_AND_CONTINUE
        );
    }

    private RestorePolicy createRestorePolicyStrict() {
        return new RestorePolicy(
                SchemaPolicy.STRICT_SCHEMA,
                RowConflictPolicy.OVERWRITE_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.FAIL_FAST
        );
    }

    /**
     * Условие 1: Пустая база данных / отсутствующая таблица с RELAXED_SCHEMA
     * Ожидается: увеличение tablesSkipped++, без исключения, предупреждение в логах
     */
    @Test
    @DisplayName("должен пропустить таблицу при пустой базе данных с релаксированной схемой")
    void shouldSkipTableWhenDatabaseEmptyWithRelaxedSchema() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);
        RestorePolicy policy = createRestorePolicyRelaxed();

        // пользовательская таблица не найдена в пустой базе данных
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesSkipped(), "Таблица должна быть пропущена при отсутствии таблицы с RELAXED_SCHEMA");
        assertEquals(0, stats.getTablesProcessed(), "Не должно быть обработано таблиц");
        assertEquals(0, stats.getTablesFailed(), "Не должно быть ошибок таблиц");
        assertEquals(0, stats.getRowsInserted(), "Не должно быть вставлено строк");
        assertEquals(0, stats.getRowsUpdated(), "Не должно быть обновлено строк");
    }

    /**
     * Условие 2: Пустая база данных / отсутствующая таблица с STRICT_SCHEMA
     * Ожидается: немедленное исключение RestoreOperationException
     */
    @Test
    @DisplayName("должен выбросить исключение при пустой базе данных с жесткой схемой")
    void shouldThrowExceptionWhenDatabaseEmptyWithStrictSchema() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFile(backupData);
        RestorePolicy policy = createRestorePolicyStrict();

        // пользовательская таблица не найдена
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        // act и then
        RestoreOperationException ex = assertThrows(RestoreOperationException.class, () -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        // assert
        assertTrue(ex.getMessage().contains("Missing table"), "Исключение должно упоминать отсутствующую таблицу");
        assertEquals("users", ex.getTable(), "Имя таблицы должно быть включено в исключение");
        assertEquals(1, stats.getTablesFailed(), "Таблица должна быть помечена как неудачная");
        assertEquals(0, stats.getTablesProcessed(), "Не должно быть обработано таблиц");
    }

    /**
     * Условие 3: Существующая таблица с совпадающей схемой
     * Ожидается: увеличение tablesProcessed++, семантика upsert (count=1 → вставка, count=2 → обновление)
     */
    @Test
    @DisplayName("должен применить семантику upsert для существующей таблицы")
    void shouldUpsertRowsWhenTableExists() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);

        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicyRelaxed();

        // Mock sql builder
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT INTO users (...) VALUES (...) ON DUPLICATE KEY UPDATE ...");

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getTablesSkipped(), "Не должно быть пропущено таблиц");
        assertEquals(0, stats.getTablesFailed(), "Не должно быть ошибок таблиц");

        //batchImporter был вызван
        verify(batchImporter, times(1)).importTableRows(any(), eq("users"), anyList(), anyList(), anyString(), any(), any(), any());

        //SQL Builder был вызван с помощью OVERWRITE_ON_CONFLICT
        verify(sqlBuilderFactory, times(1)).getSqlBuilder(eq(RowConflictPolicy.OVERWRITE_ON_CONFLICT));
    }

    /**
     * Условие 4: Отсутствующие столбцы в резервной копии (дополнительные столбцы в целевой базе)
     * Ожидается: увеличение tablesProcessed++, используется filterInsertColumns, импортируются только общие столбцы
     */
    @Test
    @DisplayName("должен обработать дополнительные столбцы в целевой базе с релаксированной схемой")
    void shouldProcessExtraColumnsInTargetWithRelaxedSchema() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая база данных содержит дополнительные столбцы, не присутствующие в резервной копии
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false),
                createColumnMeta("email", "varchar(255)", true),
                createColumnMeta("created_at", "datetime", true)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicyRelaxed();

        // Mock sql builder
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT INTO users (...) VALUES (...) ON DUPLICATE KEY UPDATE ...");

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getTablesSkipped(), "Не должно быть пропущено таблиц");
        assertEquals(0, stats.getTablesFailed(), "Не должно быть ошибок таблиц");

        //batchImporter был вызван
        verify(batchImporter, times(1)).importTableRows(any(), eq("users"), anyList(), anyList(), anyString(), any(), any(), any());

        // SQL Builder был вызван с помощью OVERWRITE_ON_CONFLICT
        verify(sqlBuilderFactory, times(1)).getSqlBuilder(eq(RowConflictPolicy.OVERWRITE_ON_CONFLICT));
    }

    /**
     * Условие 5: Дополнительные столбцы в резервной копии (отсутствующие столбцы в целевой базе)
     * Ожидается: увеличение tablesProcessed++, используется filterInsertColumns, дополнительные столбцы игнорируются
     */
    @Test
    @DisplayName("должен игнорировать дополнительные столбцы в резервной копии с релаксированной схемой")
    void shouldIgnoreExtraColumnsInBackupWithRelaxedSchema() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        // Резервная копия содержит дополнительные столбцы, не присутствующие в целевой базе
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1", "extra_col", "extra_value")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая база данных содержит меньше столбцов
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicyRelaxed();

        // Mock sql builder
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT INTO users (...) VALUES (...) ON DUPLICATE KEY UPDATE ...");

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getTablesSkipped(), "Не должно быть пропущено таблиц");
        assertEquals(0, stats.getTablesFailed(), "Не должно быть ошибок таблиц");

        // batchImporter был вызван
        verify(batchImporter, times(1)).importTableRows(any(), eq("users"), anyList(), anyList(), anyString(), any(), any(), any());

        // SQL Builder был вызван с помощью OVERWRITE_ON_CONFLICT
        verify(sqlBuilderFactory, times(1)).getSqlBuilder(eq(RowConflictPolicy.OVERWRITE_ON_CONFLICT));
    }

    /**
     * Условие 7: Обработка ошибок с FAIL_FAST
     * Ожидается: немедленное исключение, обработка останавливается
     */
    @Test
    @DisplayName("должен остановить обработку при ошибке с политикой быстрого сбоя")
    void shouldStopProcessingOnErrorWithFailFastPolicy() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        backupData.put("orders", List.of(
                Map.of("id", 1L, "amount", 100.0)
        ));
        BackupFile backup = createBackupFile(backupData);

        // Таблица users существует
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta usersSchema = new SchemaMeta("testdb", List.of(usersTable));

        // Таблица orders не найдена (приведет к ошибке)
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(usersSchema);
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("orders"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        RestorePolicy policy = new RestorePolicy(
                SchemaPolicy.STRICT_SCHEMA,
                RowConflictPolicy.OVERWRITE_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.FAIL_FAST
        );

        // Mock sql builder для таблицы users
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT INTO users (...) VALUES (...) ON DUPLICATE KEY UPDATE ...");

        // act и then - должен выбросить исключение немедленно на таблице orders
        RestoreOperationException ex = assertThrows(RestoreOperationException.class, () -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        // assert
        assertTrue(ex.getMessage().contains("Missing table"), "Исключение должно упоминать отсутствующую таблицу");
        assertEquals("orders", ex.getTable(), "Исключение должно упоминать таблицу orders");
        assertEquals(1, stats.getTablesFailed(), "1 таблица должна завершиться неудачей (orders)");
    }

    /**
     * Условие 8: Порядок таблиц из резервной копии
     * Ожидается: таблицы обрабатываются в порядке backup.tableOrder()
     */
    @Test
    @DisplayName("должен обрабатывать таблицы в порядке из резервной копии")
    void shouldProcessTablesInBackupOrder() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("categories", List.of(
                Map.of("id", 1L, "name", "Category 1")
        ));
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));

        // Пользовательский порядок: categories первым, затем users
        BackupFile backup = createBackupFileWithOrder(backupData, List.of("categories", "users"));

        TableMeta categoriesTable = createTableMeta("categories", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("name", "varchar(255)", false)
        ));
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta categoriesSchema = new SchemaMeta("testdb", List.of(categoriesTable));
        SchemaMeta usersSchema = new SchemaMeta("testdb", List.of(usersTable));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("categories"))))
                .thenReturn(categoriesSchema);
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(usersSchema);

        RestorePolicy policy = createRestorePolicyRelaxed();

        // Mock sql builder
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT INTO ... VALUES (...) ON DUPLICATE KEY UPDATE ...");

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed(), "Обе таблицы должны быть обработаны");
        assertEquals(0, stats.getTablesSkipped(), "Не должно быть пропущено таблиц");
        assertEquals(0, stats.getTablesFailed(), "Не должно быть ошибок таблиц");
    }

    /**
     * Условие 9: Пустые строки в резервной копии
     * Ожидается: увеличение tablesProcessed++, импорт не пытается
     */
    @Test
    @DisplayName("должен обработать пустую таблицу без попытки импорта")
    void shouldProcessEmptyTableWithoutImportAttempt() {
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

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicyRelaxed();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getTablesSkipped(), "Не должно быть пропущено таблиц");
        assertEquals(0, stats.getTablesFailed(), "Не должно быть ошибок таблиц");

        // Проверка, что batchImporter НЕ был вызван для пустых строк
        verify(batchImporter, never()).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
    }

    /**
     * Условие 10: Несколько таблиц с различными результатами
     * Ожидается: таблицы обрабатываются/пропускаются/завершаются с ошибкой в зависимости от доступности
     */
    @Test
    @DisplayName("должен обрабатывать несколько таблиц с различными результатами")
    void shouldHandleMultipleTablesWithVariousResults() {
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

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of(usersTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("categories"))))
                .thenReturn(new SchemaMeta("testdb", List.of(categoriesTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("nonexistent_table"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        RestorePolicy policy = createRestorePolicyRelaxed();

        // Mock sql builder
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT INTO ... VALUES (...) ON DUPLICATE KEY UPDATE ...");

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed(), "2 таблицы должны быть обработаны (users, categories)");
        assertEquals(1, stats.getTablesSkipped(), "1 таблица должна быть пропущена (nonexistent)");
        assertEquals(0, stats.getTablesFailed(), "Не должно быть ошибок таблиц");
    }

    /**
     * Условие 11: Семантика upsert - проверка отслеживания вставки и обновления
     * Ожидается: count=1 → rowsInserted, count=2 → rowsUpdated
     */
    @Test
    @DisplayName("должен корректно отслеживать вставку и обновление строк")
    void shouldTrackInsertedAndUpdatedRowsCorrectly() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = createBackupFile(backupData);

        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicyRelaxed();

        // Mock sql builder
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT INTO users (...) VALUES (...) ON DUPLICATE KEY UPDATE ...");

        // При импорте строк с UpsertAllRestoreStrategy вызывается handleBatchResult стратегии
        // с фактическим результатом пакета из базы данных
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            List<Map<String, Object>> rows = invocation.getArgument(2);
            // Для UPSERT_ALL, count=1 означает вставку, count=2 означает обновление
            // Симулируем все строки как вставки (count=1)
            s.setRowsInserted(s.getRowsInserted() + rows.size());
            return null;
        }).when(batchImporter).importTableRows(
                any(JdbcTemplate.class),
                eq("users"),
                anyList(),
                anyList(),
                anyString(),
                any(RestorePolicy.class),
                any(RestoreStats.class),
                any(com.dentapinos.dataguard.service.restore.strategy.RestoreStrategy.class)
        );

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(2, stats.getRowsInserted(), "2 строки должны быть вставлены (count=1)");
        assertEquals(0, stats.getRowsUpdated(), "0 строк не должно быть обновлено");

        // Сброс статистики для теста обновления
        stats = new RestoreStats();

        // Mock для симуляции обновлений (count=2)
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            List<Map<String, Object>> rows = invocation.getArgument(2);
            // Симулируем все строки как обновления (count=2)
            s.setRowsUpdated(s.getRowsUpdated() + rows.size());
            return null;
        }).when(batchImporter).importTableRows(
                any(JdbcTemplate.class),
                eq("users"),
                anyList(),
                anyList(),
                anyString(),
                any(RestorePolicy.class),
                any(RestoreStats.class),
                any(com.dentapinos.dataguard.service.restore.strategy.RestoreStrategy.class)
        );

        // act снова
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getRowsInserted(), "0 строк не должно быть вставлено");
        assertEquals(2, stats.getRowsUpdated(), "2 строки должны быть обновлены (count=2)");
    }

    /**
     * Условие 12: Отсутствие общих столбцов между резервной копией и целевой базой
     * Ожидается: таблица пропускается (нет столбцов для импорта)
     */
    @Test
    @DisplayName("должен пропустить импорт при отсутствии общих столбцов")
    void shouldSkipImportWhenNoCommonColumns() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("different_column", "value")
        ));
        BackupFile backup = createBackupFile(backupData);

        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicyRelaxed();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана (прочитана метаданные)");
        assertEquals(0, stats.getTablesSkipped(), "Не должно быть пропущено таблиц");
        assertEquals(0, stats.getTablesFailed(), "Не должно быть ошибок таблиц");
        assertEquals(0, stats.getRowsInserted(), "Не должно быть вставлено строк (нет общих столбцов)");
        assertEquals(0, stats.getRowsUpdated(), "Не должно быть обновлено строк");

        // Проверьте, что batchImporter НЕ вызывался (колонки для импорта нет)
        verify(batchImporter, never()).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
    }

    /**
     * Условие 13: Несколько таблиц с некоторыми ошибками и некоторым успехом
     * Ожидается: правильная отслеживание статистики
     */
    @Test
    @DisplayName("должен отслеживать смешанные результаты с несколькими таблицами")
    void shouldTrackMixedResultsWithMultipleTables() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        backupData.put("categories", List.of(
                Map.of("id", 1L, "name", "Category 1")
        ));
        backupData.put("products", List.of(
                Map.of("id", 1L, "name", "Product 1")
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
        // Таблица products существует, но имеет ошибку (Mocked ниже)
        SchemaMeta productsSchema = new SchemaMeta("testdb", List.of(createTableMeta("products", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("name", "varchar(255)", false)
        ))));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of(usersTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("categories"))))
                .thenReturn(new SchemaMeta("testdb", List.of(categoriesTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("products"))))
                .thenReturn(productsSchema);
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("nonexistent_table"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        RestorePolicy policy = createRestorePolicyRelaxed();

        // Mock sql builder
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT INTO ... VALUES (...) ON DUPLICATE KEY UPDATE ...");

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(3, stats.getTablesProcessed(), "3 таблицы должны быть обработаны (users, categories, products)");
        assertEquals(1, stats.getTablesSkipped(), "1 таблица должна быть пропущена (nonexistent)");
        assertEquals(0, stats.getTablesFailed(), "Не должно быть ошибок таблиц с RELAXED_SCHEMA");
    }

    /**
     * Условие 14: STRICT_SCHEMA с отсутствующей таблицей и FAIL_FAST
     * Ожидается: немедленное исключение
     */
    @Test
    @DisplayName("должен выбросить исключение с жесткой схемой и политикой быстрого сбоя")
    void shouldThrowExceptionWithStrictSchemaAndFailFast() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFile(backupData);

        RestorePolicy policy = new RestorePolicy(
                SchemaPolicy.STRICT_SCHEMA,
                RowConflictPolicy.OVERWRITE_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.FAIL_FAST
        );

        // пользовательская таблица не найдена
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        // act и then
        RestoreOperationException ex = assertThrows(RestoreOperationException.class, () -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        // assert
        assertTrue(ex.getMessage().contains("Missing table"), "Исключение должно упоминать отсутствующую таблицу");
        assertEquals("users", ex.getTable(), "Имя таблицы должно быть включено в исключение");
        assertEquals(1, stats.getTablesFailed(), "Таблица должна быть помечена как неудачная");
        assertEquals(0, stats.getTablesProcessed(), "Не должно быть обработано таблиц");
    }

    /**
     * Условие 15: Поведение filterInsertColumns
     * Ожидается: только столбцы, общие для резервной копии и целевой базы, используются
     */
    @Test
    @DisplayName("должен фильтровать столбцы только до общих значений")
    void shouldFilterColumnsToCommonOnesOnly() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        // Резервная копия содержит определенные столбцы
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1", "extra_col", "extra_value")
        ));
        BackupFile backup = createBackupFile(backupData);

        // Целевая база данных содержит подмножество столбцов резервной копии
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta schema = new SchemaMeta("testdb", List.of(targetTable));

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);

        RestorePolicy policy = createRestorePolicyRelaxed();

        // Отслеживание того, какие столбцы передаются в sqlBuilder
        final List<String> capturedColumns = new ArrayList<>();
        when(sqlBuilder.buildInsertSql(anyString(), anyList()))
                .thenAnswer(invocation -> {
                    List<String> columns = invocation.getArgument(1, List.class);
                    capturedColumns.addAll(columns);
                    return "INSERT INTO users (" + String.join(",", columns) + ") VALUES (...) ON DUPLICATE KEY UPDATE ...";
                });

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");

        // Проверка того, что только общие столбцы были переданы (id, username - не extra_col)
        assertTrue(capturedColumns.contains("id"), "Столбец id должен быть включен");
        assertTrue(capturedColumns.contains("username"), "Столбец username должен быть включен");
        assertFalse(capturedColumns.contains("extra_col"), "Столбец extra_col должен быть отфильтрован");
    }
}
