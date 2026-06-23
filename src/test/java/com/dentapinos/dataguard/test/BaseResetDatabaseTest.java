package com.dentapinos.dataguard.test;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Базовый класс для интеграционных тестов с полной пересозданием БД перед каждым тестом.
 * <p>
 * После очистки всех данных выполняется пересоздание схемы из schema.sql.
 * Это гарантирует идеальную изоляцию тестов и предотвращает проблемы с FK-ограничениями.
 * </p>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseResetDatabaseTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected String databaseName = "testdb";

    /**
     * 📌 ОБЯЗАТЕЛЬНЫЙ МЕТОД — полное пересоздание БД перед каждым тестом.
     * 1. Удаляем все данные из таблиц (DROP TABLE)
     * 2. Пересоздаем схему из schema.sql
     */
    @BeforeEach
    protected void resetDatabase() {
        System.out.println("\n========================================");
        System.out.println("[RESET] Полное пересоздание базы данных...");
        System.out.println("========================================\n");

        // Отключаем FK checks (на всякий случай, хотя мы всё равно удалим БД)
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");

        // 1. Удаляем БД (если существует)
        try {
            jdbcTemplate.execute("DROP DATABASE IF EXISTS `" + databaseName + "`");
            System.out.println("[RESET] База данных удалена: " + databaseName);
        } catch (Exception e) {
            System.out.println("[WARN] Ошибка при удалении БД (возможно, нет прав): " + e.getMessage());
        }

        // 2. Создаём БД заново
        jdbcTemplate.execute("CREATE DATABASE `" + databaseName + "`");
        System.out.println("[RESET] База данных создана: " + databaseName);

        // 3. Переключаемся на новую БД (важно!)
        jdbcTemplate.execute("USE `" + databaseName + "`");
        System.out.println("[RESET] Переключено на базу данных: " + databaseName);

        try {
            // 4. Выполняем schema.sql — теперь он будет работать в новой БД
            String schemaSql = loadSchemaSql();
            System.out.println("[RESET] Выполнение schema.sql...");
            executeSqlStatements(schemaSql); // <-- ✅ New helper method
            System.out.println("[RESET] Схема успешно восстановлена");
        } catch (Exception e) {
            System.err.println("[ERROR] Не удалось выполнить schema.sql!");
            e.printStackTrace();
            throw new RuntimeException("Не удалось восстановить схему базы данных", e);
        }

        // 5. Включаем FK checks обратно
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        System.out.println("\n========================================");
        System.out.println("[RESET] База данных полностью пересоздана!");
        System.out.println("========================================\n");
    }

    /**
     * Загружает schema.sql из ресурсов тестов.
     */
    private String loadSchemaSql() throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("schema.sql")) {
            assert is != null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

                return reader.lines().collect(Collectors.joining("\n"));
            }
        }
    }

    /**
     * 🛠️ Executes multiple SQL statements separated by semicolons.
     * Skips empty lines and comments-only lines.
     */
    private void executeSqlStatements(String sql) {
        if (sql == null || sql.isBlank()) return;

        // Split by semicolon, preserve comment lines inside CREATE TABLE
        String[] statements = sql
                .replaceAll("(?m)^\\s*--.*$", "") // Remove line comments
                .split(";\\s*");                    // Split by semicolon + optional whitespace

        for (String stmt : statements) {
            String cleaned = stmt.trim();
            if (cleaned.isEmpty() || cleaned.toLowerCase().startsWith("create database") ||
                    cleaned.toLowerCase().startsWith("use ")) {
                continue; // Skip DB-level commands (we handle those separately)
            }
            if (!cleaned.isEmpty()) {
                jdbcTemplate.execute(cleaned);
            }
        }
    }
}
