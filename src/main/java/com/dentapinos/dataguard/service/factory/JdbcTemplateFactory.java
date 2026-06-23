package com.dentapinos.dataguard.service.factory;


import com.dentapinos.dataguard.dto.DbCredentials;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Фабрика JdbcTemplate на основе переданных параметров подключения.
 * <p>
 * Отвечает за создание и базовую настройку DataSource/JdbcTemplate.
 */
@Component
@Slf4j
public class JdbcTemplateFactory {

    private final ConcurrentMap<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final static int POOL_SIZE_DB = 5;
    private final static int POOL_SIZE_SCHEMA = 10;


    @Data
    public static class JdbcConnection implements AutoCloseable {
        private final JdbcTemplate jdbcTemplate;
        private final HikariDataSource dataSource;

        @Override
        public void close() {
            //DataSource НЕ закрывается после каждой операции!
            //Он переиспользуется по ключу. Закрывается только в @PreDestroy.
            //Это предотвращает ошибку "HikariDataSource has been closed"
        }
    }

    // Для операций с сервером (создание БД и т.д.)
    @Lazy
    public JdbcConnection createServerConnection(DbCredentials dbCredentials) {
        String serverUrl = removeDatabaseFromUrl(dbCredentials.url());
        String key = "server-" + serverUrl;
        HikariDataSource ds = dataSources.computeIfAbsent(key, k -> createDataSource(serverUrl, dbCredentials.username(), dbCredentials.password(), POOL_SIZE_DB));
        return new JdbcConnection(new JdbcTemplate(ds), ds);
    }

    // Для работы с конкретной БД
    @Lazy
    public JdbcConnection create(DbCredentials credentials) {
        String key = "db-" + credentials.url();
        HikariDataSource ds = dataSources.computeIfAbsent(key, k -> createDataSource(credentials.url(), credentials.username(), credentials.password(), POOL_SIZE_SCHEMA));
        return new JdbcConnection(new JdbcTemplate(ds), ds);
    }

    private String removeDatabaseFromUrl(String jdbcUrl) {
        // Удаляем имя БД из URL (всё между // и ?)
        // Пример: jdbc:mysql://localhost:3306/test?useSSL=false -> jdbc:mysql://localhost:3306?useSSL=false
        int lastSlash = jdbcUrl.lastIndexOf('/');
        int questionMark = jdbcUrl.indexOf('?');
        
        if (lastSlash > 0 && (questionMark < 0 || lastSlash < questionMark)) {
            return jdbcUrl.substring(0, lastSlash) + (questionMark > 0 ? jdbcUrl.substring(questionMark) : "");
        }
        return jdbcUrl;
    }

    @Lazy
    public JdbcConnection forDatabase(DbCredentials credentials, String databaseName) {
        String url = credentials.url();
        if (!url.contains("/" + databaseName)) {
            url = url.replaceFirst("/[^/?]+", "/" + databaseName);
        }
        String finalUrl = url;
        DbCredentials modified = new DbCredentials(url, credentials.username(), credentials.password());
        String key = "db-" + url;
        HikariDataSource ds = dataSources.computeIfAbsent(key, k -> createDataSource(finalUrl, modified.username(), modified.password(), POOL_SIZE_SCHEMA));
        return new JdbcConnection(new JdbcTemplate(ds), ds);
    }

    private HikariDataSource createDataSource(String jdbcUrl, String username, String password, int poolSize) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setMaximumPoolSize(poolSize);
        ds.setMinimumIdle(Math.min(5, poolSize));
        ds.setConnectionTimeout(30000);
        log.debug("Created HikariDataSource for URL: {} with pool size: {}", jdbcUrl, poolSize);
        return ds;
    }

    @PreDestroy
    public void destroy() {
        log.info("Closing all HikariDataSources...");
        dataSources.values().forEach(ds -> {
            try {
                ds.close();
                log.debug("Closed HikariDataSource for URL: {}", ds.getJdbcUrl());
            } catch (Exception e) {
                log.warn("Error closing HikariDataSource for URL: {}", ds.getJdbcUrl(), e);
            }
        });
        dataSources.clear();
        log.info("All HikariDataSources closed successfully");
    }

}
