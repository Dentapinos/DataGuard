package com.dentapinos.dataguard.service.restore.sqlbuilder;

import com.dentapinos.dataguard.service.restore.SqlBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Реализация SqlBuilder для политики SKIP_ON_CONFLICT.
 * <p>
 * Генерирует INSERT IGNORE запрос, который игнорирует строки, вызывающие конфликты
 * (дубликаты уникальных ключей).
 * <p>
 * SQL пример: INSERT IGNORE INTO `users` (`id`, `name`) VALUES (?, ?)
 */
@Component
public class SkipOnConflictSqlBuilder implements SqlBuilder {

    @Override
    public String buildInsertSql(String tableName, List<String> columns) {
        String colsSql = columns.stream()
                .map(c -> "`" + c + "`")
                .collect(Collectors.joining(", "));

        String placeholders = columns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        return "INSERT IGNORE INTO `" + tableName + "` (" + colsSql + ") VALUES (" + placeholders + ")";
    }
}
