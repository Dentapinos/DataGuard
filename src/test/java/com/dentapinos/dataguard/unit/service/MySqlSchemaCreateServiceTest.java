package com.dentapinos.dataguard.unit.service;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.*;
import com.dentapinos.dataguard.exception.DatabaseCreationException;
import com.dentapinos.dataguard.service.MySqlSchemaCreateService;
import com.dentapinos.dataguard.service.factory.JdbcTemplateFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Юнит-тесты для MySqlSchemaCreateService.
 * Проверяет логику создания баз данных и таблиц, изолированную от реальной базы данных.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-test для сервиса создания схемы MySQL")
class MySqlSchemaCreateServiceTest {

    @Mock
    private JdbcTemplateFactory jdbcTemplateFactory;

    @Mock
    private JdbcTemplateFactory.JdbcConnection connection;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Captor
    private ArgumentCaptor<String> sqlCaptor;

    private MySqlSchemaCreateService service;

    private DbCredentials dbCredentials;

    // Список для захвата SQL-операторов
    private final List<String> capturedSqls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        dbCredentials = new DbCredentials("localhost",  "user", "pass");
        service = new MySqlSchemaCreateService(jdbcTemplateFactory);
    }

    // ---------------------------
    // createDatabaseIfNotExists()
    // ---------------------------
    @Test
    @DisplayName("должен создавать базу данных, если она не существует")
    void shouldCreateDatabaseIfNotExistsWhenDatabaseDoesNotExist() {
        // arrange
        when(jdbcTemplateFactory.createServerConnection(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            return null;
        }).when(jdbcTemplate).execute(anyString());

        // act
        service.createDatabaseIfNotExists(dbCredentials, "test_db");

        // assert
        verify(connection, times(1)).close();
        verify(jdbcTemplate, times(1)).execute(sqlCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("CREATE DATABASE IF NOT EXISTS `test_db`"));
        assertTrue(sql.contains("CHARACTER SET utf8mb4"));
        assertTrue(sql.contains("COLLATE utf8mb4_unicode_ci"));
    }

    @Test
    @DisplayName("должен экранировать обратные апострофы в имени базы данных")
    void shouldEscapeBackticksInDatabaseName() {
        // arrange
        when(jdbcTemplateFactory.createServerConnection(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            return null;
        }).when(jdbcTemplate).execute(anyString());

        // act
        service.createDatabaseIfNotExists(dbCredentials, "test`db");

        // assert
        verify(jdbcTemplate).execute(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("`test``db`"));
    }

    // ---------------------------
    // createDatabaseIfNotExistsElseException()
    // ---------------------------
    @Test
    @DisplayName("должен выбрасывать исключение DatabaseCreationException, если база данных уже существует")
    void shouldThrowDatabaseCreationExceptionWhenDatabaseAlreadyExists() {
        // arrange
        when(jdbcTemplateFactory.createServerConnection(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        doAnswer(invocation -> true).when(jdbcTemplate).queryForObject(anyString(), eq(Boolean.class), eq("test_db"));

        // act + assert
        DatabaseCreationException ex = assertThrows(
                DatabaseCreationException.class,
                () -> service.createDatabaseIfNotExistsElseException(dbCredentials, "test_db")
        );

        // assert
        assertEquals("Ошибка при создании базы данных 'test_db': База данных 'test_db' уже существует!", ex.getMessage());
    }

    @Test
    @DisplayName("должен создавать базу данных и логировать успех, если она не существует")
    void shouldCreateDatabaseAndLogSuccessWhenDatabaseDoesNotExist() {
        // arrange
        when(jdbcTemplateFactory.createServerConnection(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            return null;
        }).when(jdbcTemplate).execute(anyString());
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq("new_db")))
                .thenReturn(false);

        // act
        service.createDatabaseIfNotExistsElseException(dbCredentials, "new_db");

        // assert
        verify(jdbcTemplate).execute(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("CREATE DATABASE `new_db`"));
    }

    @Test
    @DisplayName("должен оборачивать исключения в DatabaseCreationException")
    void shouldWrapExceptionsInDatabaseCreationException() {
        // arrange
        when(jdbcTemplateFactory.createServerConnection(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), any()))
                .thenThrow(new RuntimeException("Connection failed"));

        // act + assert
        DatabaseCreationException ex = assertThrows(
                DatabaseCreationException.class,
                () -> service.createDatabaseIfNotExistsElseException(dbCredentials, "test_db")
        );

        // assert
        assertTrue(ex.getMessage().contains("Ошибка при создании базы"));
        assertEquals("Connection failed", ex.getCause().getMessage());
    }

    // ---------------------------
    // createTables()
    // ---------------------------
    @Test
    @DisplayName("должен создавать таблицы в правильном порядке: сначала CREATE TABLE, потом ALTER TABLE для FK")
    void shouldCreateTablesBeforeForeignKeysWhenCreatingSchemaWithForeignKeys() {
        // arrange
        ColumnMeta col1 = new ColumnMeta("id", "BIGINT", true, true);
        ColumnMeta col2 = new ColumnMeta("name", "VARCHAR(255)", false, false);

        TableMeta user = new TableMeta("user", List.of(col1, col2), List.of("id"), List.of(), List.of());

        ColumnMeta col3 = new ColumnMeta("id", "BIGINT", true, true);
        ColumnMeta col4 = new ColumnMeta("user_id", "BIGINT", false, false);
        ForeignKeyMeta fk = new ForeignKeyMeta("fk_user_id", List.of("user_id"), "user", List.of("id"));

        TableMeta post = new TableMeta("post", List.of(col3, col4), List.of("id"), List.of(fk), List.of());

        SchemaMeta schema = new SchemaMeta("test_schema", List.of(post, user));

        when(jdbcTemplateFactory.create(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            return null;
        }).when(jdbcTemplate).execute(anyString());

        // act
        service.createTables(dbCredentials, "test_db", schema);

        // assert
        assertEquals("USE `test_db`", capturedSqls.get(0));
        assertEquals("SET FOREIGN_KEY_CHECKS = 0", capturedSqls.get(1));

        int createCount = 0, alterCount = 0;
        for (String sql : capturedSqls.subList(2, capturedSqls.size())) {
            if (sql.startsWith("CREATE TABLE")) createCount++;
            if (sql.startsWith("ALTER TABLE")) alterCount++;
        }

        assertEquals(2, createCount);
        assertEquals(1, alterCount);
        assertTrue(alterCount > 0);

        assertEquals("SET FOREIGN_KEY_CHECKS = 1", capturedSqls.get(capturedSqls.size() - 1));
    }

    @Test
    @DisplayName("должен корректно генерировать SQL CREATE TABLE с PRIMARY KEY и INDEX")
    void shouldGenerateCorrectCreateTableSqlWithPrimaryKeyAndIndex() {
        // arrange
        ColumnMeta id = new ColumnMeta("id", "BIGINT", false, true);
        ColumnMeta email = new ColumnMeta("email", "VARCHAR(255)", false, false);
        ColumnMeta created = new ColumnMeta("created_at", "DATETIME", true, false);

        IndexMeta idxEmail = new IndexMeta("idx_email", true, List.of("email"));

        TableMeta user = new TableMeta(
                "user",
                List.of(id, email, created),
                List.of("id"),
                List.of(),
                List.of(idxEmail)
        );

        SchemaMeta schema = new SchemaMeta("test", List.of(user));

        when(jdbcTemplateFactory.create(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            return null;
        }).when(jdbcTemplate).execute(anyString());

        // act
        service.createTables(dbCredentials, "test", schema);

        // assert
        verify(jdbcTemplate, atLeastOnce()).execute(anyString());
        List<String> sqls = capturedSqls;

        String createUserSql = sqls.stream()
                .filter(s -> s.startsWith("CREATE TABLE"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("CREATE TABLE не найден в: " + sqls));

        assertTrue(createUserSql.contains("`id` BIGINT NOT NULL AUTO_INCREMENT"),
                "CREATE TABLE не содержит AUTO_INCREMENT: " + createUserSql);
        assertTrue(createUserSql.contains("`email` VARCHAR(255) NOT NULL"),
                "CREATE TABLE не содержит email: " + createUserSql);
        assertTrue(createUserSql.contains("`created_at` DATETIME NULL"),
                "CREATE TABLE не содержит created_at: " + createUserSql);
        assertTrue(createUserSql.contains("PRIMARY KEY (`id`"),
                "CREATE TABLE не содержит PRIMARY KEY: " + createUserSql);
        assertTrue(createUserSql.contains("UNIQUE KEY `idx_email`") || createUserSql.contains("KEY `idx_email`"),
                "CREATE TABLE не содержит UNIQUE KEY idx_email: " + createUserSql);
        assertTrue(createUserSql.contains("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"),
                "CREATE TABLE не содержит ENGINE: " + createUserSql);
    }

    @Test
    @DisplayName("должен генерировать корректный ALTER TABLE ... ADD FOREIGN KEY")
    void shouldGenerateCorrectAlterTableAddForeignKeySql() {
        // arrange
        ColumnMeta col1 = new ColumnMeta("id", "BIGINT", true, true);
        ColumnMeta refCol = new ColumnMeta("author_id", "BIGINT", false, false);

        ForeignKeyMeta fk = new ForeignKeyMeta(
                "fk_author",
                List.of("author_id"),
                "user",
                List.of("id")
        );

        TableMeta post = new TableMeta("post", List.of(col1, refCol), List.of("id"), List.of(fk), List.of());
        SchemaMeta schema = new SchemaMeta("test", List.of(post));

        when(jdbcTemplateFactory.create(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            return null;
        }).when(jdbcTemplate).execute(anyString());

        // act
        service.createTables(dbCredentials, "test", schema);

        // assert
        String alterSql = capturedSqls.stream()
                .filter(s -> s.startsWith("ALTER TABLE"))
                .findFirst()
                .orElseThrow();

        assertEquals(
                "ALTER TABLE `post` ADD CONSTRAINT `fk_author` FOREIGN KEY (`author_id`) REFERENCES `user` (`id`);",
                alterSql
        );
    }

    @Test
    @DisplayName("должен создавать таблицы без ошибок при пустом списке внешних ключей")
    void shouldHandleTablesWithoutForeignKeysWithoutErrors() {
        // arrange
        ColumnMeta id = new ColumnMeta("id", "BIGINT", true, true);
        TableMeta emptyTable = new TableMeta("logs", List.of(id), List.of("id"), List.of(), List.of());
        SchemaMeta schema = new SchemaMeta("test", List.of(emptyTable));

        when(jdbcTemplateFactory.create(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            capturedSqls.add(sql);
            return null;
        }).when(jdbcTemplate).execute(anyString());

        // act
        service.createTables(dbCredentials, "test", schema);

        // assert
        verify(jdbcTemplate, atLeastOnce()).execute(anyString());
        assertTrue(capturedSqls.stream().anyMatch(s -> s.contains("CREATE TABLE `logs`")));
    }

    @Test
    @DisplayName("должен прерывать выполнение при ошибке в одной таблице и пробрасывать исключение")
    void shouldStopOnTableCreationErrorWhenTableHasInvalidDDL() {
        // arrange
        ColumnMeta col1 = new ColumnMeta("id", "BIGINT", true, true);
        TableMeta okTable = new TableMeta("ok", List.of(col1), List.of("id"), List.of(), List.of());

        ColumnMeta col2 = new ColumnMeta("id", "BIGINT", true, true);
        TableMeta badTable = new TableMeta("bad", List.of(col2), List.of("id"), List.of(), List.of());

        SchemaMeta schema = new SchemaMeta("test", List.of(badTable, okTable));

        when(jdbcTemplateFactory.create(dbCredentials)).thenReturn(connection);
        when(connection.getJdbcTemplate()).thenReturn(jdbcTemplate);
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("CREATE TABLE `bad`")) {
                throw new RuntimeException("Syntax error");
            }
            return null;
        }).when(jdbcTemplate).execute(anyString());

        // act
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createTables(dbCredentials, "test", schema));

        // assert
        assertEquals("Syntax error", ex.getMessage());

        verify(jdbcTemplate).execute(startsWith("USE `test`"));
        verify(jdbcTemplate).execute("SET FOREIGN_KEY_CHECKS = 0");
        verify(jdbcTemplate).execute(contains("CREATE TABLE `bad`"));

        verify(jdbcTemplate, never()).execute(contains("CREATE TABLE `ok`"));
        verify(jdbcTemplate, never()).execute(contains("FOREIGN KEY"));
        verify(jdbcTemplate).execute("SET FOREIGN_KEY_CHECKS = 1");
    }
}
