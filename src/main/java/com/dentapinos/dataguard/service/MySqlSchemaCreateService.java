package com.dentapinos.dataguard.service;


import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.*;
import com.dentapinos.dataguard.exception.DatabaseCreationException;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MySqlSchemaCreateService implements DatabaseSchemaCreator{

    private final JdbcTemplateFactory jdbcTemplateFactory;

    @Override
    public void createDatabaseIfNotExists(DbCredentials dbCredentials,
                                          String dbName) {
        try (JdbcTemplateFactory.JdbcConnection conn = jdbcTemplateFactory.createServerConnection(dbCredentials)) {
            JdbcTemplate jdbcTemplate = conn.getJdbcTemplate();
            String safeDbName = dbName.replace("`", "``");
            String sql = "CREATE DATABASE IF NOT EXISTS `" + safeDbName + "` " +
                    "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci";
            jdbcTemplate.execute(sql);
        }
    }

    @Override
    public void createDatabaseIfNotExistsElseException(DbCredentials dbCredentials, String dbName) {
        try (JdbcTemplateFactory.JdbcConnection conn = jdbcTemplateFactory.createServerConnection(dbCredentials)) {
            JdbcTemplate serverJdbcTemplate = conn.getJdbcTemplate();

            try {
                String safeDbName = dbName.replace("`", "``");
                String checkSql = """
                SELECT COUNT(*) > 0
                FROM information_schema.schemata
                WHERE schema_name = ?
            """;
                boolean databaseExists = Boolean.TRUE.equals(
                        serverJdbcTemplate.queryForObject(checkSql, Boolean.class, dbName)
                );

                if (databaseExists) {
                    throw new DatabaseCreationException("База данных '" + dbName + "' уже существует!");
                } else {
                    String createSql = "CREATE DATABASE `" + safeDbName + "`";
                    serverJdbcTemplate.execute(createSql);
                    log.info("База данных '{}' успешно создана!", dbName);
                }
            } catch (Exception e) {
                throw new DatabaseCreationException("Ошибка при создании базы данных '" + dbName + "': " + e.getMessage(), e);
            }
        }
    }

    @Override
    public void createTables(DbCredentials dbCredentials,
                             String dbName,
                             SchemaMeta schema) {
        try (JdbcTemplateFactory.JdbcConnection conn = jdbcTemplateFactory.create(dbCredentials)) {
            JdbcTemplate jdbcTemplate = conn.getJdbcTemplate();

            // USE target DB
            jdbcTemplate.execute("USE `" + dbName + "`");

            // Отключаем проверку внешних ключей
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");

            try {
                // 1. CREATE TABLE без FK
                for (TableMeta table : schema.tables()) {
                    String ddl = buildCreateTable(table);
                    jdbcTemplate.execute(ddl);
                }

                // 2. ALTER TABLE ... ADD FOREIGN KEY
                for (TableMeta table : schema.tables()) {
                    for (ForeignKeyMeta fk : table.foreignKeys()) {
                        String ddl = buildAddForeignKey(table.name(), fk);
                        jdbcTemplate.execute(ddl);
                    }
                }
            } finally {
                // Включаем проверку внешних ключей
                jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    private String buildCreateTable(TableMeta table) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE `").append(table.name()).append("` (\n");
        List<String> lines = new ArrayList<>();

        for (ColumnMeta col : table.columns()) {
            StringBuilder colDef = new StringBuilder("  `")
                    .append(col.name()).append("` ")
                    .append(col.type());

            if (!col.nullable()) {
                colDef.append(" NOT NULL");
            } else {
                colDef.append(" NULL");
            }
            if (col.autoIncrement()) {
                colDef.append(" AUTO_INCREMENT");
            }
            lines.add(colDef.toString());
        }

        if (!table.primaryKey().isEmpty()) {
            String pk = table.primaryKey().stream()
                    .map(c -> "`" + c + "`")
                    .collect(Collectors.joining(", "));
            lines.add("  PRIMARY KEY (" + pk + ")");
        }

        // Индексы (без FK)
        for (IndexMeta idx : table.indexes()) {
            if ("PRIMARY".equalsIgnoreCase(idx.name())) continue;
            String cols = idx.columns().stream()
                    .map(c -> "`" + c + "`")
                    .collect(Collectors.joining(", "));
            String type = idx.unique() ? "UNIQUE KEY" : "KEY";
            lines.add("  " + type + " `" + idx.name() + "` (" + cols + ")");
        }

        sb.append(String.join(",\n", lines));
        sb.append("\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
        return sb.toString();
    }

    private String buildAddForeignKey(String tableName, ForeignKeyMeta fk) {
        String cols = fk.columnNames().stream()
                .map(c -> "`" + c + "`")
                .collect(Collectors.joining(", "));
        String refCols = fk.referencedColumnNames().stream()
                .map(c -> "`" + c + "`")
                .collect(Collectors.joining(", "));

        return "ALTER TABLE `" + tableName + "` " +
                "ADD CONSTRAINT `" + fk.name() + "` " +
                "FOREIGN KEY (" + cols + ") " +
                "REFERENCES `" + fk.referencedTable() + "` (" + refCols + ");";
    }
}
