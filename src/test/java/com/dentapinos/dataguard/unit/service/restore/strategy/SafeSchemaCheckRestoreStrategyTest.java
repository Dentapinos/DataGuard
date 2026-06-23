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
import com.dentapinos.dataguard.service.restore.strategy.SafeSchemaCheckRestoreStrategy;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit-тесты для SafeSchemaCheckRestoreStrategy.
 * Проверяет логику валидации схемы при восстановлении без выполнения реального импорта данных.
 */
@DisplayName("Unit-test для стратегии SafeSchemaCheckRestoreStrategy")
class SafeSchemaCheckRestoreStrategyTest {

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

    private SafeSchemaCheckRestoreStrategy strategy;

    private AutoCloseable closeable;

    private RestoreStats stats;

    @BeforeEach
    void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        
        reset(jdbcTemplateFactory, batchImporter, databaseConfigurator, mysqlMetadataReader);
        reset(jdbcConnection, jdbcTemplate);
        
        when(jdbcTemplateFactory.forDatabase(any(), any())).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        
        strategy = new SafeSchemaCheckRestoreStrategy(
                jdbcTemplateFactory,
                batchImporter,
                databaseConfigurator,
                mysqlMetadataReader
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

    // ==================== Пустая база данных ====================

    /**
     * Условие 1: Пустая база данных
     * Ожидаемое: tablesSkipped++, импорт не производится
     * Проверки:
     * - Только валидация схемы, данные не записываются
     */
    @Test
    @DisplayName("должен пропустить таблицы при пустой базе данных")
    void shouldSkipTablesWhenDatabaseIsEmpty() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFile(backupData);
        RestorePolicy policy = createRestorePolicy();

        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesSkipped(), "Таблица должна быть пропущена для пустой базы");
        assertEquals(0, stats.getTablesProcessed(), "Таблицы не должны быть обработаны");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены (нет операций с данными)");
    }

    // ==================== Схема валидна — Совпадение столбцов ====================

    /**
     * Условие 2: Схема валидна - совпадающие столбцы
     * Ожидаемое: tablesProcessed++, импорт не производится
     * Проверки:
     * - Валидация схемы прошла успешно
     * - Операции с данными не производятся
     */
    @Test
    @DisplayName("должен пройти валидацию схемы при совпадающих столбцах")
    void shouldPassSchemaValidationWhenColumnsMatch() {
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
        SchemaMeta schema = createSchema("testdb", List.of(targetTable));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);
        
        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана (схема валидирована)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        assertEquals(0, stats.getRowsInserted(), "Строки не должны быть вставлены (только проверка схемы)");
        
        verify(batchImporter, never()).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
    }

    // ==================== Отсутствующая таблица с RELAXED_SCHEMA ====================

    /**
     * Условие 3: Отсутствующая таблица при RELAXED_SCHEMA
     * Ожидаемое: tablesSkipped++, исключение не выбрасывается
     * Проверки:
     * - Пропуск отсутствующей таблицы с предупреждением
     * - Нет выброса исключения
     */
    @Test
    @DisplayName("должен пропустить отсутствующую таблицу при RELAXED_SCHEMA")
    void shouldSkipMissingTableWhenSchemaPolicyIsRelaxed() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta otherTable = createTableMeta("orders", List.of(
                createColumnMeta("id", "bigint(20)", false)
        ));
        SchemaMeta schema = createSchema("testdb", List.of(otherTable));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));
        
        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(0, stats.getTablesProcessed(), "Таблицы не должны быть обработаны");
        assertEquals(1, stats.getTablesSkipped(), "Таблица должна быть пропущена");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
    }

    // ==================== Отсутствующая таблица с STRICT_SCHEMA ====================

    /**
     * Условие 4: Отсутствующая таблица при STRICT_SCHEMA
     * Ожидаемое: немедленный выброс исключения
     */
    @Test
    @DisplayName("должен выбросить исключение при отсутствии таблицы при STRICT_SCHEMA")
    void shouldThrowExceptionWhenTableIsMissingWithStrictSchema() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFile(backupData);
        
        RestorePolicy policy = new RestorePolicy(
                SchemaPolicy.STRICT_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.ENFORCE_ALL,
                ErrorPolicy.FAIL_FAST
        );
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));
        
        // act & assert
        RestoreOperationException ex = assertThrows(RestoreOperationException.class, () -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        assertTrue(ex.getMessage().contains("Missing table"), "Исключение должно содержать текст об отсутствии таблицы");
        assertEquals("users", ex.getTable(), "Имя таблицы должно быть включено в исключение");
        assertEquals(1, stats.getTablesFailed(), "Таблица должна быть помечена как неудавшаяся");
    }

    // ==================== Дополнительные колонки в Target ====================

    /**
     * Условие 5: Дополнительные столбцы в целевой базе
     * Ожидаемое: tablesProcessed++, предупреждение залогировано
     * Проверки:
     * - Дополнительные столбцы в целевой базе допустимы при RELAXED_SCHEMA
     * - Залогировано предупреждение о недостающих столбцах в бэкапе
     */
    @Test
    @DisplayName("должен обработать схему с дополнительными столбцами при RELAXED_SCHEMA")
    void shouldProcessSchemaWithExtraColumnsWhenPolicyIsRelaxed() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1")
        ));
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false),
                createColumnMeta("email", "varchar(255)", true),
                createColumnMeta("created_at", "datetime", true)
        ));
        SchemaMeta schema = createSchema("testdb", List.of(targetTable));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);
        
        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        
        verify(batchImporter, never()).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
    }

    // ==================== Дополнительные колонки в резерве ====================

    /**
     * Условие 6: Дополнительные столбцы в бэкапе
     * Ожидаемое: tablesProcessed++, предупреждение залогировано
     * Проверки:
     * - Дополнительные столбцы в бэкапе допустимы при RELAXED_SCHEMA
     * - Залогировано предупреждение о дополнительных столбцах
     */
    @Test
    @DisplayName("должен обработать схему с дополнительными столбцами в бэкапе при RELAXED_SCHEMA")
    void shouldProcessSchemaWithExtraColumnsInBackupWhenPolicyIsRelaxed() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of(
                Map.of("id", 1L, "username", "user1", "extra_column", "extra_value")
        ));
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta targetTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta schema = createSchema("testdb", List.of(targetTable));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);
        
        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
    }

    // ==================== Опустошение строк в резервной копии ====================

    /**
     * Условие 7: Пустые строки в бэкапе
     * Ожидаемое: tablesProcessed++, проверка схемы прошла успешно
     */
    @Test
    @DisplayName("должен обработать пустую таблицу при отсутствии строк")
    void shouldProcessEmptyTableWhenBackupHasNoRows() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        backupData.put("users", List.of());
        BackupFile backup = createBackupFile(backupData);
        
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        SchemaMeta schema = createSchema("testdb", List.of(usersTable));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(schema);
        
        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Таблица должна быть обработана (проверка схемы прошла успешно)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        
        verify(batchImporter, never()).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
    }

    // ==================== Несколько таблиц ====================

    /**
     * Условие 8: Несколько таблиц с разными результатами
     * Ожидаемое: таблицы обрабатываются/пропускаются в зависимости от наличия
     */
    @Test
    @DisplayName("должен обработать несколько таблиц с разными результатами")
    void shouldProcessMultipleTablesWhenSomeExistAndSomeDoNot() {
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
        
        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed(), "Две таблицы должны быть обработаны (схема валидирована)");
        assertEquals(1, stats.getTablesSkipped(), "Одна таблица должна быть пропущена (не существует)");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        
        verify(batchImporter, never()).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
    }

    // ==================== Импорт запрещён ====================

    /**
     * Условие 9: Импорт не разрешен
     * Ожидаемое: выброс UnsupportedOperationException
     * Проверки:
     * - importTableRows выбрасывает UnsupportedOperationException
     */
    @Test
    @DisplayName("должен выбросить UnsupportedOperationException при попытке импорта")
    void shouldThrowUnsupportedOperationExceptionWhenImportAttempted() {
        // arrange
        JdbcTemplate template = mock(JdbcTemplate.class);
        TableMeta tableMeta = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        List<Map<String, Object>> rows = List.of(
                Map.of("id", 1L, "username", "user1")
        );
        RestorePolicy policy = createRestorePolicy();

        // act & assert
        UnsupportedOperationException ex = assertThrows(UnsupportedOperationException.class, () -> {
            strategy.importTableRows(template, "users", tableMeta, rows, policy, stats);
        });

        assertTrue(ex.getMessage().contains("should not perform real imports"), 
                "Сообщение исключения должно содержать текст о невозможности импорта");
    }

    // ==================== Порядок таблицы из резервного копирования ====================

    /**
     * Условие 10: Порядок таблиц из бэкапа
     * Ожидаемое: таблицы валидируются в порядке backup.tableOrder()
     */
    @Test
    @DisplayName("должен валидировать таблицы в порядке, указанном в backup.tableOrder()")
    void shouldValidateTablesInOrderSpecifiedInBackup() {
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
        
        TableMeta categoriesTable = createTableMeta("categories", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("name", "varchar(255)", false)
        ));
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("categories"))))
                .thenReturn(new SchemaMeta("testdb", List.of(categoriesTable)));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of(usersTable)));
        
        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(2, stats.getTablesProcessed(), "Обе таблицы должны быть обработаны");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
        
        verify(batchImporter, never()).importTableRows(any(), any(), anyList(), anyList(), anyString(), any(), any(), any());
    }

    // ==================== Валидация большой схемы ====================

    /**
     * Условие 11: Валидация большой схемы
     * Ожидаемое: все таблицы успешно валидированы
     */
    @Test
    @DisplayName("должен успешно валидировать большую схему с несколькими таблицами")
    void shouldValidateLargeSchemaSuccessfully() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        
        Map<String, List<Map<String, Object>>> backupData = new HashMap<>();
        List<String> tableNames = List.of("users", "categories", "products", "orders", "order_items");
        for (String tableName : tableNames) {
            backupData.put(tableName, List.of(Map.of("id", 1L, "name", "test")));
        }
        BackupFile backup = createBackupFile(backupData);
        
        for (String tableName : tableNames) {
            when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of(tableName))))
                    .thenReturn(new SchemaMeta("testdb", List.of(createTableMeta(tableName, List.of(
                            createColumnMeta("id", "bigint(20)", false),
                            createColumnMeta("name", "varchar(255)", false)
                    )))));
        }
        
        RestorePolicy policy = createRestorePolicy();

        // act
        strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");

        // assert
        assertEquals(5, stats.getTablesProcessed(), "Все 5 таблиц должны быть обработаны");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены");
        assertEquals(0, stats.getTablesFailed(), "Таблицы не должны завершиться ошибкой");
    }

    // ==================== обработка ошибок с LOG_AND_CONTINUE====================

    /**
     * Условие 12: Обработка ошибки при LOG_AND_CONTINUE
     * Ожидаемое: исключение поймано и залогировано, обработка продолжается
     */
    @Test
    @DisplayName("должен поймать исключение и продолжить обработку при LOG_AND_CONTINUE")
    void shouldCatchExceptionAndContinueWhenErrorPolicyIsLogAndContinue() {
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
        
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("orders"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of(usersTable)));
        
        RestorePolicy policy = new RestorePolicy(
                SchemaPolicy.STRICT_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.LOG_AND_CONTINUE
        );

        // act
        assertDoesNotThrow(() -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        // assert
        assertEquals(1, stats.getTablesProcessed(), "Одна таблица должна быть обработана (users)");
        assertEquals(1, stats.getTablesFailed(), "Одна таблица должна завершиться ошибкой (orders)");
        assertEquals(0, stats.getTablesSkipped(), "Таблицы не должны быть пропущены (обе были попытки)");
    }

    // ==================== Отсутствующая таблица с STRICT_SCHEMA и FAIL_FAST====================

    /**
     * Условие 13: Отсутствующая таблица при STRICT_SCHEMA и FAIL_FAST
     * Ожидаемое: немедленный выброс исключения, обработка останавливается
     */
    @Test
    @DisplayName("должен немедленно выбросить исключение при отсутствии таблицы при FAIL_FAST")
    void shouldThrowExceptionImmediatelyWhenTableIsMissingWithFailFastPolicy() {
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
        
        TableMeta usersTable = createTableMeta("users", List.of(
                createColumnMeta("id", "bigint(20)", false),
                createColumnMeta("username", "varchar(255)", false)
        ));
        
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("orders"))))
                .thenReturn(new SchemaMeta("testdb", List.of()));
        when(mysqlMetadataReader.readSchema(any(), any(), eq(List.of("users"))))
                .thenReturn(new SchemaMeta("testdb", List.of(usersTable)));
        
        RestorePolicy policy = new RestorePolicy(
                SchemaPolicy.STRICT_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.FAIL_FAST
        );

        // act & assert
        RestoreOperationException ex = assertThrows(RestoreOperationException.class, () -> {
            strategy.restore(credentials, backup, "testdb", policy, stats, "backup.zip");
        });

        assertTrue(ex.getMessage().contains("Missing table"), "Исключение должно содержать текст об отсутствии таблицы");
        assertEquals("orders", ex.getTable(), "Исключение должно содержать имя таблицы orders");
    }
}
