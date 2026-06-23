package com.dentapinos.dataguard.test;

import com.dentapinos.dataguard.test.config.TestDatabaseConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.MySQLContainer;

/**
 * Базовый класс для интеграционных тестов с общей логикой инициализации Testcontainers MySQL.
 * <p>
 * Показывает подробную информацию о контейнере при старте:
 * - JDBC URL
 * - Имя базы данных
 * - Имя пользователя
 * - Пароль
 * - Статус контейнера
 * </p>
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class BaseIntegrationTest {

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    protected String databaseName = "testdb";

    @BeforeAll
    static void setupClass() {
        MySQLContainer<?> container = TestDatabaseConfig.getMySQLContainer();
        System.out.println("\n========================================");
        System.out.println("MySQL Container Started!");
        System.out.println("JDBC URL: " + container.getJdbcUrl());
        System.out.println("DB Name: " + container.getDatabaseName());
        System.out.println("Username: " + container.getUsername());
        System.out.println("Password: " + container.getPassword());
        System.out.println("Container Status: " + (container.isRunning() ? "RUNNING" : "NOT RUNNING"));
        System.out.println("========================================\n");
    }

    /**
     * 📌 ГЛАВНЫЙ МЕТОД — очистка БД перед каждым тестом.
     * TRUNCATE таблиц — самое быстрое и безопасное решение.
     * Удаляем в правильном порядке: от дочерних к родительским.
     */
    @BeforeEach
    void clearDatabase() {
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            String[] tables = {
                    "product_orders", "orders", "products",
                    "user_profiles", "categories", "users"
            };
            for (String table : tables) {
                jdbcTemplate.execute("TRUNCATE TABLE `" + table + "`");
                jdbcTemplate.execute("ALTER TABLE `" + table + "` AUTO_INCREMENT = 1");
            }
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
}
