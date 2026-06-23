package com.dentapinos.dataguard.unit.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.*;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.RestoreStatus;
import com.dentapinos.dataguard.enums.policy.ErrorPolicy;
import com.dentapinos.dataguard.enums.policy.ForeignKeyPolicy;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.enums.policy.SchemaPolicy;
import com.dentapinos.dataguard.exception.RestoreOperationException;
import com.dentapinos.dataguard.report.RestoreReport;
import com.dentapinos.dataguard.report.RestoreSummary;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.service.restore.BatchImporter;
import com.dentapinos.dataguard.service.restore.SqlBuilder;
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import com.dentapinos.dataguard.service.restore.sqlbuilder.SqlBuilderFactory;
import com.dentapinos.dataguard.service.restore.strategy.AbstractRestoreStrategy;
import com.dentapinos.dataguard.service.restore.strategy.StrictRestoreStrategy;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для StrictRestoreStrategy.
 * Проверяет строгую стратегию восстановления с проверкой схемы и обработкой конфликтов.
 */
@DisplayName("Unit-test для стратегии StrictRestoreStrategy")
class StrictRestoreStrategyTest {

    private BatchImporter batchImporter;
    private DatabaseMetadataReader metadataReader;
    private SqlBuilderFactory sqlBuilderFactory;
    private SqlBuilder sqlBuilder;

    private StrictRestoreStrategy strictStrategy;

    private DbCredentials dbCredentials;

    @BeforeEach
    void setUp() {
        JdbcTemplateFactory jdbcTemplateFactory = mock(JdbcTemplateFactory.class);
        JdbcTemplateFactory.JdbcConnection jdbcConnection = mock(JdbcTemplateFactory.JdbcConnection.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        batchImporter = mock(BatchImporter.class);
        DatabaseConfigurator databaseConfigurator = mock(DatabaseConfigurator.class);
        metadataReader = mock(DatabaseMetadataReader.class);
        sqlBuilderFactory = mock(SqlBuilderFactory.class);
        sqlBuilder = mock(SqlBuilder.class);

        when(jdbcTemplateFactory.forDatabase(any(), anyString())).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);

        when(sqlBuilderFactory.getSqlBuilder(any())).thenReturn(sqlBuilder);
        when(sqlBuilder.buildInsertSql(anyString(), anyList())).thenReturn("INSERT_SQL");

        strictStrategy = new StrictRestoreStrategy(
                jdbcTemplateFactory,
                batchImporter,
                databaseConfigurator,
                metadataReader,
                sqlBuilderFactory
        );

        dbCredentials = new DbCredentials("host",  "user", "pass");
    }

    private RestorePolicy strictPolicy(ErrorPolicy errorPolicy) {
        return new RestorePolicy(
                SchemaPolicy.STRICT_SCHEMA,
                RowConflictPolicy.FAIL_ON_CONFLICT,
                ForeignKeyPolicy.TEMP_DISABLE,
                errorPolicy
        );
    }

    private TableMeta usersMeta;
    
    private BackupFile backupWithTable(String tableName, List<Map<String, Object>> rows) {
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        data.put(tableName, rows);
        
        if (usersMeta == null) {
            usersMeta = tableMeta("users", List.of("id", "username", "email"));
        }
        
        TableMeta tableMeta = tableMeta(tableName, rows.isEmpty() ? List.of() : new ArrayList<>(rows.get(0).keySet()));
        SchemaMeta sm = new SchemaMeta("backup-id", List.of(tableMeta));
        
        BackupFile backup = new BackupFile(
                "backup-id",
                "backup-name",
                sm,
                data,
                List.of(tableName)
        );
        return backup;
    }

    private TableMeta tableMeta(String tableName, List<String> columns) {
        List<ColumnMeta> columnMetas = new ArrayList<>();
        for (String c : columns) {
            columnMetas.add(new ColumnMeta(c, "varchar", false, false));
        }
        return new TableMeta(
                tableName,
                columnMetas,
                List.of(), // индексы
                List.of(), // ограничения
                null
        );
    }

    private void mockSchema(String dbName, TableMeta... tables) {
        SchemaMeta schemaMeta = new SchemaMeta(dbName, Arrays.asList(tables));
        when(metadataReader.readSchema(any(), eq(dbName), anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> requestedTables = invocation.getArgument(2);
            List<TableMeta> resultTables = new ArrayList<>();
            for (String tName : requestedTables) {
                Arrays.stream(tables)
                        .filter(t -> t.name().equals(tName))
                        .findFirst()
                        .ifPresent(resultTables::add);
            }
            return new SchemaMeta(dbName, resultTables);
        });
    }

    /**
     * STRICT_SCHEMA: если в целевой таблице есть столбец, которого нет в бэкапе,
     * StrictRestoreStrategy должна выбросить RestoreOperationException.
     */
    @Test
    @DisplayName("должен выбросить исключение при наличии столбца в целевой таблице, отсутствующем в бэкапе")
    void shouldThrowExceptionWhenTargetTableHasColumnMissingInBackup() {
        // arrange
        String targetDb = "test_db";
        String tableName = "users";

        TableMeta usersMeta = tableMeta(tableName, List.of("id", "username", "email"));
        mockSchema(targetDb, usersMeta);

        List<Map<String, Object>> rows = List.of(
                new HashMap<>(Map.of(
                        "id", 1,
                        "username", "user1"
                ))
        );
        BackupFile backup = backupWithTable(tableName, rows);

        RestorePolicy policy = strictPolicy(ErrorPolicy.FAIL_FAST);
        RestoreStats stats = new RestoreStats();

        // act & assert
        RestoreOperationException ex = assertThrows(
                RestoreOperationException.class,
                () -> strictStrategy.restore(dbCredentials, backup, targetDb, policy, stats, "backup-file.json")
        );

        assertTrue(ex.getMessage().contains("Столбец отсутствует в резервной копии"));
        assertTrue(ex.getMessage().contains("users.email"));
        assertTrue(stats.getTablesFailed() >= 1);
    }

    /**
     * FAIL_FAST: если при импорте строк возникает RestoreOperationException,
     * он должен быть проброшен наружу, а восстановление прервано.
     */
    @Test
    @DisplayName("должен прервать восстановление и пробросить исключение при FAIL_FAST")
    void shouldInterruptRestoreAndThrowExceptionWhenFailFastAndImportFails() {
        // arrange
        String targetDb = "test_db";
        String tableName = "users";

        TableMeta usersMeta = tableMeta(tableName, List.of("id", "username"));
        mockSchema(targetDb, usersMeta);

        List<Map<String, Object>> rows = List.of(
                new HashMap<>(Map.of(
                        "id", 1,
                        "username", "user1"
                ))
        );
        BackupFile backup = backupWithTable(tableName, rows);

        RestorePolicy policy = strictPolicy(ErrorPolicy.FAIL_FAST);
        RestoreStats stats = new RestoreStats();

        doThrow(new RestoreOperationException(
                "Test exception",
                tableName,
                null,
                0,
                "Test exception",
                null
        )).when(batchImporter).importTableRows(
                any(JdbcTemplate.class),
                eq(tableName),
                anyList(),
                anyList(),
                anyString(),
                eq(policy),
                eq(stats),
                any(AbstractRestoreStrategy.class)
        );

        // act & assert
        RestoreOperationException ex = assertThrows(
                RestoreOperationException.class,
                () -> strictStrategy.restore(dbCredentials, backup, targetDb, policy, stats, "backup-file.json")
        );

        assertTrue(ex.getMessage().contains("Test exception"));
        assertTrue(stats.getTablesFailed() >= 1);
    }

    /**
     * LOG_AND_CONTINUE: при ошибке импорта строк RestoreOperationException
     * не должен прерывать весь процесс — ошибка логируется, таблица помечается как failed,
     * но метод restore не бросает исключение наружу.
     */
    @Test
    @DisplayName("должен продолжить восстановление при ошибке импорта с LOG_AND_CONTINUE")
    void shouldContinueRestoreOnErrorWhenLogAndContinuePolicy() {
        // arrange
        String targetDb = "test_db";
        String tableName1 = "users";
        String tableName2 = "orders";

        TableMeta usersMeta = tableMeta(tableName1, List.of("id"));
        TableMeta ordersMeta = tableMeta(tableName2, List.of("id"));
        mockSchema(targetDb, usersMeta, ordersMeta);

        List<Map<String, Object>> usersRows = List.of(
                new HashMap<>(Map.of("id", 1))
        );
        List<Map<String, Object>> ordersRows = List.of(
                new HashMap<>(Map.of("id", 10))
        );

        SchemaMeta backupSchemaMeta = new SchemaMeta("backup-id", List.of(usersMeta, ordersMeta));
        Map<String, List<Map<String, Object>>> data = new LinkedHashMap<>();
        data.put(tableName1, usersRows);
        data.put(tableName2, ordersRows);

        BackupFile backup = new BackupFile(
                "backup-id",
                "backup-name",
                backupSchemaMeta,
                data,
                List.of(tableName1, tableName2)
        );

        RestorePolicy policy = strictPolicy(ErrorPolicy.LOG_AND_CONTINUE);
        RestoreStats stats = new RestoreStats();

        doThrow(new RestoreOperationException(
                "Test exception",
                tableName1,
                null,
                0,
                "Test exception",
                null
        )).when(batchImporter).importTableRows(
                any(JdbcTemplate.class),
                eq(tableName1),
                anyList(),
                anyList(),
                anyString(),
                eq(policy),
                eq(stats),
                any(AbstractRestoreStrategy.class)
        );

        // act & assert
        assertDoesNotThrow(() ->
                strictStrategy.restore(dbCredentials, backup, targetDb, policy, stats, "backup-file.json")
        );

        assertTrue(stats.getTablesFailed() >= 1);
        assertTrue(stats.getTablesProcessed() >= 2);
    }

    // ---------------- Дополнительные проверки поведения STRICT ----------------

    /**
     * Если таблица из бэкапа отсутствует в целевой БД — STRICT_SCHEMA:
     * должен быть брошен RestoreOperationException.
     */
    @Test
    @DisplayName("должен выбросить исключение при отсутствии таблицы в целевой базе")
    void shouldThrowExceptionWhenTableIsMissingInTargetDatabase() {
        // arrange
        String targetDb = "test_db";
        String tableName = "missing_table";

        when(metadataReader.readSchema(any(), eq(targetDb), anyList()))
                .thenReturn(new SchemaMeta(targetDb, List.of()));

        List<Map<String, Object>> rows = List.of(new HashMap<>(Map.of("id", 1)));
        BackupFile backup = backupWithTable(tableName, rows);

        RestorePolicy policy = strictPolicy(ErrorPolicy.FAIL_FAST);
        RestoreStats stats = new RestoreStats();

        // act & assert
        RestoreOperationException ex = assertThrows(
                RestoreOperationException.class,
                () -> strictStrategy.restore(dbCredentials, backup, targetDb, policy, stats, "backup-file.json")
        );

        assertTrue(ex.getMessage().contains("Таблица"), "Сообщение должно содержать слово 'Таблица'");
        assertTrue(ex.getMessage().contains("отсутствует"), "Сообщение должно содержать 'отсутствует'");
        assertTrue(ex.getMessage().contains(tableName), "Сообщение должно содержать имя таблицы: " + tableName);
    }

    /**
     * STRICT: проверка обработки ошибки импорта строк.
     * В режиме FAIL_FAST ошибка из batchImporter должна быть проброшена.
     */
    @Test
    @DisplayName("должен пробросить ошибку импорта при FAIL_FAST")
    void shouldThrowImportErrorWhenFailFast() {
        // arrange
        String targetDb = "test_db";
        String tableName = "users";

        TableMeta usersMeta = tableMeta(tableName, List.of("id", "username"));
        mockSchema(targetDb, usersMeta);

        List<Map<String, Object>> rows = List.of(
                new HashMap<>(Map.of("id", 1, "username", "user1"))
        );
        BackupFile backup = backupWithTable(tableName, rows);

        RestorePolicy policy = strictPolicy(ErrorPolicy.FAIL_FAST);
        RestoreStats stats = new RestoreStats();

        doThrow(new RestoreOperationException(
                "Ошибка импорта строк",
                tableName,
                null,
                0,
                "Ошибка импорта строк",
                new Exception("Batch import failed")
        )).when(batchImporter).importTableRows(
                any(JdbcTemplate.class),
                eq(tableName),
                anyList(),
                anyList(),
                anyString(),
                eq(policy),
                eq(stats),
                any(AbstractRestoreStrategy.class)
        );

        // act & assert
        RestoreOperationException ex = assertThrows(
                RestoreOperationException.class,
                () -> strictStrategy.restore(dbCredentials, backup, targetDb, policy, stats, "backup-file.json")
        );

        assertTrue(ex.getMessage().contains("Ошибка импорта строк"));
        assertTrue(stats.getTablesFailed() >= 1);
    }

    /**
     * Корректный сценарий: схема совпадает, данные вставляются без ошибок.
     */
    @Test
    @DisplayName("должен успешно восстановить при совпадающей схеме и отсутствии ошибок")
    void shouldRestoreSuccessfullyWhenSchemaMatches() {
        // arrange
        String targetDb = "test_db";
        String tableName = "users";

        TableMeta usersMeta = tableMeta(tableName, List.of("id", "username"));
        mockSchema(targetDb, usersMeta);

        List<Map<String, Object>> rows = List.of(
                new HashMap<>(Map.of(
                        "id", 1,
                        "username", "user1"
                ))
        );
        BackupFile backup = backupWithTable(tableName, rows);

        RestorePolicy policy = strictPolicy(ErrorPolicy.FAIL_FAST);
        RestoreStats stats = new RestoreStats();

        // act & assert
        assertDoesNotThrow(() ->
                strictStrategy.restore(dbCredentials, backup, targetDb, policy, stats, "backup-file.json")
        );

        verify(sqlBuilderFactory, atLeastOnce())
                .getSqlBuilder(RowConflictPolicy.FAIL_ON_CONFLICT);

        ArgumentCaptor<List<String>> colsCaptor = ArgumentCaptor.forClass(List.class);

        verify(sqlBuilder, atLeastOnce())
                .buildInsertSql(eq(tableName), colsCaptor.capture());

        List<List<String>> allCaptured = colsCaptor.getAllValues();
        List<String> cols = allCaptured.get(allCaptured.size() - 1);

        Assertions.assertThat(cols.contains("id")).isTrue();
        Assertions.assertThat(cols.contains("username")).isTrue();
    }

    /**
     * Проверяем handleBatchResult в STRICT: допускается только count == 1,
     * иначе бросается RestoreOperationException.
     */
    @Test
    @DisplayName("должен принять count == 1 и выбросить исключение при count != 1")
    void shouldAcceptCountOneAndThrowExceptionOnConflict() {
        // arrange
        RestorePolicy policy = strictPolicy(ErrorPolicy.FAIL_FAST);
        RestoreStats stats = new RestoreStats();

        // act & assert
        assertDoesNotThrow(() ->
                strictStrategy.handleBatchResult(1, "users", policy, stats)
        );
        assertEquals(1, stats.getRowsInserted());
        assertEquals(1L, stats.getRowsPerTableInserted().getOrDefault("users", 0L));

        RestoreOperationException ex = assertThrows(
                RestoreOperationException.class,
                () -> strictStrategy.handleBatchResult(2, "users", policy, stats)
        );
        assertTrue(ex.getMessage().contains("Нарушение FAIL_ON_CONFLICT"));
    }

    // ---------------- Проверка отчёта (пример использования RestoreReport) ----------------

    @Test
    @DisplayName("должен корректно сформировать отчёт восстановления")
    void shouldBuildRestoreReportCorrectly() {
        // arrange
        RestoreStats stats = new RestoreStats();
        stats.setTablesTotal(1);
        stats.setTablesProcessed(1);
        stats.setTablesFailed(0);
        stats.setRowsInserted(1);

        RestoreSummary summary = new RestoreSummary(
                1,
                1,
                0,
                0,
                1,
                0,
                0,
                Map.of("users", 1L),
                Map.of(),
                Map.of()
        );
        RestoreReport report = new RestoreReport(
                Instant.now(),
                Instant.now(),
                "test_db",
                RestoreMode.STRICT,
                RestoreStatus.SUCCESS,
                summary
        );

        // act & assert
        assertEquals("test_db", report.targetDatabase());
        assertEquals(RestoreMode.STRICT, report.mode());
        assertEquals(RestoreStatus.SUCCESS, report.status());
        assertEquals(1, report.summary().tablesTotal());
    }
}
