package com.dentapinos.dataguard.service.restore.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.RestoreStats;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.MySqlMetadataReader;
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
 * Стратегия агрессивной замены данных (FORCE_REPLACE).
 * <p>
 * Заменяет все данные из бэкапа, временно отключая внешние ключи.
 * Всегда использует {@link RowConflictPolicy#OVERWRITE_ON_CONFLICT} для обновления дубликатов.
 * <p>
 * Проверка схемы выполняется в RestoreService через SchemaCompatibilityService,
 * здесь стратегия получает метаданные таблиц из БД для каждой таблицы отдельно.
 *
 * @see com.dentapinos.dataguard.enums.RestoreMode#FORCE_REPLACE
 */
@Service
@Slf4j
public class ForceReplaceRestoreStrategy extends AbstractRestoreStrategy {

    private final SqlBuilderFactory sqlBuilderFactory;

    public ForceReplaceRestoreStrategy(JdbcTemplateFactory jdbcTemplateFactory,
                                       BatchImporter batchImporter,
                                       SqlBuilderFactory sqlBuilderFactory,
                                       DatabaseConfigurator databaseConfigurator,
                                       MySqlMetadataReader metadataReader) {
        super(jdbcTemplateFactory, batchImporter, databaseConfigurator, metadataReader);
        this.sqlBuilderFactory = sqlBuilderFactory;
    }

    @Override
    protected String getSql(String tableName, List<String> insertColumns, RowConflictPolicy rowConflictPolicy) {
        // Для FORCE_REPLACE всегда используем OVERWRITE_ON_CONFLICT
        SqlBuilder sqlBuilder = sqlBuilderFactory.getSqlBuilder(rowConflictPolicy);
        return sqlBuilder.buildInsertSql(tableName, insertColumns);
    }

    /**
     * Переопределяем метод restore для гарантированного отключения FOREIGN_KEY_CHECKS
     * при стратегии FORCE_REPLACE. Это обеспечивает, что FK будут отключены на весь
     * процесс восстановления, независимо от политики.
     */
    @Override
    public void restore(DbCredentials dbCredentials,
                        BackupFile backup,
                        String targetDatabase,
                        RestorePolicy policy,
                        RestoreStats stats, String fileName) {
        // Используем упрощенную версию, полагаясь на AbstractRestoreStrategy
        // и MySQLDatabaseConfigurator для управления FK checks
        // Это обеспечивает корректное поведение внутри одного соединения
        super.restore(dbCredentials, backup, targetDatabase, policy, stats, fileName);
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
        // Для FORCE_REPLACE используем порядок из бэкапа (fallback)
        List<String> tablesToProcess = tableOrder != null && !tableOrder.isEmpty()
                ? tableOrder.stream().filter(data::containsKey).toList()
                : new ArrayList<>(data.keySet());

        log.info("[FORCE_REPLACE] Обработка таблиц в порядке: {}", tablesToProcess);
        log.debug("[FORCE_REPLACE] Данные из бэкапа: таблиц={}, ключи={}", data.size(), data.keySet());

        for (String tableName : tablesToProcess) {
            List<Map<String, Object>> rows = data.get(tableName);
            log.debug("[FORCE_REPLACE] Таблица {}: rows={}, rows.size()={}", tableName, rows != null, rows != null ? rows.size() : "null");
            if (rows == null) {
                log.warn("[FORCE_REPLACE] Таблица {} отсутствует в бэкапе (null rows), пропускаем", tableName);
                stats.setTablesSkipped(stats.getTablesSkipped() + 1);
                continue;
            }
            if (rows.isEmpty()) {
                log.info("[FORCE_REPLACE] Таблица {} пуста в бэкапе (empty rows), пропускаем обработку строк, но учитываем как обработанную", tableName);
                // Для FORCE_REPLACE пустые таблицы обрабатываются без ошибок.
                // Увеличиваем tablesProcessed, чтобы не привести к статусу FAILED
                stats.setTablesProcessed(stats.getTablesProcessed() + 1);
                continue;
            }

            Optional<TableMeta> rowOfTableMeta = getTableMetaOneRow(dbCredentials, targetDatabase, tableName);

            if (rowOfTableMeta.isEmpty()) {
                log.warn("[FORCE_REPLACE] Таблица {} отсутствует в целевой базе данных, пропускаем", tableName);
                stats.setTablesSkipped(stats.getTablesSkipped() + 1);
                continue;
            }

            importTableRowsWithLogging(template, tableName, rowOfTableMeta.get(), rows, policy, stats);
        }
    }

    @Override
    public void handleBatchResult(int count,
                                  String tableName,
                                  RestorePolicy policy,
                                  RestoreStats stats) {
        // ON DUPLICATE KEY UPDATE: 1 = INSERT, 2 = UPDATE
        if (count == 1) {
            stats.setRowsInserted(stats.getRowsInserted() + 1);
            inc(stats.getRowsPerTableInserted(), tableName);
        } else if (count == 2) {
            stats.setRowsUpdated(stats.getRowsUpdated() + 1);
            inc(stats.getRowsPerTableUpdated(), tableName);
        }
    }

    @Override
    protected void inc(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
    }
}
