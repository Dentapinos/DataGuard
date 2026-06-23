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
import com.dentapinos.dataguard.service.restore.strategy.AppendOnlyRestoreStrategy;
import com.dentapinos.dataguard.service.restore.strategy.RestoreStrategy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты стратегии AppendOnlyRestoreStrategy.
 * Проверяется логика восстановления базы данных в режиме append-only при различных сценариях.
 */
@DisplayName("Unit-test для стратегии AppendOnlyRestoreStrategy")
class AppendOnlyRestoreStrategyTest {

    @Mock
    private JdbcTemplateFactory jdbcTemplateFactory;

    @Mock
    private BatchImporter batchImporter;

    @Mock
    private SqlBuilderFactory sqlBuilderFactory;

    @Mock
    private DatabaseConfigurator databaseConfigurator;

    @Mock
    private DatabaseMetadataReader mysqlMetadataReader;

    @Mock
    private JdbcTemplateFactory.JdbcConnection jdbcConnection;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private AppendOnlyRestoreStrategy strategy;
    private AutoCloseable closeable;
    private RestoreStats stats;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);

        when(jdbcTemplateFactory.forDatabase(any(), any())).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);

        SqlBuilder mockSqlBuilder = mock(SqlBuilder.class);
        when(sqlBuilderFactory.getSqlBuilder(eq(RowConflictPolicy.SKIP_ON_CONFLICT)))
                .thenReturn(mockSqlBuilder);
        when(mockSqlBuilder.buildInsertSql(anyString(), anyList()))
                .thenReturn("INSERT IGNORE INTO test (id) VALUES (?)");

        strategy = new AppendOnlyRestoreStrategy(
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

    // ==================== Helpers ====================

    private DbCredentials createDbCredentials() {
        return new DbCredentials(
                "jdbc:mysql://localhost:3306/testdb",
                "testuser",
                "testpass"
        );
    }

    private ColumnMeta col(String name, String type, boolean nullable) {
        return new ColumnMeta(name, type, nullable, false);
    }

    private TableMeta table(String name, ColumnMeta... cols) {
        return new TableMeta(name, Arrays.asList(cols), List.of(), List.of(), List.of());
    }

    private BackupFile backup(Map<String, List<Map<String, Object>>> data) {
        SchemaMeta schema = new SchemaMeta("testdb", List.of());
        return new BackupFile("testdb", "mysql", schema, data, null);
    }

    private RestorePolicy policyLogAndContinue() {
        return new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.LOG_AND_CONTINUE
        );
    }

    private RestorePolicy policyFailFast() {
        return new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.FAIL_FAST
        );
    }

    // ==================== 1. Пустое имя целевой базы данных ====================

    @Test
    @DisplayName("должен пропустить все таблицы при пустом имени целевой базы данных")
    void shouldSkipAllTablesWhenTargetDatabaseNameIsEmpty() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1"),
                Map.of("id", 2L, "username", "user2")
        ));
        BackupFile backup = backup(backupData);

        // act
        strategy.restore(credentials, backup, "", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(0, stats.getTablesProcessed());
        assertEquals(1, stats.getTablesSkipped());
        assertEquals(0, stats.getRowsInserted());
        verifyNoInteractions(batchImporter);
    }

    // ==================== 2. Успешный сценарий: таблица существует, строки вставлены ====================

    @Test
    @DisplayName("должен вставить строки при существующей таблице")
    void shouldInsertRowsWhenTableExists() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = Map.of(
                "users", List.of(
                        Map.of("id", 1L, "username", "user1"),
                        Map.of("id", 2L, "username", "user2")
                )
        );
        BackupFile backup = backup(backupData);

        TableMeta usersMeta = table("users",
                col("id", "bigint(20)", false),
                col("username", "varchar(255)", false),
                col("email", "varchar(255)", true)
        );
        SchemaMeta schema = new SchemaMeta("testdb", List.of(usersMeta));

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(schema);

        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            List<Map<String, Object>> rows = invocation.getArgument(2);
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
                any(RestoreStrategy.class)
        );

        // act
        strategy.restore(credentials, backup, "testdb", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed());
        assertEquals(0, stats.getTablesSkipped());
        assertEquals(2, stats.getRowsInserted());
    }

    // ==================== 3. Отсутствующая таблица в целевой БД ====================

    @Test
    @DisplayName("должен пропустить таблицу при отсутствии в целевой БД")
    void shouldSkipTableWhenTableMissingInTargetDatabase() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = Map.of(
                "users", List.of(Map.of("id", 1L, "username", "user1"))
        );
        BackupFile backup = backup(backupData);

        // Схема без таблицы 'users'
        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        // act
        strategy.restore(credentials, backup, "testdb", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(0, stats.getTablesProcessed());
        assertEquals(1, stats.getTablesSkipped());
        assertEquals(0, stats.getRowsInserted());
        verifyNoInteractions(batchImporter);
    }

    // ==================== 4. Пустые строки для существующей таблицы ====================

    @Test
    @DisplayName("должен обработать таблицу без вставки строк при пустых данных")
    void shouldProcessTableAndInsertZeroRowsWhenRowsAreEmpty() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = Map.of(
                "users", List.of()
        );
        BackupFile backup = backup(backupData);

        TableMeta usersMeta = table("users",
                col("id", "bigint(20)", false),
                col("username", "varchar(255)", false)
        );
        SchemaMeta schema = new SchemaMeta("testdb", List.of(usersMeta));

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(schema);

        // batchImporter не должен вызываться при пустых строках

        // act
        strategy.restore(credentials, backup, "testdb", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed());
        assertEquals(0, stats.getTablesSkipped());
        assertEquals(0, stats.getRowsInserted());
        verifyNoInteractions(batchImporter);
    }

    // ==================== 5. Дублирующиеся строки (INSERT IGNORE semantics) ====================

    @Test
    @DisplayName("должен вставить часть строк и пропустить дубликаты при дублировании")
    void shouldInsertSomeRowsAndSkipDuplicatesWhenDuplicateRowsPresent() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = Map.of(
                "users", List.of(
                        Map.of("id", 1L, "username", "user1"),
                        Map.of("id", 2L, "username", "user2"),
                        Map.of("id", 3L, "username", "user3")
                )
        );
        BackupFile backup = backup(backupData);

        TableMeta usersMeta = table("users",
                col("id", "bigint(20)", false),
                col("username", "varchar(255)", false)
        );
        SchemaMeta schema = new SchemaMeta("testdb", List.of(usersMeta));

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(schema);

        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            // 2 inserted, 1 skipped
            s.setRowsInserted(s.getRowsInserted() + 2);
            s.setRowsSkipped(s.getRowsSkipped() + 1);
            return null;
        }).when(batchImporter).importTableRows(
                any(JdbcTemplate.class),
                eq("users"),
                anyList(),
                anyList(),
                anyString(),
                any(RestorePolicy.class),
                any(RestoreStats.class),
                any(RestoreStrategy.class)
        );

        // act
        strategy.restore(credentials, backup, "testdb", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed());
        assertEquals(2, stats.getRowsInserted());
        assertEquals(1, stats.getRowsSkipped());
    }

    // ==================== 6. Обработка ошибок: FAIL_FAST ====================

    @Test
    @DisplayName("должен распространить исключение при включенной политике FAIL_FAST")
    void shouldPropagateExceptionWhenFailFastPolicyEnabled() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = Map.of(
                "users", List.of(Map.of("id", 1L, "username", "user1"))
        );
        BackupFile backup = backup(backupData);

        TableMeta usersMeta = table("users",
                col("id", "bigint(20)", false),
                col("username", "varchar(255)", false)
        );
        SchemaMeta schema = new SchemaMeta("testdb", List.of(usersMeta));

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(schema);

        doThrow(new RestoreOperationException(
                "Test exception",
                "users",
                null,
                0,
                "Test error",
                new RuntimeException("Test cause")
        )).when(batchImporter).importTableRows(
                any(JdbcTemplate.class),
                eq("users"),
                anyList(),
                anyList(),
                anyString(),
                any(RestorePolicy.class),
                any(RestoreStats.class),
                any(RestoreStrategy.class)
        );

        // act
        RestoreOperationException ex = assertThrows(
                RestoreOperationException.class,
                () -> strategy.restore(credentials, backup, "testdb", policyFailFast(), stats, "backup.zip")
        );

        // assert
        assertEquals("users", ex.getTable());
        assertEquals(2, stats.getTablesFailed());
    }

    // ==================== 7. Обработка ошибок: LOG_AND_CONTINUE (отсутствующая таблица среди других) ====================

    @Test
    @DisplayName("должен обработать существующую и пропустить отсутствующую таблицу при логировании ошибок")
    void shouldProcessExistingAndSkipMissingTableWhenLogAndContinuePolicyEnabled() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(Map.of("id", 1L, "username", "user1")));
        backupData.put("orders", List.of(Map.of("id", 1L, "amount", 100.0)));
        BackupFile backup = backup(backupData);

        TableMeta usersMeta = table("users",
                col("id", "bigint(20)", false),
                col("username", "varchar(255)", false)
        );
        SchemaMeta usersSchema = new SchemaMeta("testdb", List.of(usersMeta));

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(usersSchema);
        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("orders"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            List<Map<String, Object>> rows = invocation.getArgument(2);
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
                any(RestoreStrategy.class)
        );

        // act
        strategy.restore(credentials, backup, "testdb", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed());
        assertEquals(1, stats.getTablesSkipped());
        assertEquals(0, stats.getTablesFailed());
        assertEquals(1, stats.getRowsInserted());
    }

    // ==================== 8. Дополнительные столбцы в целевой таблице (RELAXED_SCHEMA) ====================

    @Test
    @DisplayName("должен передать только общие столбцы при дополнительных в целевой таблице")
    void shouldPassOnlyCommonColumnsWhenExtraColumnsInTarget() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = Map.of(
                "users", List.of(Map.of("id", 1L, "username", "user1"))
        );
        BackupFile backup = backup(backupData);

        TableMeta usersMeta = table("users",
                col("id", "bigint(20)", false),
                col("username", "varchar(255)", false),
                col("email", "varchar(255)", true),
                col("created_at", "datetime", true)
        );
        SchemaMeta schema = new SchemaMeta("testdb", List.of(usersMeta));

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(schema);

        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> insertColumns = invocation.getArgument(3);
            assertTrue(insertColumns.contains("id"));
            assertTrue(insertColumns.contains("username"));
            assertFalse(insertColumns.contains("email"));
            assertFalse(insertColumns.contains("created_at"));

            RestoreStats s = invocation.getArgument(6);
            List<Map<String, Object>> rows = invocation.getArgument(2);
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
                any(RestoreStrategy.class)
        );

        // act
        strategy.restore(credentials, backup, "testdb", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed());
        assertEquals(1, stats.getRowsInserted());
    }

    // ==================== 9. Отсутствующие столбцы в бэкапе по сравнению с целевой таблицей ====================

    @Test
    @DisplayName("должен вставить строки по доступным столбцам при отсутствующих в бэкапе")
    void shouldInsertRowsForAvailableColumnsWhenColumnsMissingInBackup() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = Map.of(
                "users", List.of(Map.of("id", 1L, "username", "user1"))
        );
        BackupFile backup = backup(backupData);

        TableMeta usersMeta = table("users",
                col("id", "bigint(20)", false),
                col("username", "varchar(255)", false),
                col("email", "varchar(255)", true)
        );
        SchemaMeta schema = new SchemaMeta("testdb", List.of(usersMeta));

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(schema);

        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            List<Map<String, Object>> rows = invocation.getArgument(2);
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
                any(RestoreStrategy.class)
        );

        // act
        strategy.restore(credentials, backup, "testdb", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed());
        assertEquals(1, stats.getRowsInserted());
        assertEquals(0, stats.getTablesSkipped());
        verify(batchImporter, times(1)).importTableRows(
                any(JdbcTemplate.class),
                eq("users"),
                anyList(),
                anyList(),
                anyString(),
                any(RestorePolicy.class),
                any(RestoreStats.class),
                any(RestoreStrategy.class)
        );
    }

    // ==================== 10. Несколько таблиц: некоторые существуют, некоторые нет ====================

    @Test
    @DisplayName("должен обработать существующие и пропустить отсутствующие таблицы при наличии нескольких")
    void shouldProcessExistingAndSkipMissingTablesWhenMultipleTables() {
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
        BackupFile backup = backup(backupData);

        TableMeta usersMeta = table("users",
                col("id", "bigint(20)", false),
                col("username", "varchar(255)", false)
        );
        TableMeta categoriesMeta = table("categories",
                col("id", "bigint(20)", false),
                col("name", "varchar(255)", false)
        );

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of(usersMeta)));
        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("categories"))))
                .thenReturn(new SchemaMeta("testdb", List.of(categoriesMeta)));
        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("nonexistent_table"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        doAnswer(invocation -> {
            RestoreStats s = invocation.getArgument(6);
            String tableName = invocation.getArgument(1);
            List<Map<String, Object>> rows = invocation.getArgument(2);
            s.setRowsInserted(s.getRowsInserted() + rows.size());
            return null;
        }).when(batchImporter).importTableRows(
                any(JdbcTemplate.class),
                anyString(),
                anyList(),
                anyList(),
                anyString(),
                any(RestorePolicy.class),
                any(RestoreStats.class),
                any(RestoreStrategy.class)
        );

        // act
        strategy.restore(credentials, backup, "testdb", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed());
        assertEquals(1, stats.getTablesSkipped());
        assertEquals(3, stats.getRowsInserted()); // 2 users + 1 category
    }

    // ==================== 11. STRICT_SCHEMA все равно пропускает отсутствующую таблицу в APPEND_ONLY ====================

    @Test
    @DisplayName("должен пропустить отсутствующую таблицу даже при STRICT_SCHEMA в режиме append-only")
    void shouldSkipMissingTableEvenWithStrictSchemaInAppendOnlyMode() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = Map.of(
                "users", List.of(Map.of("id", 1L, "username", "user1"))
        );
        BackupFile backup = backup(backupData);

        RestorePolicy strictPolicy = new RestorePolicy(
                SchemaPolicy.STRICT_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.ENFORCE_ALL,
                ErrorPolicy.LOG_AND_CONTINUE
        );

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        // act
        strategy.restore(credentials, backup, "testdb", strictPolicy, stats, "backup.zip");

        // assert
        assertEquals(0, stats.getTablesProcessed());
        assertEquals(1, stats.getTablesSkipped());
        assertEquals(0, stats.getTablesFailed());
    }

    // ==================== 12. Нет общих столбцов между бэкапом и целевой таблицей ====================

    @Test
    @DisplayName("должен обработать таблицу без вставки строк при отсутствии общих столбцов")
    void shouldProcessTableWithoutInsertingRowsWhenNoCommonColumns() {
        // arrange
        DbCredentials credentials = createDbCredentials();

        Map<String, List<Map<String, Object>>> backupData = Map.of(
                "users", List.of(Map.of("different_column", "value"))
        );
        BackupFile backup = backup(backupData);

        TableMeta usersMeta = table("users",
                col("id", "bigint(20)", false),
                col("username", "varchar(255)", false)
        );
        SchemaMeta schema = new SchemaMeta("testdb", List.of(usersMeta));

        when(mysqlMetadataReader.readSchema(any(), any(), argThat(list -> list != null && list.contains("users"))))
                .thenReturn(schema);

        // act
        strategy.restore(credentials, backup, "testdb", policyLogAndContinue(), stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed());
        assertEquals(0, stats.getRowsInserted());
        assertEquals(0, stats.getTablesSkipped());
        verifyNoInteractions(batchImporter);
    }
}