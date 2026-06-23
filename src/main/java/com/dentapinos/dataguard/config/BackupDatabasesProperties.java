package com.dentapinos.dataguard.config;

import com.dentapinos.dataguard.config.validation.ValidJdbcUrl;
import jakarta.annotation.PostConstruct;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

/**
 * Настройки баз данных для резервного копирования.
 * <p>Читает значения из конфигурации по префиксу "backup.param.databases".</p>
 * <p>Для production/local профилей список баз данных может быть пустым.</p>
 * <p>Для test профиля должен быть хотя бы один элемент (проверяется в тестовом конфиге).</p>
 */
@Slf4j
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "backup.param")
@Validated
public class BackupDatabasesProperties {

    @Size(message = "At least one database must be configured when using database features")
    @Valid
    private List<DatabaseConfig> databases = new ArrayList<>();

    /**
     * Конфигурация одной базы данных.
     */
    @Getter
    @Setter
    public static class DatabaseConfig {
        @NotBlank(message = "databaseName is required")
        private String databaseName;

        @NotBlank(message = "displayName is required")
        private String displayName;

        @NotBlank(message = "JDBC URL is required")
        @ValidJdbcUrl(message = "Invalid JDBC URL format. Expected: jdbc:mysql://host:port/database?param=value")
        private String url;

        @NotBlank(message = "username is required")
        private String username;

        @NotBlank(message = "password is required")
        private String password;

        @Override
        public String toString() {
            return "DatabaseConfig{" +
                    "databaseName='******'" +
                    ", displayName='" + displayName + '\'' +
                    ", url='******'" +
                    ", username='******'" +
                    ", password='******'" +
                    '}';
        }
    }

    /**
     * Логирование конфигурации после инициализации.
     */
    @PostConstruct
    public void logConfiguration() {
        log.info("Backup databases configured: {} database(s)", databases.size());
        databases.forEach(db -> 
            log.debug("  Database: {} (display: {})", "******", db.getDisplayName())
        );
    }
}