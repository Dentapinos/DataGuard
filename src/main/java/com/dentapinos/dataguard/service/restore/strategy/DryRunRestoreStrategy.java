package com.dentapinos.dataguard.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.RestoreStats;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.policy.ErrorPolicy;
import com.dentapinos.dataguard.enums.policy.ForeignKeyPolicy;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.enums.policy.SchemaPolicy;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.service.restore.BatchImporter;
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Стратегия DRY_RUN:
 * - Не выполняет никаких записей в БД;
 * - Проводит все проверки на наличие таблиц/колонок;
 * - Эмулирует статистику, как если бы данные были вставлены/обновлены/пропущены.
 * - Всегда использует RELAXED_SCHEMA, SKIP_VIOLATIONS, LOG_AND_CONTINUE политики.
 */
@Slf4j
@Service
public class DryRunRestoreStrategy extends AbstractRestoreStrategy {

    public DryRunRestoreStrategy(JdbcTemplateFactory jdbcTemplateFactory, BatchImporter batchImporter, DatabaseConfigurator databaseConfigurator, DatabaseMetadataReader mysqlMetadataReader) {
        super(jdbcTemplateFactory, batchImporter, databaseConfigurator, mysqlMetadataReader);
    }

    @Override
    protected void performRestore(JdbcTemplate template,
                                  DbCredentials dbCredentials,
                                  BackupFile backup,
                                  String targetDatabase,
                                  Map<String, List<Map<String, Object>>> data,
                                  RestorePolicy policy,
                                  RestoreStats stats,
                                  List<String> tableOrder) {

        log.info("Running DRY_RUN restore: no data will be written to the database");

        // В DRY_RUN всегда используем фиксированную политику: RELAXED_SCHEMA, SKIP_VIOLATIONS, LOG_AND_CONTINUE
        RestorePolicy effectivePolicy = new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                policy.rowConflictPolicy(),
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.LOG_AND_CONTINUE
        );

        // Используем порядок из бэкапа, если он задан; иначе - порядок из data.keySet()
        List<String> tablesToProcess = tableOrder != null && !tableOrder.isEmpty()
                ? tableOrder.stream().filter(data::containsKey).toList()
                : new ArrayList<>(data.keySet());

        log.info("[DRY_RUN] Обработка таблиц в порядке: {}", tablesToProcess);

        for (String tableName : tablesToProcess) {
            try {
                if (targetDatabase == null || targetDatabase.isEmpty()) {
                    log.warn("[DRY_RUN] Target database is empty, skipping table {}", tableName);
                    stats.setTablesSkipped(stats.getTablesSkipped() + 1);
                    continue;
                }

                Optional<TableMeta> metaOpt = getTableMetaOneRow(dbCredentials, targetDatabase, tableName);

                if (metaOpt.isEmpty()) {
                    log.warn("[DRY_RUN] Table {} from backup not found in target DB; data would be skipped", tableName);
                    stats.setTablesSkipped(stats.getTablesSkipped() + 1);
                    // В DRY_RUN пропущенные таблицы не считаем как обработанные - они были пропущены
                    continue;
                }

                TableMeta targetMeta = metaOpt.get();
                List<Map<String, Object>> rows = data.get(tableName);

                simulateTableRestore(tableName, targetMeta, rows, effectivePolicy, stats);
            } catch (Exception e) {
                // DRY_RUN doesn't throw exceptions - all errors are caught and logged
                log.error("[DRY_RUN] Unexpected error during restore simulation for table {}", tableName, e);
                stats.setTablesFailed(stats.getTablesFailed() + 1);
                stats.setTablesSkipped(stats.getTablesSkipped() + 1);
            }
        }
    }

    /**
     * Эмуляция вставки/обновления строк для одной таблицы.
     * Здесь нет реальных SQL-вызовов, только проверки и статистика.
     * Использует RELAXED_SCHEMA - не выбрасывает исключения при расхождении схем.
     */
    private void simulateTableRestore(String tableName,
                                      TableMeta targetMeta,
                                      List<Map<String, Object>> rows,
                                      RestorePolicy policy,
                                      RestoreStats stats) {

        if (rows == null || rows.isEmpty()) {
            log.info("[DRY_RUN] Table {}: no rows in backup", tableName);
            stats.setTablesProcessed(stats.getTablesProcessed() + 1);
            return;
        }

        // В RELAXED_SCHEMA не выбрасываем исключение при отсутствии колонки - просто логируем
        List<String> targetColumns = targetMeta.columns().stream()
                .map(ColumnMeta::name)
                .toList();

        Map<String, Object> firstRow = rows.get(0);

        // Для RELAXED_SCHEMA: проверяем, есть ли хотя бы одна колонка из targetColumns в бэкапе
        boolean hasCommonColumns = targetColumns.stream().anyMatch(firstRow::containsKey);

        if (!hasCommonColumns) {
            log.warn("[DRY_RUN] Table {}: no common columns between backup and target schema, skipping table",
                    tableName);
            stats.setTablesSkipped(stats.getTablesSkipped() + 1);
            return;
        }

        // Для RELAXED_SCHEMA: собираем только колонки, которые есть и в target, и в backup
        List<String> insertColumns = new ArrayList<>();
        for (String col : targetColumns) {
            if (firstRow.containsKey(col)) {
                insertColumns.add(col);
            } else {
                log.debug("[DRY_RUN] Table {}: column {} missing in backup, but allowed in RELAXED_SCHEMA",
                        tableName, col);
            }
        }

        if (insertColumns.isEmpty()) {
            log.warn("[DRY_RUN] Table {}: no valid columns after filtering, skipping table", tableName);
            stats.setTablesSkipped(stats.getTablesSkipped() + 1);
            return;
        }

        // Эмуляция статистики по конфликтной политике (для DRY_RUN детерминированная логика)
        RowConflictPolicy rowPolicy = policy.rowConflictPolicy();

        // Детерминированная эмуляция статистики
        switch (rowPolicy) {
            case OVERWRITE_ON_CONFLICT -> {
                // Все строки будут обработаны - для DRY_RUN считаем как INSERT (реально -部分 UPDATE)
                stats.setRowsInserted(stats.getRowsInserted() + rows.size());
                inc(stats.getRowsPerTableInserted(), tableName, rows.size());
            }
            case SKIP_ON_CONFLICT -> {
                // В DRY_RUN считаем все строки как INSERT (конфликты не определяются)
                stats.setRowsInserted(stats.getRowsInserted() + rows.size());
                inc(stats.getRowsPerTableInserted(), tableName, rows.size());
            }
            case FAIL_ON_CONFLICT -> {
                // В DRY_RUN просто логируем предупреждение, но считаем все строки как INSERT
                log.warn("[DRY_RUN] Table {}: FAIL_ON_CONFLICT policy - rows would fail during real restore if conflicts exist",
                        tableName);
                stats.setRowsInserted(stats.getRowsInserted() + rows.size());
                inc(stats.getRowsPerTableInserted(), tableName, rows.size());
            }
        }

        stats.setTablesProcessed(stats.getTablesProcessed() + 1);

        log.info("[DRY_RUN] Table {}: rowsInserted={}, tablesProcessed={}",
                tableName,
                stats.getRowsPerTableInserted().getOrDefault(tableName, 0L),
                stats.getTablesProcessed());
    }

    @Override
    public void importTableRows(JdbcTemplate jdbcTemplate,
                                   String tableName,
                                   TableMeta tableMeta,
                                   List<Map<String, Object>> rows,
                                   RestorePolicy policy,
                                   RestoreStats stats) {
        // В DRY_RUN мы не должны сюда попадать; вся логика – в simulateTableRestore().
        throw new UnsupportedOperationException("DRY_RUN strategy should not perform real imports");
    }

    @Override
    protected String getSql(String tableName, List<String> insertColumns, RowConflictPolicy rowConflictPolicy) {
        // В DRY_RUN SQL не используется - возвращаем пустую строку
        return "";
    }

    @Override
    public void handleBatchResult(int count,
                                     String tableName,
                                     RestorePolicy policy,
                                     RestoreStats stats) {
        // В DRY_RUN не делаем ничего - реальные батчи не используются
        log.debug("[DRY_RUN] handleBatchResult not supported - DRY_RUN does not perform real batch operations");
    }

    private void inc(Map<String, Long> map, String key, long value) {
        map.merge(key, value, Long::sum);
    }
}
