package com.dentapinos.dataguard.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.RestoreStats;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.policy.ErrorPolicy;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.enums.policy.SchemaPolicy;
import com.dentapinos.dataguard.exception.RestoreOperationException;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.service.restore.BatchImporter;
import com.dentapinos.dataguard.service.restore.SqlBuilder;
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import com.dentapinos.dataguard.service.restore.sqlbuilder.SqlBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Стратегия UPSERT_ALL:
 * - Для всех строк делает "upsert" (INSERT ... ON DUPLICATE KEY UPDATE);
 * - Не удаляет существующие данные;
 * - Относится к отсутствующим таблицам как в SAFE_MERGE: может пропустить или упасть
 *   в зависимости от строгости режима.
 */
@Slf4j
@Component
public class UpsertAllRestoreStrategy extends AbstractRestoreStrategy {

    private final SqlBuilderFactory sqlBuilderFactory;

    public UpsertAllRestoreStrategy(JdbcTemplateFactory jdbcTemplateFactory,
                                    BatchImporter batchImporter,
                                    DatabaseConfigurator databaseConfigurator,
                                    DatabaseMetadataReader mysqlMetadataReader, SqlBuilderFactory sqlBuilderFactory) {
        super(jdbcTemplateFactory, batchImporter, databaseConfigurator, mysqlMetadataReader);
        this.sqlBuilderFactory = sqlBuilderFactory;
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

        // Используем порядок из бэкапа, если он задан; иначе - порядок из data.keySet()
        List<String> tablesToProcess = backup.tableOrder() != null && !backup.tableOrder().isEmpty()
                ? backup.tableOrder().stream().filter(data::containsKey).toList()
                : new ArrayList<>(data.keySet());

        log.info("[UPSERT_ALL] Обработка таблиц в порядке: {}", tablesToProcess);

        for (String tableName : tablesToProcess) {
            try {
                Optional<TableMeta> metaOpt = getTableMetaOneRow(dbCredentials, targetDatabase, tableName);

                if (metaOpt.isEmpty()) {
                    if (policy.schemaPolicy() == SchemaPolicy.STRICT_SCHEMA) {
                        throw new RestoreOperationException(
                                "Missing table in target DB during UPSERT_ALL strict schema mode",
                                tableName,
                                null,
                                0,
                                "Target table not found",
                                null
                        );
                    } else {
                        log.warn("Table {} from backup not found in target DB; data skipped (UPSERT_ALL, RELAXED)",
                                tableName);
                        stats.setTablesSkipped(stats.getTablesSkipped() + 1);
                        continue;
                    }
                }

                TableMeta targetMeta = metaOpt.get();
                List<Map<String, Object>> rows = data.get(tableName);

                importTableRowsWithLogging(template, tableName, targetMeta, rows, policy, stats);
            } catch (RestoreOperationException e) {
                stats.setTablesFailed(stats.getTablesFailed() + 1);
                log.error("Failed to UPSERT_ALL for table {}", tableName, e);
                if (policy.errorPolicy() == ErrorPolicy.FAIL_FAST) {
                    throw e;
                }
            }
        }
    }

    @Override
    public void importTableRows(JdbcTemplate jdbcTemplate,
                                String tableName,
                                TableMeta currentTable,
                                List<Map<String, Object>> rows,
                                RestorePolicy policy,
                                RestoreStats stats) {

        if (rows == null || rows.isEmpty()) {
            log.info("Table {}: no rows in backup for UPSERT_ALL", tableName);
            return;
        }

        // Важно: используем базовую реализацию, которая вызовет getSql() с OVERWRITE_ON_CONFLICT
        // Это предотвращает двойной вызов getSql(), так как AbstractRestoreStrategy.importTableRows()
        // уже вызывает getSql() перед передачей в batchImporter
        super.importTableRows(jdbcTemplate, tableName, currentTable, rows, policy, stats);
    }

    @Override
    public void handleBatchResult(int count,
                                     String tableName,
                                     RestorePolicy policy,
                                     RestoreStats stats) {
        // Семантика "upsert": 1 = вставка, 2 = обновление
        if (count == 1) {
            stats.setRowsInserted(stats.getRowsInserted() + 1);
            inc(stats.getRowsPerTableInserted(), tableName);
        } else if (count == 2) {
            stats.setRowsUpdated(stats.getRowsUpdated() + 1);
            inc(stats.getRowsPerTableUpdated(), tableName);
        } else {
            // Нестандартный код — логируем предупреждение
            log.warn("Unexpected update count {} for UPSERT_ALL on table {}", count, tableName);
        }
    }

    @Override
    protected String getSql(String tableName, List<String> insertColumns, RowConflictPolicy rowConflictPolicy) {
        // Для UPSERT_ALL всегда используем OVERWRITE_ON_CONFLICT
        SqlBuilder sqlBuilder = sqlBuilderFactory.getSqlBuilder(RowConflictPolicy.OVERWRITE_ON_CONFLICT);
        return sqlBuilder.buildInsertSql(tableName, insertColumns);
    }
}
