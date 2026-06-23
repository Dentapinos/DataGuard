package com.dentapinos.dataguard.test.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

@TestConfiguration
@EnableJpaRepositories(basePackages = "com.dentapinos.dataguard.test.domain")
@EntityScan(basePackages = {"com.dentapinos.dataguard.test.domain"})
public class TestDatabaseConfig {

    private static final String DB_NAME = "testdb";
    private static final String DB_USER = "testuser";
    private static final String DB_PASSWORD = "testpass";

    private static MySQLContainer<?> mysqlContainer;

    static {
        // ===== Проверка Docker перед инициализацией =====
        try {
            System.out.println("\n=== Проверка Docker окружения ===");
            
            // Проверяем, что Docker доступен
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Ждем 5 секунд на завершение
            boolean finished = process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            
            if (!finished) {
                process.destroy();
                throw new RuntimeException("Docker проверка не завершилась вовремя. Убедитесь, что Docker Desktop запущен.");
            }
            
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Docker не запущен или недоступен. Код выхода: " + exitCode);
            }
            
            System.out.println("[OK] Docker окружение проверено и готово к работе");
            System.out.println("==========================================\n");
            
            // ===== Инициализация MySQL контейнера =====
            System.out.println("[INFO] Запуск MySQL контейнера...");
            
            mysqlContainer = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
                    .withDatabaseName(DB_NAME)
                    .withUsername(DB_USER)
                    .withPassword(DB_PASSWORD)
                    .withExposedPorts(3306)
                    .withClasspathResourceMapping("schema.sql", "/docker-entrypoint-initdb.d/init.sql", BindMode.READ_WRITE)  // Инициализация таблиц из schema.sql
                    .withCommand("--max_connections=500");  // Настройка max_connections
            mysqlContainer.start();
            
            // Expose JDBC URL as system property for application properties
            System.setProperty("MYSQL_JDBC_URL", mysqlContainer.getJdbcUrl());
            
            System.out.println("[OK] MySQL контейнер запущен успешно");
            System.out.println("[OK] Таблицы инициализированы из schema.sql");
            System.out.println("==========================================\n");
            
        } catch (Exception e) {
            System.err.println("\n==========================================");
            System.err.println("[ОШИБКА] Не удалось инициализировать тестовую базу данных!");
            System.err.println("==========================================");
            System.err.println("");
            System.err.println("Пожалуйста, убедитесь, что:");
            System.err.println("1. Docker Desktop запущен на вашем компьютере");
            System.err.println("2. Docker работает корректно (попробуйте выполнить 'docker ps')");
            System.err.println("3. У вас есть права на запуск Docker контейнеров");
            System.err.println("");
            System.err.println("Оригинальная ошибка:");
            System.err.println(e.getMessage());
            System.err.println("");
            System.err.println("==========================================\n");
            throw new RuntimeException("Не удалось инициализировать тестовую базу данных. Docker не запущен или недоступен.", e);
        }
    }

    @Bean
    public DataSource dataSource() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(mysqlContainer.getJdbcUrl());
        dataSource.setUsername(DB_USER);
        dataSource.setPassword(DB_PASSWORD);
        dataSource.setDriverClassName(com.mysql.cj.jdbc.Driver.class.getName());
        return dataSource;
    }
    
    @Bean
    public com.dentapinos.dataguard.service.factory.JdbcTemplateFactory jdbcTemplateFactory() {
        return new com.dentapinos.dataguard.service.factory.JdbcTemplateFactory();
    }

    public static MySQLContainer<?> getMySQLContainer() {
        return mysqlContainer;
    }
}
