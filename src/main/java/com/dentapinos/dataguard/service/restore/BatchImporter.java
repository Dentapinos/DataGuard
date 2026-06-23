package com.dentapinos.dataguard.service.restore;

import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.entity.RestoreStats;
import com.dentapinos.dataguard.enums.policy.ErrorPolicy;
import com.dentapinos.dataguard.enums.policy.RowConflictPolicy;
import com.dentapinos.dataguard.exception.RestoreOperationException;
import com.dentapinos.dataguard.service.restore.sqlbuilder.SqlBuilderFactory;
import com.dentapinos.dataguard.service.restore.strategy.AbstractRestoreStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class BatchImporter {

    private static final int BATCH_SIZE = 1000;

    private final SqlBuilderFactory sqlBuilderFactory;

    /**
     * Импортирует строки таблицы с использованием пакетной вставки.
     *
     * @param jdbcTemplate    JdbcTemplate для работы с БД
     * @param tableName       имя таблицы
     * @param rows            список строк для вставки
     * @param insertColumns   список колонок для вставки
     * @param sql             SQL-запрос для вставки
     * @param policy          политика восстановления
     * @param stats           объект для сбора статистики
     * @param strategy        стратегия для обработки результатов батча (optional)
     */
    public void importTableRows(JdbcTemplate jdbcTemplate,
                                String tableName,
                                List<Map<String, Object>> rows,
                                List<String> insertColumns,
                                String sql,
                                RestorePolicy policy,
                                RestoreStats stats,
                                com.dentapinos.dataguard.service.restore.strategy.RestoreStrategy strategy) {

        List<Map<String, Object>> batch = new ArrayList<>(BATCH_SIZE);

        try {
            for (Map<String, Object> row : rows) {
                Map<String, Object> filtered = new LinkedHashMap<>();
                for (String col : insertColumns) {
                    filtered.put(col, row.get(col));
                }
                batch.add(filtered);

                if (batch.size() >= BATCH_SIZE) {
                    executeBatch(jdbcTemplate, sql, insertColumns, tableName, batch, policy, stats, strategy);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                executeBatch(jdbcTemplate, sql, insertColumns, tableName, batch, policy, stats, strategy);
            }
        } catch (RestoreOperationException e) {
            // уже доменное – просто пробрасываем
            throw e;
        } catch (DuplicateKeyException e) {
            // на случай, если что-то проскочит мимо executeBatch
            throw new RestoreOperationException(
                    "Duplicate key while restoring table " + tableName,
                    tableName,
                    null,
                    0,
                    e.getMessage(),
                    e
            );
        } catch (Exception e) {
            log.error("Unexpected error while importing rows for table {}: {}", tableName, e.getMessage(), e);
            throw new RestoreOperationException(
                    "Unexpected error while importing rows for table " + tableName,
                    tableName,
                    null,
                    0,
                    e.getMessage(),
                    e
            );
        }
    }

    /**
     * Импортирует строки таблицы с автоматической генерацией SQL на основе политики конфликта.
     *
     * @param jdbcTemplate    JdbcTemplate для работы с БД
     * @param tableName       имя таблицы
     * @param rows            список строк для вставки
     * @param insertColumns   список колонок для вставки
     * @param policy          политика восстановления
     * @param stats           объект для сбора статистики
     * @param strategy        стратегия для обработки результатов батча (optional)
     */
    public void importTableRows(JdbcTemplate jdbcTemplate,
                                String tableName,
                                List<Map<String, Object>> rows,
                                List<String> insertColumns,
                                RestorePolicy policy,
                                RestoreStats stats,
                                com.dentapinos.dataguard.service.restore.strategy.RestoreStrategy strategy) {
        SqlBuilder sqlBuilder = sqlBuilderFactory.getSqlBuilder(policy.rowConflictPolicy());
        String sql = sqlBuilder.buildInsertSql(tableName, insertColumns);
        importTableRows(jdbcTemplate, tableName, rows, insertColumns, sql, policy, stats, strategy);
    }

    /**
     * Выполняет пакетную вставку данных.
     *
     * @param jdbcTemplate    JdbcTemplate для работы с БД
     * @param sql             SQL-запрос для вставки
     * @param insertColumns   список колонок для вставки
     * @param tableName       имя таблицы
     * @param batch           список строк для вставки
     * @param policy          политика восстановления
     * @param stats           объект для сбора статистики
     */
    protected void executeBatch(JdbcTemplate jdbcTemplate,
                                String sql,
                                List<String> insertColumns,
                                String tableName,
                                List<Map<String, Object>> batch,
                                RestorePolicy policy,
                                RestoreStats stats,
                                com.dentapinos.dataguard.service.restore.strategy.RestoreStrategy strategy) throws Exception {

        log.debug("[BATCH_IMPORT] Executing batch for table '{}', batch size: {}", tableName, batch.size());
        
        try {
            if (tableName.endsWith("_seq")) {
                log.warn("Таблицы _seq не меняем");
                return;
            }
            // Используем null вместо batchSize, чтобы драйвер сам определил размер батча
            int[][] res = jdbcTemplate.batchUpdate(sql, batch, -1, (ps, row) -> {
                for (int i = 0; i < insertColumns.size(); i++) {
                    String col = insertColumns.get(i);
                    Object value = row.get(col);
                    ps.setObject(i + 1, value);
                }
            });
            
            log.debug("[BATCH_IMPORT] Batch results: {} rows processed", res.length);

            for (int i = 0; i < res.length; i++) {
                int[] subBatchResult = res[i];
                int count = subBatchResult[0];
                log.debug("[BATCH_IMPORT] Row {} result: count={}", i, count);
                if (strategy != null) {
                    if (strategy instanceof AbstractRestoreStrategy absStrategy) {
                        absStrategy.handleBatchResult(count, tableName, policy, stats);
                    } else {
                        handleBatchResult(count, tableName, policy, stats);
                    }
                } else {
                    handleBatchResult(count, tableName, policy, stats);
                }
            }
        } catch (DuplicateKeyException e) {
            // достаём SQL-ошибку (MySQL и т.п.)
            String sqlState = null;
            int errorCode = 0;
            String sqlMessage;

            if (e.getCause() instanceof java.sql.SQLException sqlEx) {
                sqlState = sqlEx.getSQLState();
                errorCode = sqlEx.getErrorCode();
                sqlMessage = sqlEx.getMessage();
            } else if (e.getMostSpecificCause() instanceof java.sql.SQLException sqlEx2) {
                sqlState = sqlEx2.getSQLState();
                errorCode = sqlEx2.getErrorCode();
                sqlMessage = sqlEx2.getMessage();
            } else {
                sqlMessage = e.getMessage();
            }

            throw new RestoreOperationException(
                    "Duplicate key while restoring table " + tableName,
                    tableName,
                    sqlState,
                    errorCode,
                    sqlMessage,
                    e
            );
        } catch (Exception e) {
            // остальное – как у вас было (или тоже через RestoreOperationException)
            handleBatchInsertFailure(jdbcTemplate, sql, insertColumns, tableName, batch, policy, stats, e);
        }
    }

    /**
     * Обрабатывает результат одной операции в пакете.
     *
     * @param count           количество затронутых строк
     * @param tableName       имя таблицы
     * @param policy          политика восстановления
     * @param stats           объект для сбора статистики
     */
    protected void handleBatchResult(int count,
                                     String tableName,
                                     RestorePolicy policy,
                                     RestoreStats stats) {
        RowConflictPolicy rowPolicy = policy.rowConflictPolicy();

        if (rowPolicy == RowConflictPolicy.OVERWRITE_ON_CONFLICT) {
            if (count == 1) {
                stats.setRowsInserted(stats.getRowsInserted() + 1);
                inc(stats.getRowsPerTableInserted(), tableName);
            } else if (count == 2) {
                stats.setRowsUpdated(stats.getRowsUpdated() + 1);
                inc(stats.getRowsPerTableUpdated(), tableName);
            }
        } else if (rowPolicy == RowConflictPolicy.SKIP_ON_CONFLICT) {
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

    /**
     * Обрабатывает ошибку пакетной вставки.
     *
     * @param jdbcTemplate    JdbcTemplate для работы с БД
     * @param sql             SQL-запрос для вставки
     * @param insertColumns   список колонок для вставки
     * @param tableName       имя таблицы
     * @param batch           список строк для вставки
     * @param policy          политика восстановления
     * @param stats           объект для сбора статистики
     * @param exception       исключение
     */
    protected void handleBatchInsertFailure(JdbcTemplate jdbcTemplate,
                                            String sql,
                                            List<String> insertColumns,
                                            String tableName,
                                            List<Map<String, Object>> batch,
                                            RestorePolicy policy,
                                            RestoreStats stats,
                                            Exception exception) {
        log.error("Пакетная вставка для таблицы не получилась {}: {}", tableName, exception.getMessage(), exception);

        if (policy.errorPolicy() == ErrorPolicy.FAIL_FAST) {
            throw new RestoreOperationException(
                    "Пакетная вставка не удалась при восстановлении таблицы " + tableName,
                    tableName,
                    null,
                    0,
                    exception.getMessage(),
                    exception
            );
        }

        // Для НЕ FAIL_FAST – то, что у тебя есть (скипаем)
        RowConflictPolicy rowPolicy = policy.rowConflictPolicy();
        if (rowPolicy == RowConflictPolicy.SKIP_ON_CONFLICT) {
            log.warn("Batch провалился с SKIP_ON_CONFLICT политикой. Отмечаю все строки как пропущенные.");
        } else if (rowPolicy == RowConflictPolicy.OVERWRITE_ON_CONFLICT) {
            log.warn("Batch провалился с OVERWRITE_ON_CONFLICT политикой. Отмечаю все строки как пропущенные.");
        }

        stats.setRowsSkipped(stats.getRowsSkipped() + batch.size());
        batch.forEach(r -> inc(stats.getRowsPerTableSkipped(), tableName));
    }

    /**
     * Увеличивает счетчик в мапе.
     *
     * @param map   мапа для счетчика
     * @param key   ключ
     */
    public void inc(Map<String, Long> map, String key) {
        map.merge(key, 1L, Long::sum);
    }
}
