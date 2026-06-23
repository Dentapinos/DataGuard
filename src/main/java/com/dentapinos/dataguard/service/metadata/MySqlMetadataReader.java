package com.dentapinos.dataguard.service.metadata;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.*;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MySqlMetadataReader implements DatabaseMetadataReader {

    private final JdbcTemplateFactory jdbcTemplateFactory;

    @Override
    public SchemaMeta readSchema(
            DbCredentials dbCredentials,
            String database,
            List<String> includedTables
    ) {
        try (JdbcTemplateFactory.JdbcConnection conn = jdbcTemplateFactory.forDatabase(dbCredentials, database)) {
            JdbcTemplate jdbcTemplate = conn.getJdbcTemplate();
            List<String> tables = getTables(jdbcTemplate, database, includedTables);
            Map<String, List<ColumnMeta>> columns = getColumns(jdbcTemplate, database, tables);
            Map<String, List<String>> pk = getPrimaryKeys(jdbcTemplate, database, tables);
            Map<String, List<IndexMeta>> indexes = getIndexes(jdbcTemplate, database, tables);
            Map<String, List<ForeignKeyMeta>> fks = getForeignKeys(jdbcTemplate, database, tables);

            List<TableMeta> tableMetas = new ArrayList<>();
            for (String table : tables) {
                tableMetas.add(new TableMeta(
                        table,
                        columns.getOrDefault(table, List.of()),
                        pk.getOrDefault(table, List.of()),
                        fks.getOrDefault(table, List.of()),
                        indexes.getOrDefault(table, List.of())
                ));
            }
            return new SchemaMeta(database, tableMetas);
        } catch (DataAccessException ex) {
            log.error(ex.getMessage(), ex);
            throw new RuntimeException("Ошибка доступа к базе данных с именем " + database + ", она имеет неправильные параметры доступа", ex);
        } catch (Exception e) {
            throw new RuntimeException("Ошибка при считывании схемы", e);
        }
    }

    @Override
    public List<String> listTables(DbCredentials credentials, String dbName) {
        try (JdbcTemplateFactory.JdbcConnection conn = jdbcTemplateFactory.forDatabase(credentials, dbName)) {
            return getTables(conn.getJdbcTemplate(), dbName, List.of());
        }
    }

    private List<String> getTables(JdbcTemplate jdbcTemplate, String db, List<String> includedTables) {
        String sql = "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ?";
        List<String> all;
        try{
            all = jdbcTemplate.queryForList(sql, String.class, db);
        } catch (DataAccessException e) {
            log.error("Ошибка при попытке подключится к базе {}, вероятно неправильные настройки базы", db);
            throw e;
        }
        if (includedTables == null || includedTables.isEmpty()) {
            return all;
        }
        // Сохраняем порядок из includedTables
        return includedTables.stream()
                .filter(all::contains)
                .toList();
    }

    private Map<String, List<ColumnMeta>> getColumns(JdbcTemplate jdbcTemplate, String db, List<String> tables) {
        if (tables.isEmpty()) return Map.of();
        String inClause = String.join(",", Collections.nCopies(tables.size(), "?"));
        String sql = """
            SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, EXTRA
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ?
              AND TABLE_NAME IN (%s)
            ORDER BY TABLE_NAME, ORDINAL_POSITION
            """.formatted(inClause);

        List<Object> params = new ArrayList<>();
        params.add(db);
        params.addAll(tables);

        Map<String, List<ColumnMeta>> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String table = rs.getString("TABLE_NAME");
            String name = rs.getString("COLUMN_NAME");
            String type = rs.getString("COLUMN_TYPE");
            boolean nullable = "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE"));
            boolean autoInc = rs.getString("EXTRA") != null &&
                    rs.getString("EXTRA").toLowerCase().contains("auto_increment");

            result.computeIfAbsent(table, k -> new ArrayList<>())
                    .add(new ColumnMeta(name, type, nullable, autoInc));
        }, params.toArray());

        return result;
    }

    private Map<String, List<String>> getPrimaryKeys(JdbcTemplate jdbcTemplate, String db, List<String> tables) {
        if (tables.isEmpty()) return Map.of();
        String inClause = String.join(",", Collections.nCopies(tables.size(), "?"));
        String sql = """
            SELECT TABLE_NAME, COLUMN_NAME, SEQ_IN_INDEX
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = ?
              AND TABLE_NAME IN (%s)
              AND INDEX_NAME = 'PRIMARY'
            ORDER BY TABLE_NAME, SEQ_IN_INDEX
            """.formatted(inClause);

        List<Object> params = new ArrayList<>();
        params.add(db);
        params.addAll(tables);

        Map<String, List<String>> result = new HashMap<>();
        jdbcTemplate.query(sql, rs -> {
            String table = rs.getString("TABLE_NAME");
            String column = rs.getString("COLUMN_NAME");
            result.computeIfAbsent(table, k -> new ArrayList<>()).add(column);
        }, params.toArray());
        return result;
    }

    private Map<String, List<IndexMeta>> getIndexes(JdbcTemplate jdbcTemplate, String db, List<String> tables) {
        if (tables.isEmpty()) return Map.of();
        String inClause = String.join(",", Collections.nCopies(tables.size(), "?"));
        String sql = """
            SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = ?
              AND TABLE_NAME IN (%s)
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """.formatted(inClause);

        List<Object> params = new ArrayList<>();
        params.add(db);
        params.addAll(tables);

        Map<String, Map<String, IndexMetaBuilder>> tmp = new HashMap<>();

        jdbcTemplate.query(sql, rs -> {
            String table = rs.getString("TABLE_NAME");
            String indexName = rs.getString("INDEX_NAME");
            boolean unique = rs.getInt("NON_UNIQUE") == 0;
            String column = rs.getString("COLUMN_NAME");

            tmp.computeIfAbsent(table, t -> new HashMap<>())
                    .computeIfAbsent(indexName, n -> new IndexMetaBuilder(indexName, unique))
                    .columns.add(column);
        }, params.toArray());

        Map<String, List<IndexMeta>> result = new HashMap<>();
        tmp.forEach((table, map) -> {
            List<IndexMeta> list = new ArrayList<>();
            map.values().forEach(b ->
                    list.add(new IndexMeta(b.name, b.unique, List.copyOf(b.columns))));
            result.put(table, list);
        });

        return result;
    }

    private Map<String, List<ForeignKeyMeta>> getForeignKeys(JdbcTemplate jdbcTemplate, String db, List<String> tables) {
        if (tables.isEmpty()) return Map.of();
        String inClause = String.join(",", Collections.nCopies(tables.size(), "?"));
        String sql = """
            SELECT kcu.TABLE_NAME,
                   kcu.CONSTRAINT_NAME,
                   kcu.COLUMN_NAME,
                   kcu.REFERENCED_TABLE_NAME,
                   kcu.REFERENCED_COLUMN_NAME,
                   kcu.POSITION_IN_UNIQUE_CONSTRAINT
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
            WHERE kcu.TABLE_SCHEMA = ?
              AND kcu.TABLE_NAME IN (%s)
              AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
            ORDER BY kcu.TABLE_NAME, kcu.CONSTRAINT_NAME, kcu.POSITION_IN_UNIQUE_CONSTRAINT
            """.formatted(inClause);

        List<Object> params = new ArrayList<>();
        params.add(db);
        params.addAll(tables);

        Map<String, Map<String, FkBuilder>> tmp = new HashMap<>();

        jdbcTemplate.query(sql, rs -> {
            String table = rs.getString("TABLE_NAME");
            String name = rs.getString("CONSTRAINT_NAME");
            String col = rs.getString("COLUMN_NAME");
            String refTable = rs.getString("REFERENCED_TABLE_NAME");
            String refCol = rs.getString("REFERENCED_COLUMN_NAME");

            tmp.computeIfAbsent(table, t -> new HashMap<>())
                    .computeIfAbsent(name, n -> new FkBuilder(name, refTable))
                    .add(col, refCol);
        }, params.toArray());

        Map<String, List<ForeignKeyMeta>> result = new HashMap<>();
        tmp.forEach((table, map) -> {
            List<ForeignKeyMeta> list = new ArrayList<>();
            map.values().forEach(b ->
                    list.add(new ForeignKeyMeta(b.name,
                            List.copyOf(b.columns),
                            b.referencedTable,
                            List.copyOf(b.referencedColumns))));
            result.put(table, list);
        });

        return result;
    }

    @Data
    private static class IndexMetaBuilder {
        final String name;
        final boolean unique;
        final List<String> columns = new ArrayList<>();
        IndexMetaBuilder(String name, boolean unique) {
            this.name = name;
            this.unique = unique;
        }
    }

    private static class FkBuilder {
        final String name;
        final String referencedTable;
        final List<String> columns = new ArrayList<>();
        final List<String> referencedColumns = new ArrayList<>();
        FkBuilder(String name, String referencedTable) {
            this.name = name;
            this.referencedTable = referencedTable;
        }
        void add(String col, String refCol) {
            columns.add(col);
            referencedColumns.add(refCol);
        }
    }
}
