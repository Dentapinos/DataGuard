package com.dentapinos.dataguard.service.restore.sqlbuilder;

import com.dentapinos.dataguard.service.restore.SqlBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Реализация SqlBuilder для политики OVERWRITE_ON_CONFLICT.
 * <p>
 * Генерирует INSERT...ON DUPLICATE KEY UPDATE запрос, который при конфликте
 * обновляет существующую строку данными из вставки.
 * <p>
 * SQL пример:
 * INSERT INTO `users` (`id`, `name`) VALUES (?, ?)
 * ON DUPLICATE KEY UPDATE `id` = VALUES(`id`), `name` = VALUES(`name`)
 */
@Component
public class OverwriteOnConflictSqlBuilder implements SqlBuilder {

    @Override
    public String buildInsertSql(String tableName, List<String> columns) {
        String colsSql = columns.stream()
                .map(c -> "`" + c + "`")
                .collect(Collectors.joining(", "));

        String placeholders = columns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        String updateClause = columns.stream()
                .map(c -> "`" + c + "` = VALUES(`" + c + "`)")
                .collect(Collectors.joining(", "));

        return "INSERT INTO `" + tableName + "` (" + colsSql + ") VALUES (" + placeholders + ") " +
                "ON DUPLICATE KEY UPDATE " + updateClause;
    }
}
