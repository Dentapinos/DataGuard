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
 * Стратегия строгого восстановления (STRICT).
 * <p>
 * Требует полного совпадения схемы и данных. При любых расхождениях выбрасывает исключение.
 * Всегда использует {@link RowConflictPolicy#FAIL_ON_CONFLICT}.
 * <p>
 * Проверка схемы выполняется в RestoreService через SchemaCompatibilityService,
 * здесь стратегия получает метаданные таблиц из БД для каждой таблицы отдельно.
 *
 * @see com.dentapinos.dataguard.enums.RestoreMode#STRICT
 */
@Service
@Slf4j
public class StrictRestoreStrategy extends AbstractRestoreStrategy {

    private final SqlBuilderFactory sqlBuilderFactory;

    public StrictRestoreStrategy(JdbcTemplateFactory jdbcTemplateFactory, BatchImporter batchImporter, DatabaseConfigurator databaseConfigurator, DatabaseMetadataReader mysqlMetadataReader, SqlBuilderFactory sqlBuilderFactory) {
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
        List<String> tablesToProcess = tableOrder != null && !tableOrder.isEmpty()
                ? tableOrder.stream().filter(data::containsKey).toList()
                : new ArrayList<>(data.keySet());

        log.info("[STRICT_RESTORE] Обработка таблиц в порядке: {}", tablesToProcess);

        for (String tableName : tablesToProcess) {
            try {
                // Получаем метаданные текущей таблицы из БД
                Optional<TableMeta> rowOfTableMeta = getTableMetaOneRow(dbCredentials, targetDatabase, tableName);

                // STRICT_SCHEMA: таблица должна существовать в БД
                if (rowOfTableMeta.isEmpty()) {
                    throw createSchemaMismatchException("Таблица отсутствует в целевой базе данных", tableName);
                }

                List<Map<String, Object>> rows = data.get(tableName);

                importTableRowsWithLogging(template,tableName, rowOfTableMeta.get(), rows, policy, stats);
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
    public void importTableRows(
            JdbcTemplate jdbcTemplate,
            String tableName,
            TableMeta currentTable,
            List<Map<String, Object>> rows,
            RestorePolicy policy,
            RestoreStats stats
    ) {
        List<String> targetColumns = currentTable.columns().stream()
                .map(ColumnMeta::name)
                .toList();

        if (rows.isEmpty()) {
            return;
        }

        Map<String, Object> firstRow = rows.get(0);
        List<String> insertColumns = new ArrayList<>();

        for (String col : targetColumns) {
            if (firstRow.containsKey(col)) {
                insertColumns.add(col);
            } else {
                throw createSchemaMismatchException("Столбец отсутствует в резервной копии", tableName + "." + col);
            }
        }

        // вызываем базовую реализацию, которая дергает BatchImporter
        super.importTableRows(jdbcTemplate, tableName, currentTable, rows, policy, stats);
    }

    @Override
    protected String getSql(String tableName, List<String> insertColumns, RowConflictPolicy rowConflictPolicy) {
        // Используем SqlBuilderFactory для генерации SQL
        SqlBuilder sqlBuilder = sqlBuilderFactory.getSqlBuilder(rowConflictPolicy);
        return sqlBuilder.buildInsertSql(tableName, insertColumns);
    }

    @Override
    public void handleBatchResult(int count,
                                  String tableName,
                                  RestorePolicy policy,
                                  RestoreStats stats) {
        // FAIL_ON_CONFLICT: ожидаем ровно 1 для успешной вставки
        if (count == 1) {
            stats.setRowsInserted(stats.getRowsInserted() + 1);
            inc(stats.getRowsPerTableInserted(), tableName);
        } else {
            throw createBatchResultException("Нарушение FAIL_ON_CONFLICT", tableName, count);
        }
    }

    /**
     * Создает исключение для ошибок несоответствия схемы.
     * Вызывается при нарушении STRICT_SCHEMA политики.
     *
     * @param description описание ошибки
     * @param tableName   имя таблицы (или столбца)
     * @return IllegalArgumentException
     */
    public static RestoreOperationException createSchemaMismatchException(String description, String tableName) {
        String msg = description + " для '" + tableName + "' (STRICT_SCHEMA требует полного совпадения схем)";
        log.error(msg);
        return new RestoreOperationException(
                msg,
                tableName,
                null,
                0,
                msg,
                new IllegalStateException(msg)
        );
    }

    /**
     * Создает исключение для ошибок пакетного результата.
     * Вызывается при нарушении FAIL_ON_CONFLICT политики.
     *
     * @param description описание ошибки
     * @param tableName   имя таблицы
     * @param count       фактическое количество затронутых строк
     * @return IllegalArgumentException
     */
    private static RestoreOperationException createBatchResultException(String description, String tableName, int count) {
        String msg = description + " для таблицы '" + tableName + "': ожидался count=1, получено " + count;
        log.error(msg);
        return new RestoreOperationException(
                msg,
                tableName,
                null,
                0,
                msg,
                new IllegalStateException(msg)
        );
    }

    /**
     * Увеличивает счетчик в мапе.
     *
     * @param map   мапа для счетчика
     * @param key   ключ
     */
    protected void inc(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
    }
}