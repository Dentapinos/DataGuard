package com.dentapinos.dataguard.service;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.ExportStats;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class MySqlDataExporter implements DatabaseDataExporter {
    private final JdbcTemplateFactory jdbcTemplateFactory;

    @Override
    public Map<String, List<Map<String, Object>>> exportData(
            DbCredentials dbCredentials,
            SchemaMeta schema,
            List<String> tableOrder,
            ExportStats stats
    ) {
        try (JdbcTemplateFactory.JdbcConnection conn = jdbcTemplateFactory.create(dbCredentials)) {
            JdbcTemplate jdbcTemplate = conn.getJdbcTemplate();
            Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();

            stats.setTablesTotal(schema.tables().size());

            // Используем указанный порядок таблиц, если он задан
            List<TableMeta> tablesToExport = tableOrder != null && !tableOrder.isEmpty()
                    ? schema.tables().stream()
                            .filter(t -> tableOrder.contains(t.name()))
                            .sorted((t1, t2) -> {
                                int idx1 = tableOrder.indexOf(t1.name());
                                int idx2 = tableOrder.indexOf(t2.name());
                                return Integer.compare(idx1, idx2);
                            })
                            .toList()
                    : schema.tables();

            for (TableMeta table : tablesToExport) {
                String tableName = table.name();
                log.debug("[EXPORT] Экспорт таблицы {}: columns={}, rowCounts={}", tableName, table.columns().size(), "-");
                String sql = "SELECT * FROM `" + tableName + "`";
                log.debug("[EXPORT] Выполняется SQL: {}", sql);

                try {
                    List<Map<String, Object>> rows = jdbcTemplate.query(sql, (rs, rowNum) -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (ColumnMeta col : table.columns()) {
                            Object value = rs.getObject(col.name());
                            row.put(col.name(), value);
                        }
                        return row;
                    });

                    result.put(tableName, rows);

                    long count = rows.size();
                    stats.getRowsPerTable().put(tableName, count);
                    stats.setTotalRows(stats.getTotalRows() + count);
                    stats.setTablesProcessed(stats.getTablesProcessed() + 1);
                    log.debug("[EXPORT] Таблица {}: экспортировано строк={}, результат.put() успешен", tableName, count);
                    log.debug("[EXPORT] Данные для таблицы {}: {}", tableName, rows);
                } catch (Exception e) {
                    // логируем ошибку по таблице и идём дальше
                    log.error("[EXPORT] Ошибка экспорта таблицы {}", tableName, e);
                    stats.setTablesFailed(stats.getTablesFailed() + 1);
                    // таблицу в result не кладем
                }
            }

            return result;
        }
    }
}