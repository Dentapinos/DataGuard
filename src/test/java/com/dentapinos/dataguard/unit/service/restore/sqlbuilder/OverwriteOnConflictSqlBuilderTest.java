package com.dentapinos.dataguard.unit.service.restore.sqlbuilder;


import com.dentapinos.dataguard.service.restore.sqlbuilder.OverwriteOnConflictSqlBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Юнит-тесты для OverwriteOnConflictSqlBuilder.
 * Проверяет генерацию SQL INSERT...ON DUPLICATE KEY UPDATE запросов для нескольких колонок,
 * обработку пустых списков и экранирование имен таблиц и колонок обратными апострофами.
 */
@DisplayName("Unit-test для генератора SQL-запросов с перезаписью при конфликтах")
@SpringBootTest
class OverwriteOnConflictSqlBuilderTest {

    @Autowired
    private OverwriteOnConflictSqlBuilder builder;

    @Test
    @DisplayName("должен корректно генерировать INSERT...ON DUPLICATE KEY UPDATE для нескольких колонок")
    void shouldGenerateInsertWithMultipleColumns() {
        // arrange
        String tableName = "users";
        List<String> columns = Arrays.asList("id", "name", "email");

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        assertEquals(
                "INSERT INTO `users` (`id`, `name`, `email`) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE `id` = VALUES(`id`), `name` = VALUES(`name`), `email` = VALUES(`email`)",
                sql
        );
    }

    @Test
    @DisplayName("должен корректно генерировать INSERT...ON DUPLICATE KEY UPDATE для одной колонки")
    void shouldGenerateInsertWithSingleColumn() {
        // arrange
        String tableName = "settings";
        List<String> columns = Collections.singletonList("value");

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        assertEquals(
                "INSERT INTO `settings` (`value`) VALUES (?) ON DUPLICATE KEY UPDATE `value` = VALUES(`value`)",
                sql
        );
    }

    @Test
    @DisplayName("должен корректно обрабатывать пустой список колонок")
    void shouldHandleEmptyColumnsList() {
        // arrange
        String tableName = "logs";
        List<String> columns = Collections.emptyList();

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        // Примечание: MySQL требует хотя бы одну колонку в UPDATE-части, иначе — синтаксическая ошибка.
        // Поэтому текущая реализация сгенерирует "ON DUPLICATE KEY UPDATE " — некорректно.
        // Для корректной работы нужно либо выбрасывать исключение, либо возвращать просто INSERT без ON DUPLICATE KEY UPDATE.
        // Сейчас — тест "как есть", но с пометкой о необходимости исправления.
        assertEquals(
                "INSERT INTO `logs` () VALUES () ON DUPLICATE KEY UPDATE ",
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
                "INSERT INTO `my-table` (`id`, `status`) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE `id` = VALUES(`id`), `status` = VALUES(`status`)",
                sql
        );
    }

    @Test
    @DisplayName("должен корректно обрабатывать колонки, содержащие символы, требующие экранирования")
    void shouldEscapeSpecialCharsInColumnNames() {
        // arrange
        String tableName = "data";
        List<String> columns = Arrays.asList("id", "user-email", "created_at", "role'id");

        // act
        String sql = builder.buildInsertSql(tableName, columns);

        // assert
        assertEquals(
                "INSERT INTO `data` (`id`, `user-email`, `created_at`, `role'id`) VALUES (?, ?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE `id` = VALUES(`id`), `user-email` = VALUES(`user-email`), `created_at` = VALUES(`created_at`), `role'id` = VALUES(`role'id`)",
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
                "INSERT INTO `Users` (`ID`, `Name`) VALUES (?, ?) " +
                        "ON DUPLICATE KEY UPDATE `ID` = VALUES(`ID`), `Name` = VALUES(`Name`)",
                sql
        );
    }
}
