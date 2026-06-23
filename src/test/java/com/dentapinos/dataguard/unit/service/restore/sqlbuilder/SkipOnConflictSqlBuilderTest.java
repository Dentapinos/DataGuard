package com.dentapinos.dataguard.unit.service.restore.sqlbuilder;


import com.dentapinos.dataguard.service.restore.sqlbuilder.SkipOnConflictSqlBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Юнит-тесты для SkipOnConflictSqlBuilder.
 * Проверяет генерацию SQL INSERT IGNORE запросов для нескольких колонок,
 * обработку пустых списков и экранирование имен таблиц и колонок обратными апострофами.
 */
@DisplayName("Unit-test для генератора SQL-запросов с пропуском при конфликтах")
@SpringBootTest
class SkipOnConflictSqlBuilderTest {

    @Autowired
    private SkipOnConflictSqlBuilder builder;

    @Test
    @DisplayName("должен корректно генерировать INSERT IGNORE для нескольких колонок")
    void shouldGenerateInsertIgnoreWithMultipleColumns() {
        // arrange
        String tableName = "users";
        List<String> columns = Arrays.asList("id", "name", "email");

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        assertEquals(
                "INSERT IGNORE INTO `users` (`id`, `name`, `email`) VALUES (?, ?, ?)",
                sql
        );
    }

    @Test
    @DisplayName("должен корректно генерировать INSERT IGNORE для одной колонки")
    void shouldGenerateInsertIgnoreWithSingleColumn() {
        // arrange
        String tableName = "settings";
        List<String> columns = Collections.singletonList("value");

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        assertEquals(
                "INSERT IGNORE INTO `settings` (`value`) VALUES (?)",
                sql
        );
    }

    @Test
    @DisplayName("должен генерировать корректный запрос при пустом списке колонок")
    void shouldHandleEmptyColumnsList() {
        // arrange
        String tableName = "logs";
        List<String> columns = Collections.emptyList();

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        assertEquals(
                "INSERT IGNORE INTO `logs` () VALUES ()",
                sql
        );
    }

    @Test
    @DisplayName("должен экранировать имена таблиц и колонок обратными апострофами")
    void shouldEscapeBackticks() {
        // arrange
        String tableName = "my-table";
        List<String> columns = Arrays.asList("id", "status");

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        assertEquals(
                "INSERT IGNORE INTO `my-table` (`id`, `status`) VALUES (?, ?)",
                sql
        );
    }

    @Test
    @DisplayName("должен корректно обрабатывать колонки, содержащие специальные символы")
    void shouldEscapeSpecialCharsInColumnNames() {
        // arrange
        String tableName = "data";
        List<String> columns = Arrays.asList("id", "user-email", "created_at", "role'id");

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        assertEquals(
                "INSERT IGNORE INTO `data` (`id`, `user-email`, `created_at`, `role'id`) VALUES (?, ?, ?, ?)",
                sql
        );
    }

    @Test
    @DisplayName("должен работать с регистронезависимыми именами")
    void shouldSupportCaseInsensitiveNames() {
        // arrange
        String tableName = "Users";
        List<String> columns = Arrays.asList("ID", "Name");

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        assertEquals(
                "INSERT IGNORE INTO `Users` (`ID`, `Name`) VALUES (?, ?)",
                sql
        );
    }
}
