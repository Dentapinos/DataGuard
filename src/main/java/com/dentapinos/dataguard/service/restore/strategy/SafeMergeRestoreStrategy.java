package com.dentapinos.dataguard.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.RestoreStats;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.exception.RestoreOperationException;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.service.restore.BatchImporter;
import com.dentapinos.dataguard.service.restore.SqlBuilder;
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import com.dentapinos.dataguard.service.restore.sqlbuilder.SqlBuilderFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Стратегия безопасного слияния (SAFE_MERGE).
 * <p>
 * Мягкое восстановление с пропуском конфликтующих строк и игнорированием расхождений схемы.
 * Использует {@link RowConflictPolicy#SKIP_ON_CONFLICT} для пропуска дубликатов.
 * <p>
 * Проверка схемы выполняется в RestoreService через SchemaCompatibilityService,
 * здесь стратегия получает метаданные таблиц из БД для каждой таблицы отдельно.
 *
 * @see com.dentapinos.dataguard.enums.RestoreMode#SAFE_MERGE
 */
@Service
@Slf4j
public class SafeMergeRestoreStrategy extends AbstractRestoreStrategy {

    private final SqlBuilderFactory sqlBuilderFactory;

    public SafeMergeRestoreStrategy(JdbcTemplateFactory jdbcTemplateFactory, BatchImporter batchImporter, DatabaseConfigurator databaseConfigurator, SqlBuilderFactory sqlBuilderFactory, DatabaseMetadataReader metadataReader) {
        super(jdbcTemplateFactory, batchImporter, databaseConfigurator, metadataReader);
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

        log.info("[SAFE_MERGE_RESTORE] Обработка таблиц в порядке: {}", tablesToProcess);

        for (String tableName : tablesToProcess) {
            try {
                // Получаем метаданные текущей таблицы из БД
                Optional<TableMeta> meta = getTableMetaOneRow(dbCredentials, targetDatabase, tableName);

                if (meta.isEmpty()) {
                    log.warn("Table {} from backup not found in target DB; data skipped (RELAXED mode)", tableName);
                    stats.setTablesSkipped(stats.getTablesSkipped() + 1);
                    continue;
                }

                List<Map<String, Object>> rows = data.get(tableName);

                importTableRowsWithLogging(template, tableName, meta.get(), rows, policy, stats);
            } catch (RestoreOperationException e) {
                stats.setTablesFailed(stats.getTablesFailed() + 1);
                log.error("Failed to restore table {}", tableName, e);
                if (policy.errorPolicy() == com.dentapinos.dataguard.enums.policy.ErrorPolicy.FAIL_FAST) {
                    throw e;
                }
                // иначе — продолжаем к следующей таблице
            }
        }
    }

    @Override
    protected String getSql(String tableName, List<String> insertColumns, RowConflictPolicy rowConflictPolicy) {
        // Используем SqlBuilderFactory для генерации SQL на основе политики конфликта
        SqlBuilder sqlBuilder = sqlBuilderFactory.getSqlBuilder(rowConflictPolicy);
        return sqlBuilder.buildInsertSql(tableName, insertColumns);
    }

    public void handleBatchResult(int count,
                                  String tableName,
                                  RestorePolicy policy,
                                  RestoreStats stats) {
        RowConflictPolicy rowPolicy = policy.rowConflictPolicy();

        if (rowPolicy == RowConflictPolicy.OVERWRITE_ON_CONFLICT) {
            // ON DUPLICATE KEY UPDATE: 1 = INSERT, 2 = UPDATE
            if (count == 1) {
                stats.setRowsInserted(stats.getRowsInserted() + 1);
                inc(stats.getRowsPerTableInserted(), tableName);
            } else if (count == 2) {
                stats.setRowsUpdated(stats.getRowsUpdated() + 1);
                inc(stats.getRowsPerTableUpdated(), tableName);
            }
        } else if (rowPolicy == RowConflictPolicy.SKIP_ON_CONFLICT) {
            // INSERT IGNORE: 1 = INSERT, 0 = skipped
            if (count == 1) {
                stats.setRowsInserted(stats.getRowsInserted() + 1);
                inc(stats.getRowsPerTableInserted(), tableName);
            } else {
                stats.setRowsSkipped(stats.getRowsSkipped() + 1);
                inc(stats.getRowsPerTableSkipped(), tableName);
            }
        } else if (rowPolicy == RowConflictPolicy.FAIL_ON_CONFLICT) {
            if (count == 1) {
                stats.setRowsInserted(stats.getRowsInserted() + 1);
                inc(stats.getRowsPerTableInserted(), tableName);
            }
        }
    }

    protected void inc(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
    }
}
