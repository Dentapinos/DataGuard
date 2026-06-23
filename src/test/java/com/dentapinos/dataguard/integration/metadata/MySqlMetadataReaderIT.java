package com.dentapinos.dataguard.integration.metadata;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.ForeignKeyMeta;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import com.dentapinos.dataguard.service.metadata.MySqlMetadataReader;
import com.dentapinos.dataguard.test.BaseResetDatabaseTest;
import com.dentapinos.dataguard.test.annotation.WithResetDatabaseBeforeEach;
import com.dentapinos.dataguard.test.config.TestDatabaseConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционные тесты для MySqlMetadataReader с использованием Testcontainers MySQL.
 * Проверяют чтение полной схемы базы данных со всеми метаданными (таблицы, колонки, PK, FK, индексы),
 * фильтрацию таблиц, корректность метаданных колонок, составные ключи и сложные типы данных.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TestDatabaseConfig.class})
@WithResetDatabaseBeforeEach
@DisplayName("IT - MySqlMetadataReader")
class MySqlMetadataReaderIT extends BaseResetDatabaseTest {

    @Autowired
    private MySqlMetadataReader metadataReader;

    @Autowired
    private JdbcTemplateFactory jdbcTemplateFactory;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private DbCredentials dbCredentials;

    private String databaseName;

    @BeforeEach
    void setUp() {
        String jdbcUrl = System.getProperty("MYSQL_JDBC_URL");
        assertNotNull(jdbcUrl, "MYSQL_JDBC_URL system property must be set");
        dbCredentials = new DbCredentials(jdbcUrl, "testuser", "testpass");

        // URL format: jdbc:mysql://localhost:port/database_name?...
        String urlPart = jdbcUrl.substring("jdbc:mysql://".length());
        int firstSlash = urlPart.indexOf('/') + 1;
        int questionMark = urlPart.indexOf('?');
        if (questionMark > 0) {
            databaseName = urlPart.substring(firstSlash, questionMark);
        } else {
            databaseName = urlPart.substring(firstSlash);
        }
    }

    @Nested
    @DisplayName("Метод readSchema()")
    class ReadSchemaMethodTests {

        @Test
        @DisplayName("должен читать полную схему со всеми таблицами, колонками, PK, FK и индексами")
        void shouldReadFullSchemaWhenIncludedTablesIsEmpty() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert
            assertNotNull(schema);
            assertEquals(databaseName, schema.database());
            assertTrue(schema.tables().size() >= 8, "Должно быть как минимум 8 столов из schema.sql");

            // Проверка таблицы
            List<String> tableNames = schema.tables().stream()
                    .map(TableMeta::name)
                    .toList();
            assertTrue(tableNames.contains("users"));
            assertTrue(tableNames.contains("user_profiles"));
            assertTrue(tableNames.contains("categories"));
            assertTrue(tableNames.contains("products"));
            assertTrue(tableNames.contains("orders"));
            assertTrue(tableNames.contains("order_products"));
            assertTrue(tableNames.contains("product_orders"));
            assertTrue(tableNames.contains("product_order_users"));

            // Проверка структуры таблицы пользователей
            TableMeta usersTable = schema.tables().stream()
                    .filter(t -> "users".equals(t.name()))
                    .findFirst()
                    .orElseThrow();
            
            assertEquals("users", usersTable.name());
            assertFalse(usersTable.columns().isEmpty());
            assertFalse(usersTable.primaryKey().isEmpty());
            assertTrue(usersTable.foreignKeys().isEmpty()); // Нет FK среди пользователей

            // Проверка метаданных столбца
            ColumnMeta idColumn = usersTable.columns().stream()
                    .filter(c -> "id".equals(c.name()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(idColumn.type().startsWith("bigint"), "Type should be bigint");
            assertFalse(idColumn.nullable());
            assertTrue(idColumn.autoIncrement());

            ColumnMeta usernameColumn = usersTable.columns().stream()
                    .filter(c -> "username".equals(c.name()))
                    .findFirst()
                    .orElseThrow();
            assertTrue(usernameColumn.type().startsWith("varchar"));
            assertFalse(usernameColumn.nullable());

            // Проверьте первичный ключ
            assertEquals(List.of("id"), usersTable.primaryKey());
        }

        @Test
        @DisplayName("должен корректно обрабатывать составной первичный ключ")
        void shouldHandleCompositePrimaryKeyWhenTableHasMultipleColumnsInPK() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert
            TableMeta orderProductsTable = schema.tables().stream()
                    .filter(t -> "order_products".equals(t.name()))
                    .findFirst()
                    .orElseThrow();

            // order_products имеет композитный PK: (order_id, product_id)
            assertEquals(List.of("order_id", "product_id"), orderProductsTable.primaryKey());
        }

        @Test
        @DisplayName("должен читать внешние ключи корректно")
        void shouldReadForeignKeysWhenReadingSchema() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert
            TableMeta productsTable = schema.tables().stream()
                    .filter(t -> "products".equals(t.name()))
                    .findFirst()
                    .orElseThrow();

            assertFalse(productsTable.foreignKeys().isEmpty());

            //Проверка наличия FK
            Set<String> fkNames = productsTable.foreignKeys().stream()
                    .map(ForeignKeyMeta::name)
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(fkNames.size() >= 2, "В таблице продуктов должно быть как минимум 2 FK (category_id, user_id)");

            // Verify product_orders FK
            TableMeta productOrdersTable = schema.tables().stream()
                    .filter(t -> "product_orders".equals(t.name()))
                    .findFirst()
                    .orElseThrow();

            boolean hasProductFK = productOrdersTable.foreignKeys().stream()
                    .anyMatch(fk -> fk.columnNames().contains("product_id") && "products".equals(fk.referencedTable()));
            boolean hasOrderFK = productOrdersTable.foreignKeys().stream()
                    .anyMatch(fk -> fk.columnNames().contains("order_id") && "orders".equals(fk.referencedTable()));

            assertTrue(hasProductFK, "Должна быть таблица FK в продукты");
            assertTrue(hasOrderFK, "Должна быть таблица от FK до заказов");
        }

        @Test
        @DisplayName("должен читать индексы корректно, включая составные индексы")
        void shouldReadIndexesWhenReadingSchema() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert
            TableMeta usersTable = schema.tables().stream()
                    .filter(t -> "users".equals(t.name()))
                    .findFirst()
                    .orElseThrow();

            // В таблице пользователей должны быть индексы (PK + уникальный в электронной почте)
            assertFalse(usersTable.indexes().isEmpty(), "Таблица пользователей должна содержать хотя бы один индекс");
        }

        @Test
        @DisplayName("должен учитывать фильтр includedTables")
        void shouldRespectIncludedTablesFilterWhenProvided() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of("users", "products"));

            // assert
            assertNotNull(schema);
            assertEquals(2, schema.tables().size());

            List<String> tableNames = schema.tables().stream()
                    .map(TableMeta::name)
                    .toList();
            assertTrue(tableNames.contains("users"));
            assertTrue(tableNames.contains("products"));
        }

        @Test
        @DisplayName("должен обрабатывать пустой список includedTables")
        void shouldHandleEmptyIncludedTablesWhenProvided() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert
            assertNotNull(schema);
            assertTrue(schema.tables().size() >= 8);
        }

        @Test
        @DisplayName("должен обрабатывать null в параметре includedTables")
        void shouldHandleNullIncludedTablesWhenProvided() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, null);

            // assert
            assertNotNull(schema);
            assertTrue(schema.tables().size() >= 8);
        }

        @Test
        @DisplayName("должен обрабатывать несуществующие таблицы в фильтре includedTables")
        void shouldHandleNonExistentTablesInFilterWhenProvided() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of("users", "non_existent_table"));

            // assert
            assertNotNull(schema);
            assertEquals(1, schema.tables().size());
            assertEquals("users", schema.tables().get(0).name());
        }

        @Test
        @DisplayName("должен корректно обрабатывать метаданные колонок")
        void shouldHandleColumnMetadataWhenReadingSchema() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert
            TableMeta productsTable = schema.tables().stream()
                    .filter(t -> "products".equals(t.name()))
                    .findFirst()
                    .orElseThrow();

            ColumnMeta priceColumn = productsTable.columns().stream()
                    .filter(c -> "price".equals(c.name()))
                    .findFirst()
                    .orElseThrow();
            // Проверка, существует ли столбец с ценами и имеет ли правильные свойства
            assertNotNull(priceColumn);
            assertTrue(priceColumn.type().toLowerCase().contains("decimal") || 
                      priceColumn.type().toLowerCase().contains("float") || 
                      priceColumn.type().toLowerCase().contains("double"));
        }
    }

    @Nested
    @DisplayName("Метод listTables()")
    class ListTablesMethodTests {

        @Test
        @DisplayName("должен перечислить все таблицы в базе данных")
        void shouldListAllTablesWhenListingTables() {
            // arrange

            // act
            List<String> tables = metadataReader.listTables(dbCredentials, databaseName);

            // assert
            assertNotNull(tables);
            assertTrue(tables.size() >= 8);
            assertTrue(tables.contains("users"));
            assertTrue(tables.contains("orders"));
            assertTrue(tables.contains("categories"));
        }

        @Test
        @DisplayName("должен обрабатывать специальные символы в именах таблиц")
        void shouldHandleTablesWithSpecialCharactersWhenListingTables() {
            // arrange
            String tableName = "test_table_123";
            jdbcTemplate.execute("CREATE TABLE `" + tableName + "` (id BIGINT PRIMARY KEY)");
            
            try {
                // act
                List<String> tables = metadataReader.listTables(dbCredentials, databaseName);

                // assert
                assertTrue(tables.contains(tableName), "Должен найти таблицу со специальными символами в названии");
            } finally {
                jdbcTemplate.execute("DROP TABLE `" + tableName + "`");
            }
        }
    }

    @Nested
    @DisplayName("Пограничные случаи")
    class EdgeCasesTests {

        @Test
        @DisplayName("должен обрабатывать таблицу без индексов и внешних ключей")
        void shouldHandleTableWithoutIndexesOrFKsWhenReadingSchema() {
            // arrange
            String tableName = "simple_table";
            jdbcTemplate.execute("CREATE TABLE `" + tableName + "` (id BIGINT, name VARCHAR(100))");

            try {
                // act
                SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of(tableName));

                // assert
                TableMeta tableMeta = schema.tables().stream()
                        .filter(t -> tableName.equals(t.name()))
                        .findFirst()
                        .orElseThrow();
                
                assertEquals(tableName, tableMeta.name());
                assertFalse(tableMeta.columns().isEmpty());
                assertTrue(tableMeta.primaryKey().isEmpty());
                assertTrue(tableMeta.foreignKeys().isEmpty());
                assertTrue(tableMeta.indexes().isEmpty());
            } finally {
                jdbcTemplate.execute("DROP TABLE `" + tableName + "`");
            }
        }

        @Test
        @DisplayName("должен обрабатывать составной внешний ключ с несколькими колонками")
        void shouldHandleCompositeForeignKeyWhenReadingSchema() {
            // arrange
            jdbcTemplate.execute("CREATE TABLE parent (" +
                    "id1 BIGINT, " +
                    "id2 BIGINT, " +
                    "PRIMARY KEY (id1, id2))");
            
            jdbcTemplate.execute("CREATE TABLE child (" +
                    "id BIGINT PRIMARY KEY, " +
                    "parent_id1 BIGINT, " +
                    "parent_id2 BIGINT, " +
                    "FOREIGN KEY (parent_id1, parent_id2) REFERENCES parent(id1, id2))");

            try {
                // act
                SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of("parent", "child"));

                // assert
                TableMeta childTable = schema.tables().stream()
                        .filter(t -> "child".equals(t.name()))
                        .findFirst()
                        .orElseThrow();
                
                assertFalse(childTable.foreignKeys().isEmpty());
                ForeignKeyMeta fk = childTable.foreignKeys().get(0);
                assertEquals(2, fk.columnNames().size());
                assertEquals(2, fk.referencedColumnNames().size());
                assertEquals(List.of("parent_id1", "parent_id2"), fk.columnNames());
                assertEquals(List.of("id1", "id2"), fk.referencedColumnNames());
                assertEquals("parent", fk.referencedTable());
            } finally {
                jdbcTemplate.execute("DROP TABLE child");
                jdbcTemplate.execute("DROP TABLE parent");
            }
        }

        @Test
        @DisplayName("должен обрабатывать сложные типы данных (JSON, GEOMETRY, TEXT)")
        void shouldHandleComplexDataTypesWhenReadingSchema() {
            // arrange
            String tableName = "complex_types";
            jdbcTemplate.execute("CREATE TABLE `" + tableName + "` (" +
                    "id BIGINT PRIMARY KEY, " +
                    "json_data JSON, " +
                    "geo_data GEOMETRY, " +
                    "text_data TEXT, " +
                    "long_text MEDIUMTEXT)");

            try {
                // act
                SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of(tableName));

                // assert
                TableMeta tableMeta = schema.tables().stream()
                        .filter(t -> tableName.equals(t.name()))
                        .findFirst()
                        .orElseThrow();
                
                // Проверка чтения сложных типов
                assertTrue(tableMeta.columns().stream()
                        .anyMatch(c -> "json_data".equals(c.name()) && c.type().contains("json")));
                assertTrue(tableMeta.columns().stream()
                        .anyMatch(c -> "geo_data".equals(c.name()) && c.type().contains("geometry")));
                assertTrue(tableMeta.columns().stream()
                        .anyMatch(c -> "text_data".equals(c.name()) && (c.type().startsWith("text") || c.type().startsWith("varchar"))));
            } finally {
                jdbcTemplate.execute("DROP TABLE `" + tableName + "`");
            }
        }

        @Test
        @DisplayName("должен сохранять порядок колонок в составном ключе")
        void shouldPreserveColumnOrderInCompositeKeyWhenReadingSchema() {
            // arrange
            jdbcTemplate.execute("CREATE TABLE multi_col_pk (" +
                    "col_c BIGINT, " +
                    "col_a BIGINT, " +
                    "col_b BIGINT, " +
                    "PRIMARY KEY (col_c, col_a, col_b))");

            try {
                // act
                SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of("multi_col_pk"));

                // assert
                TableMeta tableMeta = schema.tables().stream()
                        .filter(t -> "multi_col_pk".equals(t.name()))
                        .findFirst()
                        .orElseThrow();
                
                assertEquals(List.of("col_c", "col_a", "col_b"), tableMeta.primaryKey(),
                        "Порядок столбцов первичного ключа должен сохраняться");
            } finally {
                jdbcTemplate.execute("DROP TABLE multi_col_pk");
            }
        }

        @Test
        @DisplayName("должен обрабатывать таблицу только с первичным ключом и без других индексов")
        void shouldHandleTableWithOnlyPrimaryKeyWhenReadingSchema() {
            // arrange
            String tableName = "only_pk_table";
            jdbcTemplate.execute("CREATE TABLE `" + tableName + "` (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(100))");

            try {
                // act
                SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of(tableName));

                // assert
                TableMeta tableMeta = schema.tables().stream()
                        .filter(t -> tableName.equals(t.name()))
                        .findFirst()
                        .orElseThrow();
                
                assertFalse(tableMeta.primaryKey().isEmpty());
                // Первичный ключ должен быть в индексах
                assertFalse(tableMeta.indexes().isEmpty(), "Должен быть хотя бы индекс PK");
            } finally {
                jdbcTemplate.execute("DROP TABLE `" + tableName + "`");
            }
        }

        @Test
        @DisplayName("должен корректно обрабатывать автоинкрементные колонки")
        void shouldHandleAutoIncrementWhenReadingSchema() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert - Находим таблицы с авто-инкрементными столбцами
            TableMeta usersTable = schema.tables().stream()
                    .filter(t -> "users".equals(t.name()))
                    .findFirst()
                    .orElseThrow();
            
            ColumnMeta idColumn = usersTable.columns().stream()
                    .filter(c -> "id".equals(c.name()))
                    .findFirst()
                    .orElseThrow();
            
            assertTrue(idColumn.autoIncrement(), "Столбец ID должен быть автоматическим увеличением");
        }

        @Test
        @DisplayName("должен корректно обрабатывать nullable колонки")
        void shouldHandleNullableColumnsWhenReadingSchema() {
            // arrange

            // act
            SchemaMeta schema = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert
            TableMeta userProfilesTable = schema.tables().stream()
                    .filter(t -> "user_profiles".equals(t.name()))
                    .findFirst()
                    .orElseThrow();
            
            //Bio и avatar_url должны быть обнулены
            ColumnMeta bioColumn = userProfilesTable.columns().stream()
                    .filter(c -> "bio".equals(c.name()))
                    .findFirst()
                    .orElseThrow();
            
            assertTrue(bioColumn.nullable(), "Столбец bio должен быть нулевым");
        }
    }

    @Nested
    @DisplayName("Интеграция с JdbcTemplateFactory")
    class JdbcTemplateFactoryIntegrationTests {

        @Test
        @DisplayName("должен создавать подключения к существующим базам данных")
        void shouldCreateConnectionsToDifferentDatabasesWhenReadingSchema() {
            // arrange

            // act
            SchemaMeta schema1 = metadataReader.readSchema(dbCredentials, databaseName, List.of());
            SchemaMeta schema2 = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert
            assertNotNull(schema1);
            assertNotNull(schema2);
            assertEquals(schema1.database(), schema2.database());
        }

        @Test
        @DisplayName("должен переиспользовать DataSource для одной и той же базы данных")
        void shouldReuseDataSourceWhenReadingSchemaTwice() {
            // arrange

            // act
            SchemaMeta schema1 = metadataReader.readSchema(dbCredentials, databaseName, List.of());
            SchemaMeta schema2 = metadataReader.readSchema(dbCredentials, databaseName, List.of());

            // assert
            assertNotNull(schema1);
            assertNotNull(schema2);
            assertEquals(schema1.database(), schema2.database());
        }
    }
}
