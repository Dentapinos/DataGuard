package com.dentapinos.dataguard.unit.service.engine;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.ExportStats;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.BackupStatus;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.dentapinos.dataguard.report.BackupReport;
import com.dentapinos.dataguard.report.BackupSummary;
import com.dentapinos.dataguard.service.DatabaseDataExporter;
import com.dentapinos.dataguard.service.engine.MySqlBackupEngine;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Юнит-тесты для MySqlBackupEngine.
 * Проверяет создание резервных копий MySQL баз данных, включая чтение метаданных, экспорт данных
 * и формирование отчетов с различными статусами выполнения.
 */
@DisplayName("Unit-test для MySQL движка резервного копирования")
class MySqlBackupEngineTest {

    @Mock
    private DatabaseMetadataReader metadataReader;

    @Mock
    private DatabaseDataExporter dataExporter;

    private MySqlBackupEngine engine;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() {
        // arrange
        closeable = MockitoAnnotations.openMocks(this);
        engine = new MySqlBackupEngine(metadataReader, dataExporter);
    }

    @AfterEach
    void tearDown() throws Exception {
        closeable.close();
    }

    // ==================== Вспомогательные методы ====================

    /**
     * Создает учетные данные для подключения к тестовой базе данных.
     */
    private DbCredentials createDbCredentials() {
        return new DbCredentials(
                "jdbc:mysql://localhost:3306/testdb",
                "testuser",
                "testpass"
        );
    }

    /**
     * Создает метаданные столбца для тестов.
     */
    private ColumnMeta col(String name, String type, boolean nullable) {
        return new ColumnMeta(name, type, nullable, false);
    }

    /**
     * Создает метаданные таблицы для тестов.
     */
    private TableMeta table(String name, ColumnMeta... cols) {
        return new TableMeta(name, Arrays.asList(cols), List.of(), List.of(), List.of());
    }

    /**
     * Создает метаданные схемы для тестов.
     */
    private SchemaMeta schema(String database, TableMeta... tables) {
        return new SchemaMeta(database, Arrays.asList(tables));
    }


    // ==================== Тест 1: Успешный случай - все таблицы успешно экспортированы ====================

    @Test
    @DisplayName("должен успешно создать резервную копию, когда все таблицы экспортированы")
    void shouldSuccessfullyCreateBackupWhenAllTablesExported() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of("users", "orders");

        TableMeta usersTable = table("users", col("id", "bigint(20)", false), col("name", "varchar(255)", false));
        TableMeta ordersTable = table("orders", col("id", "bigint(20)", false), col("amount", "double", true));

        SchemaMeta schema = schema(database, usersTable, ordersTable);

        Map<String, List<Map<String, Object>>> exportData = Map.of(
                "users", List.of(
                        Map.of("id", 1L, "name", "User 1"),
                        Map.of("id", 2L, "name", "User 2")
                ),
                "orders", List.of(
                        Map.of("id", 1L, "amount", 100.0)
                )
        );

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExportStats stats = invocation.getArgument(3);
            stats.setTablesTotal(2);
            stats.setTablesProcessed(2);
            stats.setTotalRows(3);
            stats.getRowsPerTable().put("users", 2L);
            stats.getRowsPerTable().put("orders", 1L);
            return exportData;
        });

        // act
        BackupEnvelope envelope = engine.backup(credentials, database, tables);

        // assert
        assertNotNull(envelope);
        assertNotNull(envelope.report());
        assertNotNull(envelope.backup());

        BackupReport report = envelope.report();
        assertEquals(database, report.database());
        assertEquals("mysql", report.engine());
        assertEquals(BackupStatus.SUCCESS, report.status());

        BackupSummary summary = report.summary();
        assertEquals(2, summary.tablesTotal());
        assertEquals(2, summary.tablesProcessed());
        assertEquals(0, summary.tablesFailed());
        assertEquals(0, summary.tablesSkipped());
        assertEquals(3, summary.totalRows());
        assertEquals(2, summary.rowsPerTable().size());
        assertEquals(2L, summary.rowsPerTable().get("users"));
        assertEquals(1L, summary.rowsPerTable().get("orders"));

        BackupFile backup = envelope.backup();
        assertEquals(database, backup.database());
        assertEquals("mysql", backup.engine());
        assertEquals(schema, backup.schema());
        assertEquals(exportData, backup.data());
        assertEquals(tables, backup.tableOrder());

        // Были проведены пробные проверки
        verify(metadataReader).readSchema(eq(credentials), eq(database), eq(tables));
        verify(dataExporter).exportData(eq(credentials), eq(schema), eq(tables), any(ExportStats.class));
        verifyNoMoreInteractions(metadataReader, dataExporter);
    }

    // ==================== Тест 2: Частичный успех - некоторые таблицы не удалось экспортировать ====================

    @Test
    @DisplayName("должен создать резервную копию с предупреждениями, когда некоторые таблицы не удалось экспортировать")
    void shouldCreateBackupWithWarningsWhenSomeTablesFailed() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of("users", "orders", "products");

        TableMeta usersTable = table("users", col("id", "bigint(20)", false), col("name", "varchar(255)", false));
        TableMeta ordersTable = table("orders", col("id", "bigint(20)", false), col("amount", "double", true));

        SchemaMeta schema = schema(database, usersTable, ordersTable);

        Map<String, List<Map<String, Object>>> exportData = Map.of(
                "users", List.of(
                        Map.of("id", 1L, "name", "User 1")
                )
        );

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExportStats stats = invocation.getArgument(3);
            stats.setTablesTotal(3);
            stats.setTablesProcessed(1);
            stats.setTablesFailed(1);
            stats.setTablesSkipped(1);
            stats.setTotalRows(1);
            stats.getRowsPerTable().put("users", 1L);
            return exportData;
        });

        // act
        BackupEnvelope envelope = engine.backup(credentials, database, tables);

        // assert
        assertNotNull(envelope);
        BackupReport report = envelope.report();
        assertEquals(BackupStatus.COMPLETED_WITH_WARNINGS, report.status());

        BackupSummary summary = report.summary();
        assertEquals(3, summary.tablesTotal());
        assertEquals(1, summary.tablesProcessed());
        assertEquals(1, summary.tablesFailed());
        assertEquals(1, summary.tablesSkipped());
        assertEquals(1, summary.totalRows());

        // Проверка, что порядок таблицы выводится из схемы
        verify(metadataReader).readSchema(eq(credentials), eq(database), eq(tables));
        verify(dataExporter).exportData(eq(credentials), eq(schema), eq(List.of("users", "orders")), any(ExportStats.class));
        verifyNoMoreInteractions(metadataReader, dataExporter);
    }

    // ==================== Тест 3: Полный сбой - ни одна таблица не была обработана ====================

    @Test
    @DisplayName("должен создать резервную копию со статусом FAILED, когда ни одна таблица не была обработана")
    void shouldCreateFailedBackupWhenNoTablesProcessed() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of("users");

        SchemaMeta schema = schema(database);

        Map<String, List<Map<String, Object>>> exportData = Map.of();

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExportStats stats = invocation.getArgument(3);
            stats.setTablesTotal(1);
            stats.setTablesProcessed(0);
            stats.setTablesFailed(1);
            stats.setTablesSkipped(0);
            stats.setTotalRows(0);
            return exportData;
        });

        // act
        BackupEnvelope envelope = engine.backup(credentials, database, tables);

        // assert
        assertNotNull(envelope);
        BackupReport report = envelope.report();
        assertEquals(BackupStatus.FAILED, report.status());

        BackupSummary summary = report.summary();
        assertEquals(1, summary.tablesTotal());
        assertEquals(0, summary.tablesProcessed());
        assertEquals(1, summary.tablesFailed());
        assertEquals(0, summary.tablesSkipped());
        assertEquals(0, summary.totalRows());

        // Порядок проверки таблицы выводится из схемы (пустой в данном случае)
        verify(metadataReader).readSchema(eq(credentials), eq(database), eq(tables));
        verify(dataExporter).exportData(eq(credentials), eq(schema), eq(List.of()), any(ExportStats.class));
        verifyNoMoreInteractions(metadataReader, dataExporter);
    }

    // ==================== Тест 4: Пустой список таблиц ====================

    @Test
    @DisplayName("должен успешно создать резервную копию, когда список таблиц пустой")
    void shouldSuccessfullyCreateBackupWhenTablesListIsEmpty() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of();

        SchemaMeta schema = schema(database);

        Map<String, List<Map<String, Object>>> exportData = Map.of();

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExportStats stats = invocation.getArgument(3);
            stats.setTablesTotal(0);
            stats.setTablesProcessed(0);
            stats.setTablesFailed(0);
            stats.setTablesSkipped(0);
            stats.setTotalRows(0);
            return exportData;
        });

        // act
        BackupEnvelope envelope = engine.backup(credentials, database, tables);

        // assert
        assertNotNull(envelope);
        BackupReport report = envelope.report();
        assertEquals(BackupStatus.SUCCESS, report.status());

        verify(metadataReader).readSchema(eq(credentials), eq(database), eq(tables));
        verify(dataExporter).exportData(eq(credentials), eq(schema), eq(tables), any(ExportStats.class));
        verifyNoMoreInteractions(metadataReader, dataExporter);
    }

    // ==================== Тест 5: Одна таблица с несколькими строками ====================

    @Test
    @DisplayName("должен успешно создать резервную копию, когда есть одна таблица с несколькими строками")
    void shouldSuccessfullyCreateBackupWithSingleTableMultipleRows() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of("users");

        TableMeta usersTable = table("users",
                col("id", "bigint(20)", false),
                col("name", "varchar(255)", false),
                col("email", "varchar(255)", true)
        );

        SchemaMeta schema = schema(database, usersTable);

        Map<String, List<Map<String, Object>>> exportData = Map.of(
                "users", List.of(
                        Map.of("id", 1L, "name", "User 1", "email", "user1@test.com"),
                        Map.of("id", 2L, "name", "User 2", "email", "user2@test.com"),
                        Map.of("id", 3L, "name", "User 3", "email", "user3@test.com"),
                        Map.of("id", 4L, "name", "User 4", "email", "user4@test.com"),
                        Map.of("id", 5L, "name", "User 5", "email", "user5@test.com")
                )
        );

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExportStats stats = invocation.getArgument(3);
            stats.setTablesTotal(1);
            stats.setTablesProcessed(1);
            stats.setTotalRows(5);
            stats.getRowsPerTable().put("users", 5L);
            return exportData;
        });

        // act
        BackupEnvelope envelope = engine.backup(credentials, database, tables);

        // assert
        assertNotNull(envelope);
        BackupReport report = envelope.report();
        BackupSummary summary = report.summary();
        assertEquals(BackupStatus.SUCCESS, report.status());
        assertEquals(1, summary.tablesTotal());
        assertEquals(1, summary.tablesProcessed());
        assertEquals(0, summary.tablesFailed());
        assertEquals(5, summary.totalRows());
        assertEquals(5L, summary.rowsPerTable().get("users"));

        verify(metadataReader).readSchema(eq(credentials), eq(database), eq(tables));
        verify(dataExporter).exportData(eq(credentials), eq(schema), eq(tables), any(ExportStats.class));
        verifyNoMoreInteractions(metadataReader, dataExporter);
    }

    // ==================== Тест 6: Проверка порядка таблиц передаваемого в экспортер ====================

    @Test
    @DisplayName("должен передавать порядок таблиц из схемы в экспортер")
    void shouldPassTableOrderFromSchemaToExporter() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of("orders", "users");

        TableMeta ordersTable = table("orders", col("id", "bigint(20)", false));
        TableMeta usersTable = table("users", col("id", "bigint(20)", false));

        SchemaMeta schema = schema(database, ordersTable, usersTable);

        Map<String, List<Map<String, Object>>> exportData = Map.of(
                "orders", List.of(),
                "users", List.of()
        );

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExportStats stats = invocation.getArgument(3);
            stats.setTablesTotal(2);
            stats.setTablesProcessed(2);
            stats.setTotalRows(0);
            return exportData;
        });

        // act
        engine.backup(credentials, database, tables);

        // assert - Проверка правильного порядка в таблице
        verify(dataExporter).exportData(
                eq(credentials),
                eq(schema),
                eq(List.of("orders", "users")), //Порядок таблицы по схеме
                any(ExportStats.class)
        );

        verify(metadataReader).readSchema(eq(credentials), eq(database), eq(tables));
        verifyNoMoreInteractions(metadataReader, dataExporter);
    }

    // ==================== Тест 7: Отчет содержит правильные временные метки ====================

    @Test
    @DisplayName("должен содержать в отчете правильные временные метки")
    void shouldContainCorrectTimestampsInReport() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of("users");

        TableMeta usersTable = table("users", col("id", "bigint(20)", false));

        SchemaMeta schema = schema(database, usersTable);

        Map<String, List<Map<String, Object>>> exportData = Map.of();

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExportStats stats = invocation.getArgument(3);
            stats.setTablesTotal(1);
            stats.setTablesProcessed(1);
            stats.setTotalRows(0);
            return exportData;
        });

        Instant beforeBackup = Instant.now();
        
        // act
        BackupEnvelope envelope = engine.backup(credentials, database, tables);

        Instant afterBackup = Instant.now();

        // assert
        BackupReport report = envelope.report();
        assertNotNull(report.startedAt());
        System.out.println("->>>>>>>>>" + report.startedAt());
        assertNotNull(report.finishedAt());
        System.out.println("->>>>>>>>>" + report.finishedAt());
        
        // Проверка, что временные метки действительны и находятся в ожидаемом диапазоне
        // startedAt должен быть >= beforeBackup и <= afterBackup
        // finishedAt должен быть >= startedAt и <= afterBackup
        assertTrue(report.startedAt().isBefore(afterBackup.plusSeconds(1)), 
                "startedAt должен быть перед afterBackup с небольшим допуском");
        assertTrue(report.startedAt().isAfter(beforeBackup.minusSeconds(1)), 
                "startedAt должен быть после beforeBackup с небольшим допуском");
        assertTrue(
                report.finishedAt().equals(report.startedAt()) ||
                        report.finishedAt().isAfter(report.startedAt()),
                "finishedAt должен быть равен или после startedAt"
        );
        assertTrue(report.finishedAt().isBefore(afterBackup.plusSeconds(1)), 
                "finishedAt должен быть перед afterBackup с небольшим допуском");
    }

    // ==================== Тест 8: Резервная копия содержит правильные данные ====================

    @Test
    @DisplayName("должна содержать в резервной копии правильные данные")
    void shouldContainCorrectDataInBackupFile() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of("users");

        TableMeta usersTable = table("users", col("id", "bigint(20)", false));
        SchemaMeta schema = schema(database, usersTable);

        Map<String, List<Map<String, Object>>> exportData = Map.of(
                "users", List.of(Map.of("id", 1L))
        );

        ExportStats stats = new ExportStats();
        stats.setTablesTotal(1);
        stats.setTablesProcessed(1);
        stats.setTotalRows(1);

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenReturn(exportData);

        // act
        BackupEnvelope envelope = engine.backup(credentials, database, tables);

        // assert
        BackupFile backup = envelope.backup();
        assertEquals(database, backup.database());
        assertEquals("mysql", backup.engine());
        assertEquals(schema, backup.schema());
        assertEquals(exportData, backup.data());
        assertEquals(tables, backup.tableOrder());
    }

    // ==================== Тест 9: Несколько таблиц с разным успехом ====================

    @Test
    @DisplayName("должен создать резервную копию с предупреждениями, когда часть таблиц успешна, а часть нет")
    void shouldCreateBackupWithWarningsWhenMultipleTablesMixedSuccess() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of("users", "orders", "products", "categories");

        TableMeta usersTable = table("users", col("id", "bigint(20)", false));
        TableMeta ordersTable = table("orders", col("id", "bigint(20)", false));

        SchemaMeta schema = schema(database, usersTable, ordersTable);

        Map<String, List<Map<String, Object>>> exportData = Map.of(
                "users", List.of(Map.of("id", 1L)),
                "orders", List.of(Map.of("id", 1L))
        );

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExportStats stats = invocation.getArgument(3);
            stats.setTablesTotal(4);
            stats.setTablesProcessed(2);
            stats.setTablesFailed(1);
            stats.setTablesSkipped(1);
            stats.setTotalRows(2);
            stats.getRowsPerTable().put("users", 1L);
            stats.getRowsPerTable().put("orders", 1L);
            return exportData;
        });

        // act
        BackupEnvelope envelope = engine.backup(credentials, database, tables);

        // assert
        assertNotNull(envelope);
        BackupReport report = envelope.report();
        assertEquals(BackupStatus.COMPLETED_WITH_WARNINGS, report.status());

        BackupSummary summary = report.summary();
        assertEquals(4, summary.tablesTotal());
        assertEquals(2, summary.tablesProcessed());
        assertEquals(1, summary.tablesFailed());
        assertEquals(1, summary.tablesSkipped());
        assertEquals(2, summary.totalRows());

        // Проверяем, что порядок таблицы выводится из схемы
        verify(metadataReader).readSchema(eq(credentials), eq(database), eq(tables));
        verify(dataExporter).exportData(eq(credentials), eq(schema), eq(List.of("users", "orders")), any(ExportStats.class));
        verifyNoMoreInteractions(metadataReader, dataExporter);
    }

    // ==================== Тест 10: Пустая карта данных - все таблицы неуспешны ====================

    @Test
    @DisplayName("должен создать резервную копию со статусом FAILED, когда карта данных пустая (все таблицы неуспешны)")
    void shouldCreateFailedBackupWhenDataMapIsEmptyAllFailed() {
        // arrange
        DbCredentials credentials = createDbCredentials();
        String database = "testdb";
        List<String> tables = List.of("users", "orders");

        TableMeta usersTable = table("users", col("id", "bigint(20)", false));
        TableMeta ordersTable = table("orders", col("id", "bigint(20)", false));

        SchemaMeta schema = schema(database, usersTable, ordersTable);

        Map<String, List<Map<String, Object>>> exportData = Map.of();

        when(metadataReader.readSchema(any(), any(), any())).thenReturn(schema);
        when(dataExporter.exportData(any(), any(), any(), any())).thenAnswer(invocation -> {
            ExportStats stats = invocation.getArgument(3);
            stats.setTablesTotal(2);
            stats.setTablesProcessed(0);
            stats.setTablesFailed(2);
            stats.setTablesSkipped(0);
            stats.setTotalRows(0);
            return exportData;
        });

        // act
        BackupEnvelope envelope = engine.backup(credentials, database, tables);

        // assert
        assertNotNull(envelope);
        BackupReport report = envelope.report();
        assertEquals(BackupStatus.FAILED, report.status());

        BackupSummary summary = report.summary();
        assertEquals(2, summary.tablesTotal());
        assertEquals(0, summary.tablesProcessed());
        assertEquals(2, summary.tablesFailed());
        assertEquals(0, summary.tablesSkipped());
        assertEquals(0, summary.totalRows());

        //Проверяем, что порядок таблицы выводится из схемы
        verify(metadataReader).readSchema(eq(credentials), eq(database), eq(tables));
        verify(dataExporter).exportData(eq(credentials), eq(schema), eq(List.of("users", "orders")), any(ExportStats.class));
        verifyNoMoreInteractions(metadataReader, dataExporter);
    }
}
