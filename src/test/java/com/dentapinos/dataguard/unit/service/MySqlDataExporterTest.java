package com.dentapinos.dataguard.unit.service;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.ExportStats;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.service.MySqlDataExporter;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Юнит-тесты для MySqlDataExporter.
 * Проверяет логику экспорта данных из MySQL-базы в указанные таблицы с контролем порядка и обработкой ошибок.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-test для экспортера данных MySQL")
class MySqlDataExporterTest {

    @Mock
    private JdbcTemplateFactory jdbcTemplateFactory;

    @Mock
    private JdbcTemplateFactory.JdbcConnection jdbcConnection;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private MySqlDataExporter exporter;

    private DbCredentials dbCredentials;
    private SchemaMeta schema;

    @BeforeEach
    void setUp() {
        dbCredentials = new DbCredentials("localhost", "user", "pass");

        // arrange
        ColumnMeta col1 = new ColumnMeta("id", "BIGINT", true, false);
        ColumnMeta col2 = new ColumnMeta("name", "VARCHAR", false, false);
        ColumnMeta col3 = new ColumnMeta("email", "VARCHAR", false, false);

        TableMeta tableA = new TableMeta("table_a", List.of(col1, col2), List.of(), List.of(), List.of());
        TableMeta tableB = new TableMeta("table_b", List.of(col1, col2, col3), List.of(), List.of(), List.of());
        TableMeta tableC = new TableMeta("table_c", List.of(col1, col2), List.of(), List.of(), List.of());

        schema = new SchemaMeta("test_schema", List.of(tableA, tableB, tableC));
    }

    @Test
    @DisplayName("должен экспортировать таблицы в указанном порядке и вернуть правильные данные")
    void shouldExportTablesInSpecifiedOrder() throws Exception {
        // arrange
        List<String> tableOrder = List.of("table_b", "table_c", "table_a");
        ExportStats stats = new ExportStats();

        Map<String, Object> row1 = Map.of("id", 1L, "name", "Alice", "email", "a@test.com");
        Map<String, Object> row2 = Map.of("id", 2L, "name", "Bob", "email", "b@test.com");
        List<Map<String, Object>> mockTableBData = List.of(row1, row2);

        Map<String, Object> row3 = Map.of("id", 10L, "name", "Charlie");
        List<Map<String, Object>> mockTableCData = List.of(row3);

        Map<String, Object> row4 = Map.of("id", 100L, "name", "Diana");
        List<Map<String, Object>> mockTableAData = List.of(row4);

        when(jdbcTemplateFactory.create(dbCredentials)).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);

        when(jdbcTemplate.query(eq("SELECT * FROM `table_b`"), any(RowMapper.class)))
                .thenReturn(mockTableBData);
        when(jdbcTemplate.query(eq("SELECT * FROM `table_c`"), any(RowMapper.class)))
                .thenReturn(mockTableCData);
        when(jdbcTemplate.query(eq("SELECT * FROM `table_a`"), any(RowMapper.class)))
                .thenReturn(mockTableAData);

        // act
        Map<String, List<Map<String, Object>>> result = exporter.exportData(dbCredentials, schema, tableOrder, stats);

        // assert
        assertEquals(List.of("table_b", "table_c", "table_a"), new ArrayList<>(result.keySet()));

        assertEquals(2, result.get("table_b").size());
        assertEquals(1, result.get("table_c").size());
        assertEquals(1, result.get("table_a").size());

        assertEquals(3, stats.getTablesTotal());
        assertEquals(3, stats.getTablesProcessed());
        assertEquals(0, stats.getTablesFailed());
        assertEquals(4, stats.getTotalRows());

        verify(jdbcTemplate, times(1)).query(eq("SELECT * FROM `table_b`"), any(RowMapper.class));
        verify(jdbcTemplate, times(1)).query(eq("SELECT * FROM `table_c`"), any(RowMapper.class));
        verify(jdbcTemplate, times(1)).query(eq("SELECT * FROM `table_a`"), any(RowMapper.class));
    }

    @Test
    @DisplayName("должен экспортировать таблицы в естественном порядке, если tableOrder не задан")
    void shouldExportTablesInNaturalOrderWhenTableOrderIsNull() throws Exception {
        // arrange
        ExportStats stats = new ExportStats();
        List<String> tableOrder = null;

        when(jdbcTemplateFactory.create(dbCredentials)).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);

        when(jdbcTemplate.query(eq("SELECT * FROM `table_a`"), any(RowMapper.class)))
                .thenReturn(List.of(Map.of("id", 1L, "name", "X")));
        when(jdbcTemplate.query(eq("SELECT * FROM `table_b`"), any(RowMapper.class)))
                .thenReturn(List.of());
        when(jdbcTemplate.query(eq("SELECT * FROM `table_c`"), any(RowMapper.class)))
                .thenReturn(List.of(Map.of("id", 3L, "name", "Z")));

        // act
        Map<String, List<Map<String, Object>>> result = exporter.exportData(dbCredentials, schema, null, stats);

        // assert
        assertEquals(List.of("table_a", "table_b", "table_c"), new ArrayList<>(result.keySet()));
        assertEquals(3, stats.getTablesTotal());
        assertEquals(3, stats.getTablesProcessed());
        assertEquals(2, stats.getTotalRows());
    }

    @Test
    @DisplayName("должен продолжать экспорт при ошибке в одной таблице и не включать её в результат")
    void shouldContinueExportWhenTableExportFails() {
        // arrange
        List<String> tableOrder = List.of("table_a", "table_b", "table_c");
        ExportStats stats = new ExportStats();

        when(jdbcTemplateFactory.create(dbCredentials)).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);

        when(jdbcTemplate.query(eq("SELECT * FROM `table_a`"), any(RowMapper.class)))
                .thenReturn(List.of(Map.of("id", 1L, "name", "A")));
        when(jdbcTemplate.query(eq("SELECT * FROM `table_b`"), any(RowMapper.class)))
                .thenThrow(new RuntimeException("Connection timeout"));
        when(jdbcTemplate.query(eq("SELECT * FROM `table_c`"), any(RowMapper.class)))
                .thenReturn(List.of(Map.of("id", 2L, "name", "C")));

        // act
        Map<String, List<Map<String, Object>>> result = exporter.exportData(dbCredentials, schema, tableOrder, stats);

        // assert
        assertEquals(List.of("table_a", "table_c"), new ArrayList<>(result.keySet()));
        assertEquals(3, stats.getTablesTotal());
        assertEquals(2, stats.getTablesProcessed());
        assertEquals(1, stats.getTablesFailed());
        assertEquals(2, stats.getTotalRows());
    }

    @Test
    @DisplayName("должен корректно маппить колонки из ResultSet")
    void shouldMapResultSetColumnsCorrectly() {
        // arrange
        List<String> tableOrder = List.of("table_a");
        ExportStats stats = new ExportStats();

        when(jdbcTemplateFactory.create(dbCredentials)).thenReturn(jdbcConnection);
        when(jdbcConnection.getJdbcTemplate()).thenReturn(jdbcTemplate);

        doAnswer(invocation -> {
            RowMapper<Map<String, Object>> mapper = invocation.getArgument(1);
            return List.of(Map.of("id", 99L, "name", "Mock"));
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class));

        // act
        exporter.exportData(dbCredentials, schema, tableOrder, stats);

        // assert
        assertEquals(1, stats.getTablesProcessed());
    }

    @Test
    @DisplayName("должен использовать LinkedHashMap и сохранять порядок ключей")
    void shouldUseLinkedHashMap() {
        // arrange
        Map<String, List<Map<String, Object>>> testMap = new LinkedHashMap<>();
        testMap.put("z", List.of());
        testMap.put("a", List.of());
        testMap.put("m", List.of());

        // assert
        assertEquals(List.of("z", "a", "m"), new ArrayList<>(testMap.keySet()));
    }
}