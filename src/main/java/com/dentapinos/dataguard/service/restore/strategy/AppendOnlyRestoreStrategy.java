package com.dentapinos.dataguard.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ColumnMeta;
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
 * Стратегия только добавления (APPEND_ONLY).
 * <p>
 * Восстанавливает только новые строки, существующие данные не трогаются.
 * Всегда использует {@link RowConflictPolicy#SKIP_ON_CONFLICT} для игнорирования дубликатов.
 * <p>
 * Проверка схемы выполняется в RestoreService через SchemaCompatibilityService,
 * здесь стратегия получает метаданные таблиц из БД для каждой таблицы отдельно.
 *
 * @see com.dentapinos.dataguard.enums.RestoreMode#APPEND_ONLY
 */
@Service
@Slf4j
public class AppendOnlyRestoreStrategy extends AbstractRestoreStrategy {


    private final SqlBuilderFactory sqlBuilderFactory;

    public AppendOnlyRestoreStrategy(JdbcTemplateFactory jdbcTemplateFactory, BatchImporter batchImporter, SqlBuilderFactory sqlBuilderFactory, DatabaseConfigurator databaseConfigurator, DatabaseMetadataReader metadataService) {
        super(jdbcTemplateFactory, batchImporter, databaseConfigurator, metadataService);
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
        List<String> tablesToProcess = tableOrder != null && !tableOrder.isEmpty()
                ? tableOrder.stream().filter(data::containsKey).toList()
                : new ArrayList<>(data.keySet());

        log.info("[APPEND_ONLY_RESTORE] Обработка таблиц в порядке: {}", tablesToProcess);

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
        // Для APPEND_ONLY всегда используем INSERT IGNORE для пропуска дубликатов
        SqlBuilder sqlBuilder = sqlBuilderFactory.getSqlBuilder(RowConflictPolicy.SKIP_ON_CONFLICT);
        return sqlBuilder.buildInsertSql(tableName, insertColumns);
    }

    @Override
    protected List<String> filterInsertColumns(TableMeta currentTable, List<Map<String, Object>> rows) {
        List<String> targetColumns = currentTable.columns().stream()
                .map(ColumnMeta::name)
                .toList();

        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        // Для APPEND_ONLY включаем все колонки из backup (включая id), которые существуют в целевой таблице.
        // INSERT IGNORE будет игнорировать строки, которые уже существуют по PRIMARY KEY или уникальным ключам
        Map<String, Object> firstRow = rows.get(0);
        return targetColumns.stream()
                .filter(firstRow::containsKey)
                .toList();
    }

    public void handleBatchResult(int count,
                                  String tableName,
                                  RestorePolicy policy,
                                  RestoreStats stats) {
        // Для APPEND_ONLY (INSERT IGNORE): 1 = вставлено, 0 = пропущено (дубликат)
        if (count == 1) {
            stats.setRowsInserted(stats.getRowsInserted() + 1);
            inc(stats.getRowsPerTableInserted(), tableName);
        } else {
            stats.setRowsSkipped(stats.getRowsSkipped() + 1);
            inc(stats.getRowsPerTableSkipped(), tableName);
        }
    }

    protected void inc(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
    }
}
