package com.dentapinos.dataguard.service.restore;

import java.util.List;

/**
 * Интерфейс для генерации SQL-запросов вставки в зависимости от политики конфликта.
 * <p>
 * Используется для стандартизации формирования SQL-выражений:
 * - FAIL_ON_CONFLICT: простая вставка
 * - SKIP_ON_CONFLICT: INSERT IGNORE
 * - OVERWRITE_ON_CONFLICT: INSERT...ON DUPLICATE KEY UPDATE
 */
public interface SqlBuilder {

    /**
     * Генерирует SQL-запрос для вставки строк в таблицу.
     *
     * @param tableName   имя таблицы
     * @param columns     список колонок для вставки
     * @return SQL-запрос с placeholder'ами для значений
     */
    String buildInsertSql(String tableName, List<String> columns);
}
