package com.dentapinos.dataguard.service.restore.sqlbuilder;

import com.dentapinos.dataguard.service.restore.SqlBuilder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Реализация SqlBuilder для политики FAIL_ON_CONFLICT.
 * <p>
 * Генерирует простой INSERT запрос без специальной обработки конфликтов.
 * Ожидаемое поведение: при конфликте будет выброшено исключение.
 * <p>
 * SQL пример: INSERT INTO `users` (`id`, `name`) VALUES (?, ?)
 */
@Component
public class FailOnConflictSqlBuilder implements SqlBuilder {

    @Override
    public String buildInsertSql(String tableName, List<String> columns) {
        String colsSql = columns.stream()
                .map(c -> "`" + c + "`")
                .collect(Collectors.joining(", "));

        String placeholders = columns.stream()
                .map(c -> "?")
                .collect(Collectors.joining(", "));

        return "INSERT INTO `" + tableName + "` (" + colsSql + ") VALUES (" + placeholders + ")";
    }
}
