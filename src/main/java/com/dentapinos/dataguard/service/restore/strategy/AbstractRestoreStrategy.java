package com.dentapinos.dataguard.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.*;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.exception.RestoreOperationException;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.service.restore.BatchImporter;
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Абстрактный базовый класс для стратегий восстановления бэкапов.
 * <p>
 * Выполняет общую инициализацию и управление транзакциями,
 * оставляя конкретную логику восстановления в подклассах.
 *
 * @see RestoreStrategy
 */
@Slf4j
public abstract class AbstractRestoreStrategy implements RestoreStrategy {

    private final JdbcTemplateFactory jdbcTemplateFactory;
    private final BatchImporter batchImporter;
    private final DatabaseConfigurator databaseConfigurator;
    private final DatabaseMetadataReader mysqlMetadataReader;

    public AbstractRestoreStrategy(
            JdbcTemplateFactory jdbcTemplateFactory,
            BatchImporter batchImporter,
            DatabaseConfigurator databaseConfigurator,
            DatabaseMetadataReader mysqlMetadataReader
    ) {
        this.jdbcTemplateFactory = jdbcTemplateFactory;
        this.batchImporter = batchImporter;
        this.databaseConfigurator = databaseConfigurator;
        this.mysqlMetadataReader = mysqlMetadataReader;
    }

    protected JdbcTemplateFactory getJdbcTemplateFactory() {
        return jdbcTemplateFactory;
    }

    @Override
    public void restore(DbCredentials dbCredentials,
                        BackupFile backup,
                        String targetDatabase,
                        RestorePolicy policy,
                        RestoreStats stats, String fileName) {
        try (JdbcTemplateFactory.JdbcConnection conn = jdbcTemplateFactory.forDatabase(dbCredentials, targetDatabase)) {
            JdbcTemplate template = conn.getJdbcTemplate();
            log.info("Начало восстановления: targetDb={}, backupName={}, schemaPolicy={}, foreignKeyPolicy={}, rowPolicy={}, errorPolicy={}",
                    targetDatabase, fileName, policy.schemaPolicy(), policy.foreignKeyPolicy(), policy.rowConflictPolicy(), policy.errorPolicy());

            databaseConfigurator.configureBeforeRestore(template, policy);
            Map<String, List<Map<String, Object>>> data = backup.data();
            // tablesTotal устанавливается в RestoreService до вызова strategy.restore(),
            // поэтому здесь просто используем его, если он уже задан
            if (stats.getTablesTotal() == 0) {
                stats.setTablesTotal(data.size());
            }

            // Получаем порядок таблиц из бэкапа (если он задан)
            List<String> tableOrder = backup.tableOrder();
            if (tableOrder != null && !tableOrder.isEmpty()) {
                log.info("[RESTORE_ORDER] Используем порядок таблиц из бэкапа: {}", tableOrder);
            } else {
                log.warn("[RESTORE_ORDER] Порядок таблиц в бэкапе не задан, используем порядок из data.keySet()");
            }

            try {
                performRestore(template, dbCredentials, backup, targetDatabase, data, policy, stats, tableOrder);
                log.info("Восстановление завершено: targetDb={}, tablesProcessed={}, tablesFailed={}, tablesSkipped={}, " +
                                "rowsInserted={}, rowsUpdated={}, rowsSkipped={}",
                        targetDatabase,
                        stats.getTablesProcessed(),
                        stats.getTablesFailed(),
                        stats.getTablesSkipped(),
                        stats.getRowsInserted(),
                        stats.getRowsUpdated(),
                        stats.getRowsSkipped());
            } catch (RestoreOperationException e) {
                log.error("RestoreOperationException during restore", e);
                throw e;
            } catch (Exception e) {
                log.error("Unexpected error during restore", e);
                throw new RestoreOperationException(
                        "Unexpected error during restore",
                        null, null, 0,
                        e.getMessage(),
                        e
                );
            } finally {
                try {
                    databaseConfigurator.configureAfterRestore(template, policy);
                } catch (Exception e) {
                    // логируем, но не перекрываем основное исключение
                    log.warn("Error while configureAfterRestore", e);
                }
            }
        }
    }


    protected Optional<TableMeta> getTableMetaOneRow(DbCredentials dbCredentials, String targetDatabase, String tableName) {
        SchemaMeta schema = mysqlMetadataReader.readSchema(
                dbCredentials,
                targetDatabase,
                List.of(tableName)
        );
        if (schema == null) {
            return Optional.empty();
        }
        return schema.tables().stream()
                .findFirst();
    }

    /**
     * Импортирует строки таблицы, логирует ошибки и обновляет статистику.
     * Исключение подавляется (не бросается), даже при FAIL_FAST.
     */
    protected void importTableRowsWithLogging(
            JdbcTemplate template,
            String tableName,
            TableMeta tableMeta,
            List<Map<String, Object>> rows,
            RestorePolicy policy,
            RestoreStats stats) {

        if (rows == null || rows.isEmpty()) {
            log.info("Table {}: no rows in backup, skipping insert", tableName);
            stats.setTablesProcessed(stats.getTablesProcessed() + 1);
            return;
        }

        try {
            importTableRows(template, tableName, tableMeta, rows, policy, stats);
            stats.setTablesProcessed(stats.getTablesProcessed() + 1);
        } catch (Exception e) {
            log.error("Error while importing rows for table {}: {}", tableName, e.getMessage(), e);
            stats.setTablesFailed(stats.getTablesFailed() + 1);
            stats.setTablesProcessed(stats.getTablesProcessed() + 1);
            // Подавляем исключение для LOG_AND_CONTINUE, пробрасываем для FAIL_FAST
            if (policy.errorPolicy() == com.dentapinos.dataguard.enums.policy.ErrorPolicy.FAIL_FAST) {
                throw e;
            }
            // Для LOG_AND_CONTINUE продолжаем выполнение
        }
    }

    /**
     * Выполняет конкретную логику восстановления в подклассах.
     *
     * @param template        JdbcTemplate для работы с БД
     * @param dbCredentials   учетные данные для подключения к целевой БД
     * @param backup          бэкап-файл с данными для восстановления
     * @param targetDatabase  имя целевой базы данных
     * @param policy          политика восстановления (стратегия)
     * @param stats           объект для сбора статистики операции
     * @param tableOrder      порядок таблиц для восстановления (из бэкапа или определенный RestoreOrderService)
     */
    protected abstract void performRestore(JdbcTemplate template,
                                           DbCredentials dbCredentials,
                                           BackupFile backup,
                                           String targetDatabase,
                                           Map<String, List<Map<String, Object>>> data,
                                           RestorePolicy policy,
                                           RestoreStats stats,
                                           List<String> tableOrder);

    public void importTableRows(
            JdbcTemplate jdbcTemplate,
            String tableName,
            TableMeta currentTable,
            List<Map<String, Object>> rows,
            RestorePolicy policy,
            RestoreStats stats
    ) {

        List<String> insertColumns = filterInsertColumns(currentTable, rows);

        if (insertColumns.isEmpty()) {
            log.debug("Нет колонок для импорта в таблицу {}", tableName);
            return;
        }

        String sql = getSql(tableName, insertColumns, policy.rowConflictPolicy());

        batchImporter.importTableRows(jdbcTemplate, tableName, rows, insertColumns, sql, policy, stats, this);
    }

    protected abstract String getSql(String tableName, List<String> insertColumns, RowConflictPolicy rowConflictPolicy);
    
    protected void inc(Map<String, Long> map, String key) {
        batchImporter.inc(map, key);
    }

    /**
     * Возвращает список колонок, которые присутствуют как в целевой таблице,
     * так и в строках данных бэкапа (на основе первой строки).
     * Если строк нет — возвращает пустой список.
     */
    protected List<String> filterInsertColumns(TableMeta currentTable, List<Map<String, Object>> rows) {
        List<String> targetColumns = currentTable.columns().stream()
                .map(ColumnMeta::name)
                .toList();

        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        // Для FORCE_REPLACE используем все доступные колонки из целевой таблицы
        // которые есть в данных бэкапа
        return rows.stream()
                .flatMap(row -> row.keySet().stream())
                .distinct() // уникальные имена колонок
                .filter(targetColumns::contains)
                .toList();
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
            } else {
                // STRICT может переопределить этот метод и бросать исключение
                throw new RestoreOperationException(
                        "Unexpected batch result for FAIL_ON_CONFLICT: count=" + count,
                        tableName,
                        null,
                        0,
                        "Unexpected batch result",
                        null
                );
            }
        }
    }
}
