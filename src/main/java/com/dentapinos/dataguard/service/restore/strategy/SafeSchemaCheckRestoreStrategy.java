package com.dentapinos.dataguard.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ColumnMeta;
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
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Стратегия SAFE_SCHEMA_CHECK:
 * - Проверяет только схему (наличие таблиц и колонок);
 * - Не пишет никаких данных;
 * - Используется для валидации перед восстановлением.
 */
@Slf4j
@Component
public class SafeSchemaCheckRestoreStrategy extends AbstractRestoreStrategy {

    public SafeSchemaCheckRestoreStrategy(JdbcTemplateFactory jdbcTemplateFactory,
                                          BatchImporter batchImporter,
                                          DatabaseConfigurator databaseConfigurator,
                                          DatabaseMetadataReader mysqlMetadataReader) {
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

        log.info("Running SAFE_SCHEMA_CHECK restore: only schema will be validated, no data will be written");

        // Используем порядок из бэкапа, если он задан; иначе - порядок из data.keySet()
        List<String> tablesToProcess = backup.tableOrder() != null && !backup.tableOrder().isEmpty()
                ? backup.tableOrder().stream().filter(data::containsKey).toList()
                : new ArrayList<>(data.keySet());

        log.info("[SAFE_SCHEMA_CHECK] Обработка таблиц в порядке: {}", tablesToProcess);

        for (String tableName : tablesToProcess) {
            try {
                Optional<TableMeta> metaOpt = getTableMetaOneRow(dbCredentials, targetDatabase, tableName);

                if (metaOpt.isEmpty()) {
                    if (policy.schemaPolicy() == SchemaPolicy.STRICT_SCHEMA) {
                        throw new RestoreOperationException(
                                "Missing table in target DB during SAFE_SCHEMA_CHECK strict schema mode",
                                tableName,
                                null,
                                0,
                                "Target table not found",
                                null
                        );
                    } else {
                        log.warn("[SAFE_SCHEMA_CHECK] Table {} from backup not found in target DB", tableName);
                        // Для SAFE_SCHEMA_CHECK пропущенная таблица - это статус COMPLETED_WITH_WARNINGS
                        // Увеличиваем только tablesSkipped, не tablesProcessed
                        stats.setTablesSkipped(stats.getTablesSkipped() + 1);
                        continue;
                    }
                }

                TableMeta targetMeta = metaOpt.get();
                validateTableSchema(tableName, targetMeta, data.get(tableName));

                stats.setTablesProcessed(stats.getTablesProcessed() + 1);
            } catch (RestoreOperationException e) {
                stats.setTablesFailed(stats.getTablesFailed() + 1);
                log.error("[SAFE_SCHEMA_CHECK] Schema validation failed for table {}", tableName, e);
                if (policy.errorPolicy() == ErrorPolicy.FAIL_FAST) {
                    throw e;
                }
            }
        }
    }

    private void validateTableSchema(String tableName,
                                     TableMeta targetMeta,
                                     List<Map<String, Object>> rows) {

        if (rows == null || rows.isEmpty()) {
            log.info("[SAFE_SCHEMA_CHECK] Table {}: no rows in backup, schema-only check passed", tableName);
            return;
        }

        List<String> targetColumns = targetMeta.columns().stream()
                .map(ColumnMeta::name)
                .toList();

        Map<String, Object> firstRow = rows.get(0);

        // Для RELAXED_SCHEMA: если колонка отсутствует в бэкапе - это ошибка, ожидаемая в данных
        for (String col : targetColumns) {
            if (!firstRow.containsKey(col)) {
                log.warn("[SAFE_SCHEMA_CHECK] Column {} is missing in backup for table {}, " +
                                "but column exists in target schema (this is OK for RELAXED_SCHEMA)",
                        col, tableName);
            }
        }

        // Для RELAXED_SCHEMA: лишние колонки в бэкапе разрешены (только логируем)
        for (String col : firstRow.keySet()) {
            if (!targetColumns.contains(col)) {
                log.warn("[SAFE_SCHEMA_CHECK] Column {} is present in backup for table {}, " +
                                "but not found in target schema (extra column in backup, allowed in RELAXED_SCHEMA)",
                        col, tableName);
            }
        }

        log.info("[SAFE_SCHEMA_CHECK] Table {}: schema validation passed", tableName);
    }

    @Override
    public void importTableRows(JdbcTemplate jdbcTemplate,
                                   String tableName,
                                   TableMeta tableMeta,
                                   List<Map<String, Object>> rows,
                                   RestorePolicy policy,
                                   RestoreStats stats) {
        // Никаких вставок — это чисто проверка схемы
        throw new UnsupportedOperationException("SAFE_SCHEMA_CHECK strategy should not perform real imports");
    }

    @Override
    protected String getSql(String tableName, List<String> insertColumns, RowConflictPolicy rowConflictPolicy) {
        return "";
    }

    @Override
    public void handleBatchResult(int count,
                                     String tableName,
                                     RestorePolicy policy,
                                     RestoreStats stats) {
        // Не вызывается
    }
}
