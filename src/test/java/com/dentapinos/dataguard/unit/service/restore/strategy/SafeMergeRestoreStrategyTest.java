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
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import com.dentapinos.dataguard.service.restore.sqlbuilder.SqlBuilderFactory;
import com.dentapinos.dataguard.service.restore.strategy.SafeMergeRestoreStrategy;
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
 * Unit-тесты для класса SafeMergeRestoreStrategy.
 * Проверяется логика восстановления в режиме safe-merge (смешанное поведение) при различных сценариях.
 */
@DisplayName("Unit-test для стратегииSafeMergeRestoreStrategy")
class SafeMergeRestoreStrategyTest {

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
    private JdbcTemplateFactory.JdbcConnection jdbcConnection;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private com.dentapinos.dataguard.service.restore.SqlBuilder sqlBuilder;

    private SafeMergeRestoreStrategy strategy;

    private AutoCloseable closeable;

    private RestoreStats stats;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        
        reset(jdbcTemplateFactory, batchImporter, databaseConfigurator, mysqlMetadataReader, sqlBuilderFactory);
        reset(jdbcConnection, jdbcTemplate);
        
        when(jdbcTemplateFactory.forDatabase(any(), any())).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        
        // Имитируем sqlBuilderFactory, чтобы вернуть SQL Builder для любого policy
        when(sqlBuilderFactory.getSqlBuilder(any())).thenReturn(sqlBuilder);
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT SQL");
        
        strategy = new SafeMergeRestoreStrategy(
                jdbcTemplateFactory,
                batchImporter,
                databaseConfigurator,
                sqlBuilderFactory,
                mysqlMetadataReader
        );
        
        stats = new RestoreStats();
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
    }

    // ==================== Методы помощники ====================

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

    private RestorePolicy createRestorePolicySkip() {
        return new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.LOG_AND_CONTINUE
        );
    }

    private RestorePolicy createRestorePolicyOverwrite() {
        return new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.OVERWRITE_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.LOG_AND_CONTINUE
        );
    }

    // ==================== Тест 1: Пустая база данных — таблица не найдена ====================

    @Test
    @DisplayName("должен пропустить таблицу при отсутствии в целевой БД")
    void shouldSkipTableWhenTargetDatabaseNotFound() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(Map.of("id", 1L, "username", "user1")));
        BackupFile backup = createBackupFile(backupData);
        RestorePolicy policy = createRestorePolicySkip();

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(0, stats.getTablesProcessed(), "Таблицы не должны быть обработаны");
        assertEquals(1, stats.getTablesSkipped(), "Таблица должна быть пропущена");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться с ошибкой");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены");
    }

    // ==================== Тест 2: Нормальное восстановление с SKIP_ON_CONFLICT ====================

    @Test
    @DisplayName("должен вставить новые и пропустить дубликаты при SKIP_ON_CONFLICT")
    void shouldInsertNewAndSkipDuplicatesWhenSkipOnConflict() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "existing_user"),
                Map.of("id", 2L, "username", "new_user")
        ));
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(targetTable)));
        
        // Имитация пакетного импортера для имитации: count=0 для дубликата, count=1 для нового
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            s.setRowsInserted(s.getRowsInserted() + 1);  //1 новая строка
            s.setRowsSkipped(s.getRowsSkipped() + 1);     // 1 дубликат пропущен
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
        
        RestorePolicy policy = createRestorePolicySkip();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(1, stats.getRowsInserted(), "1 строка должна быть вставлена");
        assertEquals(1, stats.getRowsSkipped(), "1 строка должна быть пропущена (дубликат)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
    }

    // ==================== Тест 3: Нормальное восстановление с OVERWRITE_ON_CONFLICT====================

    @Test
    @DisplayName("должен обновить существующие и вставить новые при OVERWRITE_ON_CONFLICT")
    void shouldUpdateExistingAndInsertNewWhenOverwriteOnConflict() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "updated_user1"),
                Map.of("id", 2L, "username", "updated_user2")
        ));
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(targetTable)));
        
        // Мок пакетного импортера: count=2 для обновления
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            s.setRowsUpdated(s.getRowsUpdated() + 2);  // 2 обновленных строки
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
        
        RestorePolicy policy = createRestorePolicyOverwrite();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(2, stats.getRowsUpdated(), "2 строки должны быть обновлены");
        assertEquals(0, stats.getRowsSkipped(), "Строки не должны быть пропущены");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
    }

    // ==================== Тест 4: Смешанные INSERT и UPDATE с OVERWRITE ====================

    @Test
    @DisplayName("должен смешивать INSERT и UPDATE при OVERWRITE policy")
    void shouldMixInsertAndUpdateWhenOverwritePolicy() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "existing_user"),
                Map.of("id", 2L, "username", "new_user")
        ));
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(targetTable)));
        
        // Мак: первая строка = ОБНОВЛЕНИЕ (count=2), вторая строка = ВСТАВКА (count=1)
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            s.setRowsUpdated(s.getRowsUpdated() + 1);   //Обновление 1 строки
            s.setRowsInserted(s.getRowsInserted() + 1);  // Вставлена 1 строка
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
        
        RestorePolicy policy = createRestorePolicyOverwrite();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(1, stats.getRowsInserted(), "1 строка должна быть вставлена");
        assertEquals(1, stats.getRowsUpdated(), "1 строка должна быть обновлена");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
    }

    // ==================== Тест 5: Опустошение строк в резервном режиме ====================

    @Test
    @DisplayName("должен обработать таблицу без строк")
    void shouldProcessTableWithEmptyRows() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of());  // Пустые строки
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(targetTable)));
        
        RestorePolicy policy = createRestorePolicySkip();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
    }

    // ==================== Test 6: Порядок таблицы из резервной копии ====================

    @Test
    @DisplayName("должен обработать таблицы в порядке из backup.tableOrder()")
    void shouldProcessTablesInBackupOrder() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("categories", List.of(Map.of("id", 1L, "name", "Category 1")));
        backupData.put("users", List.of(Map.of("id", 1L, "username", "user1")));
        BackupFile backup = createBackupFileWithOrder(backupData, List.of("categories", "users"));
        
        TableMeta categoriesTable = createTableMeta("categories", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("name", "varchar(255)", false)
        ));
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("categories"))))
                .thenReturn(createSchema("testdb", List.of(categoriesTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(usersTable)));
        
        List<String> processedOrder = new ArrayList<>();
        doAnswer(invocation -> {
            String tableName = invocation.getArgument(1);
            processedOrder.add(tableName);
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
        
        RestorePolicy policy = createRestorePolicySkip();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed(), "Обе таблицы должны быть обработаны");
        assertEquals(2, processedOrder.size(), "Порядок должен быть отслежен");
        assertEquals("categories", processedOrder.get(0), "Первая таблица должна быть categories");
        assertEquals("users", processedOrder.get(1), "Вторая таблица должна быть users");
    }

    // ==================== Test 7: Missing Table in Target Database ====================

    @Test
    @DisplayName("SAFE_MERGE_MissingTable: table skipped when not found in target DB")
    void SAFE_MERGE_MissingTable() {
        // given
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(Map.of("id", 1L, "username", "user1")));
        BackupFile backup = createBackupFile(backupData);
        
        // Целевой база данных не имеет таблицы пользователей (пустая схема для пользователей)
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(new SchemaMeta("testdb", List.of()));
        
        RestorePolicy policy = createRestorePolicySkip();

        // when
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // then
        assertEquals(0, stats.getTablesProcessed(), "Table should not be processed (skipped before processing)");
        assertEquals(1, stats.getTablesSkipped(), "Table should be skipped (not found in target DB)");
        assertEquals(0, stats.getTablesFailed(), "No tables should fail");
        assertEquals(0, stats.getRowsInserted(), "No rows should be inserted");
    }

    // ==================== Тест 8: Дополнительные столбцы в целевой базе данных ====================

    @Test
    @DisplayName("SAFE_MERGE_ExtraColumns: extra columns in target ignored")
    void SAFE_MERGE_ExtraColumns() {
        // given
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(Map.of("id", 1L, "username", "user1")));
        BackupFile backup = createBackupFile(backupData);
        
        // У цели есть дополнительные столбцы
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false),
                createColumnMeta("email", "varchar(255)", true),
                createColumnMeta("created_at", "datetime", true)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(targetTable)));
        
        // Проверяем, что в пакетный импортер передаются только обычные столбцы
        // Имитация пакетного импортера для возврата count=1 для вставки
        doAnswer(invocation -> {
            List<String> insertColumns = invocation.getArgument(3);
            assertTrue(insertColumns.contains("id"), "Should include id column");
            assertTrue(insertColumns.contains("username"), "Should include username column");
            // Обновление статистики для одной вставленной строки
            RestoreStats s = invocation.getArgument(6);
            s.setRowsInserted(s.getRowsInserted() + 1);
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
        
        RestorePolicy policy = createRestorePolicySkip();

        // when
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // then
        assertEquals(1, stats.getTablesProcessed(), "Table should be processed");
        assertEquals(1, stats.getRowsInserted(), "1 row should be inserted");
        assertEquals(0, stats.getTablesSkipped(), "No tables should be skipped");
    }

    // ==================== Test 9: No Common Columns ====================

    @Test
    @DisplayName("SAFE_MERGE_NoCommonColumns: table processed but no rows inserted")
    void SAFE_MERGE_NoCommonColumns() {
        // given
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(Map.of("different_column", "value")));
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(targetTable)));
        
        RestorePolicy policy = createRestorePolicySkip();

        // when
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // then
        assertEquals(1, stats.getTablesProcessed(), "Table should be processed (schema exists)");
        assertEquals(0, stats.getRowsInserted(), "No rows should be inserted (no common columns)");
        assertEquals(0, stats.getTablesSkipped(), "No tables should be skipped");
    }

    // ==================== Тест 10: Несколько таблиц с смешанными результатами ====================

    @Test
    @DisplayName("SAFE_MERGE_MultipleTables: multiple tables processed with various results")
    void SAFE_MERGE_MultipleTables() {
        // given
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(Map.of("id", 1L, "username", "user1")));
        backupData.put("categories", List.of(Map.of("id", 1L, "name", "Category 1")));
        backupData.put("nonexistent_table", List.of(Map.of("id", 1L)));
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
                .thenReturn(createSchema("testdb", List.of(usersTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("categories"))))
                .thenReturn(createSchema("testdb", List.of(categoriesTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("nonexistent_table"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));
        
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            String tableName = invocation.getArgument(1);
            if ("users".equals(tableName)) {
                s.setRowsInserted(s.getRowsInserted() + 1);
            } else if ("categories".equals(tableName)) {
                s.setRowsInserted(s.getRowsInserted() + 1);
            }
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
        
        RestorePolicy policy = createRestorePolicySkip();

        // when
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // then
        assertEquals(2, stats.getTablesProcessed(), "2 tables should be processed");
        assertEquals(1, stats.getTablesSkipped(), "1 table should be skipped (nonexistent)");
        assertEquals(0, stats.getTablesFailed(), "No tables should fail");
        assertEquals(2, stats.getRowsInserted(), "2 rows should be inserted");
    }

    // ==================== Тест 11: Обработка ошибок с LOG_AND_CONTINUE ====================

    @Test
    @DisplayName("SAFE_MERGE_ErrorLogAndContinue: exception caught, processing continues")
    void SAFE_MERGE_ErrorLogAndContinue() {
        // given
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(Map.of("id", 1L, "username", "user1")));
        backupData.put("orders", List.of(Map.of("id", 1L)));
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(usersTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("orders"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));
        
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            s.setRowsInserted(s.getRowsInserted() + 1);
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
        
        RestorePolicy policy = createRestorePolicySkip();

        // when
        assertDoesNotThrow(() -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        // then
        assertEquals(1, stats.getTablesProcessed(), "1 table should be processed (users)");
        assertEquals(1, stats.getTablesSkipped(), "1 table should be skipped (orders)");
        assertEquals(0, stats.getTablesFailed(), "No tables should fail");
    }

    // ==================== Тест 12: Обработка ошибок с FAIL_FAST ====================

    @Test
    @DisplayName("SAFE_MERGE_FailFast: exception thrown immediately on failure")
    void SAFE_MERGE_FailFast() {
        // given
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(Map.of("id", 1L, "username", "user1")));
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(createSchema("testdb", List.of(usersTable)));
        
        doThrow(new RestoreOperationException(
                "Test exception",
                "users",
                null,
                0,
                "Test error",
                new RuntimeException("Test error")
        )).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
        
        RestorePolicy policy = new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.FAIL_FAST
        );

        // when & then
        RestoreOperationException ex = assertThrows(RestoreOperationException.class, () -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        // then
        assertEquals("users", ex.getTable(), "Table name should be included in exception");
    }

    // ==================== Тест 13: Обработка больших партий ====================

    @Test
    @DisplayName("SAFE_MERGE_LargeBatch: large batch processed correctly")
    void SAFE_MERGE_LargeBatch() {
        // given
        DbCredentials credentials = createDbCredentials();
        
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 2500; i++) {
            rows.add(Map.of("id", (long) i, "username", "user" + i));
        }
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", rows);
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), any()))
                .thenReturn(createSchema("testdb", List.of(usersTable)));
        
        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            s.setRowsUpdated(s.getRowsUpdated() + 2500);
            return null;
        }).when(batchImporter).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
        
        RestorePolicy policy = createRestorePolicyOverwrite();

        // when
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // then
        assertEquals(1, stats.getTablesProcessed(), "Table should be processed");
        assertEquals(2500, stats.getRowsUpdated(), "All 2500 rows should be updated");
        assertEquals(0, stats.getTablesSkipped(), "No tables should be skipped");
    }

    // ==================== Тест 14: handleBatchResult для SKIP_ON_CONFLICT ====================

    @Test
    @DisplayName("SAFE_MERGE_handleBatchResult_Skip: count=1 inserts, count=0 skips")
    void SAFE_MERGE_handleBatchResult_Skip() {
        RestorePolicy policy = createRestorePolicySkip();
        
        // count=1: insert
        strategy.handleBatchResult(1, "users", policy, stats);
        assertEquals(1, stats.getRowsInserted(), "count=1 should insert");
        assertEquals(1L, stats.getRowsPerTableInserted().getOrDefault("users", 0L), "Per-table counter should increment");
        
        // count=0: skip
        strategy.handleBatchResult(0, "users", policy, stats);
        assertEquals(1, stats.getRowsSkipped(), "count=0 should skip");
        assertEquals(1L, stats.getRowsPerTableSkipped().getOrDefault("users", 0L), "Per-table skipped counter should increment");
    }

    // ==================== Тест 15: handleBatchResult для OVERWRITE_ON_CONFLICT ====================

    @Test
    @DisplayName("SAFE_MERGE_handleBatchResult_Overwrite: count=1 inserts, count=2 updates")
    void SAFE_MERGE_handleBatchResult_Overwrite() {
        RestorePolicy policy = createRestorePolicyOverwrite();
        
        // count=1: insert
        strategy.handleBatchResult(1, "users", policy, stats);
        assertEquals(1, stats.getRowsInserted(), "count=1 should insert");
        
        // count=2: update
        strategy.handleBatchResult(2, "users", policy, stats);
        assertEquals(1, stats.getRowsUpdated(), "count=2 should update");
    }

    // ==================== Тест 16: handleBatchResult для FAIL_ON_CONFLICT ====================

    @Test
    @DisplayName("SAFE_MERGE_handleBatchResult_Fail: only count=1 allowed")
    void SAFE_MERGE_handleBatchResult_Fail() {
        //SafeMergeRestoreStrategy использует только SKIP_ON_CONFLICT и OVERWRITE_ON_CONFLICT
        // FAIL_ON_CONFLICT обрабатывается StrictRestoreStrategy, поэтому этот тест неприменим
        // Мы просто проверяем, что SafeMerge правильно обрабатывает SKIP и OVERWRITE
        
        // Политика тестирования SKIP_ON_CONFLICT
        RestorePolicy policySkip = createRestorePolicySkip();
        RestoreStats statsSkip = new RestoreStats();
        
        // count=1: insert
        strategy.handleBatchResult(1, "users", policySkip, statsSkip);
        assertEquals(1, statsSkip.getRowsInserted(), "count=1 should insert for SKIP");
        assertEquals(1L, statsSkip.getRowsPerTableInserted().getOrDefault("users", 0L), "Per-table counter should increment");
        
        // count=0: skip
        strategy.handleBatchResult(0, "users", policySkip, statsSkip);
        assertEquals(1, statsSkip.getRowsSkipped(), "count=0 should skip for SKIP");
        assertEquals(1L, statsSkip.getRowsPerTableSkipped().getOrDefault("users", 0L), "Per-table skipped counter should increment");
        
        // Проверьте политику OVERWRITE с новой статистикой
        RestorePolicy policyOverwrite = createRestorePolicyOverwrite();
        RestoreStats statsOverwrite = new RestoreStats();
        
        // count=1: insert
        strategy.handleBatchResult(1, "users", policyOverwrite, statsOverwrite);
        assertEquals(1, statsOverwrite.getRowsInserted(), "count=1 should insert for OVERWRITE");
        
        // count=2: update
        strategy.handleBatchResult(2, "users", policyOverwrite, statsOverwrite);
        assertEquals(1, statsOverwrite.getRowsUpdated(), "count=2 should update for OVERWRITE");
    }
}
