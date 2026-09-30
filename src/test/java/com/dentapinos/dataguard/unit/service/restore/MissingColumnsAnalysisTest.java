package com.dentapinos.dataguard.unit.service.restore;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.dto.MissingColumnWithDefault;
import com.dentapinos.dataguard.dto.MissingColumnsAnalysisDto;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.service.restore.DefaultFunctionResolver;
import com.dentapinos.dataguard.service.restore.SchemaCompatibilityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Unit-тесты для анализа недостающих колонок с подсказками для дефолтных значений.
 */
@DisplayName("Unit-test для анализа недостающих колонок")
@ExtendWith(MockitoExtension.class)
class MissingColumnsAnalysisTest {

    @Mock
    private DatabaseMetadataReader metadataReader;

    private SchemaCompatibilityService service;

    private DbCredentials dbCredentials;
    private String targetDatabase;

    @BeforeEach
    void setUp() {
        service = new SchemaCompatibilityService(metadataReader, new com.dentapinos.dataguard.service.restore.DefaultFunctionResolver());
        dbCredentials = new DbCredentials("jdbc:mysql://localhost:3306", "user", "pass");
        targetDatabase = "target_db";
    }

    @Test
    @DisplayName("Должен вернуть пустой список когда схемы полностью совпадают")
    void shouldReturnEmptyListWhenSchemasMatch() {
        // given
        TableMeta tableMeta = new TableMeta(
                "users",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("name", "varchar(255)", false, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        BackupFile backup = new BackupFile(
                "test_db",
                "mysql",
                new SchemaMeta("test_db", List.of(tableMeta)),
                Map.of("users", List.of(Map.of("id", 1L, "name", "Alice"))),
                List.of("users")
        );

        when(metadataReader.readSchema(dbCredentials, targetDatabase, List.of("users")))
                .thenReturn(new SchemaMeta(targetDatabase, List.of(tableMeta)));

        // when
        MissingColumnsAnalysisDto analysis = service.generateMissingColumnsAnalysis(
                dbCredentials, backup, targetDatabase);

        // then
        assertThat(analysis.missingColumns()).isEmpty();
        assertThat(analysis.tableCount()).isEqualTo(0);
        assertThat(analysis.totalMissingColumns()).isEqualTo(0);
        assertThat(analysis.userInstructions()).isNotBlank();
    }

    @Test
    @DisplayName("Должен найти колонку которая есть в БД но нет в бэкапе (nullable)")
    void shouldFindMissingNullableColumn() {
        // given - в бэкапе только id и name
        TableMeta backupTable = new TableMeta(
                "users",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("name", "varchar(255)", false, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        // в БД есть дополнительная колонка email, которая может быть NULL
        TableMeta currentTable = new TableMeta(
                "users",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("name", "varchar(255)", false, false),
                        new ColumnMeta("email", "varchar(255)", true, false)  // nullable=true
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        BackupFile backup = new BackupFile(
                "test_db",
                "mysql",
                new SchemaMeta("test_db", List.of(backupTable)),
                Map.of("users", List.of(Map.of("id", 1L, "name", "Alice"))),
                List.of("users")
        );

        when(metadataReader.readSchema(dbCredentials, targetDatabase, List.of("users")))
                .thenReturn(new SchemaMeta(targetDatabase, List.of(currentTable)));

        // when
        MissingColumnsAnalysisDto analysis = service.generateMissingColumnsAnalysis(
                dbCredentials, backup, targetDatabase);

        // then
        assertThat(analysis.missingColumns()).hasSize(1);
        MissingColumnWithDefault missingCol = analysis.missingColumns().get(0);
        assertThat(missingCol.tableName()).isEqualTo("users");
        assertThat(missingCol.columnName()).isEqualTo("email");
        assertThat(missingCol.columnType()).isEqualTo("varchar(255)");
        assertThat(missingCol.nullable()).isTrue();
        assertThat(missingCol.defaultValueHint()).contains("email");
        assertThat(missingCol.defaultValueHint()).contains("varchar(255)");
        assertThat(missingCol.defaultValueHint()).contains("NULL");
        assertThat(analysis.tableCount()).isEqualTo(1);
        assertThat(analysis.totalMissingColumns()).isEqualTo(1);
        
        // Проверяем готовый шаблон
        assertThat(analysis.readyToUseMissingFields()).containsKey("users");
        assertThat(analysis.readyToUseMissingFields().get("users")).containsEntry("email", null);
        
        // Проверяем что есть список доступных функций
        assertThat(analysis.availableFunctions()).isNotEmpty();
        assertThat(analysis.availableFunctions()).extracting(DefaultFunctionResolver.DefaultFunctionInfo::name)
                .contains("uuid", "now", "random_int");
    }

    @Test
    @DisplayName("Должен найти NOT NULL колонку без DEFAULT")
    void shouldFindMissingNotNullColumn() {
        // given
        TableMeta backupTable = new TableMeta(
                "orders",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("amount", "double", false, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        TableMeta currentTable = new TableMeta(
                "orders",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("amount", "double", false, false),
                        new ColumnMeta("status", "varchar(50)", false, false)  // NOT NULL
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        BackupFile backup = new BackupFile(
                "test_db",
                "mysql",
                new SchemaMeta("test_db", List.of(backupTable)),
                Map.of("orders", List.of(Map.of("id", 1L, "amount", 99.99))),
                List.of("orders")
        );

        when(metadataReader.readSchema(dbCredentials, targetDatabase, List.of("orders")))
                .thenReturn(new SchemaMeta(targetDatabase, List.of(currentTable)));

        // when
        MissingColumnsAnalysisDto analysis = service.generateMissingColumnsAnalysis(
                dbCredentials, backup, targetDatabase);

        // then
        assertThat(analysis.missingColumns()).hasSize(1);
        MissingColumnWithDefault missingCol = analysis.missingColumns().get(0);
        assertThat(missingCol.columnName()).isEqualTo("status");
        assertThat(missingCol.nullable()).isFalse();
        assertThat(missingCol.defaultValueHint()).contains("status");
        assertThat(missingCol.defaultValueHint()).contains("NOT NULL");
        assertThat(missingCol.defaultValueHint()).contains("Необходимо указать значение по умолчанию");
    }

    @Test
    @DisplayName("Должен распознать автоинкремент колонку")
    void shouldRecognizeAutoIncrementColumn() {
        // given
        TableMeta backupTable = new TableMeta(
                "products",
                List.of(
                        new ColumnMeta("id", "bigint", false, true)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        TableMeta currentTable = new TableMeta(
                "products",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("sort_order", "int", false, true)  // auto_increment
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        BackupFile backup = new BackupFile(
                "test_db",
                "mysql",
                new SchemaMeta("test_db", List.of(backupTable)),
                Map.of("products", List.of(Map.of("id", 1L))),
                List.of("products")
        );

        when(metadataReader.readSchema(dbCredentials, targetDatabase, List.of("products")))
                .thenReturn(new SchemaMeta(targetDatabase, List.of(currentTable)));

        // when
        MissingColumnsAnalysisDto analysis = service.generateMissingColumnsAnalysis(
                dbCredentials, backup, targetDatabase);

        // then
        assertThat(analysis.missingColumns()).hasSize(1);
        MissingColumnWithDefault missingCol = analysis.missingColumns().get(0);
        assertThat(missingCol.columnName()).isEqualTo("sort_order");
        assertThat(missingCol.defaultValueHint()).contains("автоинкремент");
        assertThat(missingCol.defaultValueHint()).contains("не нужно указывать значение");
    }

    @Test
    @DisplayName("Должен найти недостающие колонки в нескольких таблицах")
    void shouldFindMissingColumnsInMultipleTables() {
        // given
        TableMeta usersBackup = new TableMeta(
                "users",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("name", "varchar(255)", false, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        TableMeta usersCurrent = new TableMeta(
                "users",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("name", "varchar(255)", false, false),
                        new ColumnMeta("email", "varchar(255)", true, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        TableMeta ordersBackup = new TableMeta(
                "orders",
                List.of(
                        new ColumnMeta("id", "bigint", false, true)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        TableMeta ordersCurrent = new TableMeta(
                "orders",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("user_id", "bigint", false, false),
                        new ColumnMeta("total", "double", false, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        BackupFile backup = new BackupFile(
                "test_db",
                "mysql",
                new SchemaMeta("test_db", List.of(usersBackup, ordersBackup)),
                Map.of(
                        "users", List.of(Map.of("id", 1L, "name", "Alice")),
                        "orders", List.of(Map.of("id", 1L))
                ),
                List.of("users", "orders")
        );

        when(metadataReader.readSchema(
                        dbCredentials,
                        targetDatabase,
                        List.of("users", "orders")))
                .thenReturn(new SchemaMeta(targetDatabase, List.of(usersCurrent, ordersCurrent)));

        // when
        MissingColumnsAnalysisDto analysis = service.generateMissingColumnsAnalysis(
                dbCredentials, backup, targetDatabase);

        // then
        assertThat(analysis.missingColumns()).hasSize(3);
        assertThat(analysis.tableCount()).isEqualTo(2);
        assertThat(analysis.totalMissingColumns()).isEqualTo(3);

        // Проверяем что есть колонки из обеих таблиц
        List<String> missingColumnNames = analysis.missingColumns().stream()
                .map(MissingColumnWithDefault::columnName)
                .toList();
        assertThat(missingColumnNames).containsExactlyInAnyOrder("email", "user_id", "total");
    }

    @Test
    @DisplayName("Должен игнорировать таблицы которых нет в бэкапе")
    void shouldIgnoreTablesNotInBackup() {
        // given - в бэкапе только users, но в БД есть ещё products с отличиями
        TableMeta usersBackup = new TableMeta(
                "users",
                List.of(
                        new ColumnMeta("id", "bigint", false, true)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        TableMeta usersCurrent = new TableMeta(
                "users",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("name", "varchar(255)", true, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        // products есть только в БД, её не должно быть в анализе
        TableMeta productsCurrent = new TableMeta(
                "products",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("missing_col", "varchar(255)", true, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        BackupFile backup = new BackupFile(
                "test_db",
                "mysql",
                new SchemaMeta("test_db", List.of(usersBackup)),
                Map.of("users", List.of(Map.of("id", 1L))),
                List.of("users")
        );

        when(metadataReader.readSchema(dbCredentials, targetDatabase, List.of("users")))
                .thenReturn(new SchemaMeta(targetDatabase, List.of(usersCurrent)));

        // when
        MissingColumnsAnalysisDto analysis = service.generateMissingColumnsAnalysis(
                dbCredentials, backup, targetDatabase);

        // then - products не должна быть в анализе, т.к. её нет в бэкапе
        assertThat(analysis.missingColumns()).hasSize(1);
        assertThat(analysis.missingColumns().get(0).columnName()).isEqualTo("name");
    }

    @Test
    @DisplayName("Должен сгенерировать readyToUseMissingFields с правильными типами значений")
    void shouldGenerateReadyToUseMissingFieldsWithCorrectTypes() {
        // given
        TableMeta backupTable = new TableMeta(
                "test_table",
                List.of(
                        new ColumnMeta("id", "bigint", false, true)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        TableMeta currentTable = new TableMeta(
                "test_table",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("name", "varchar(255)", true, false),  // nullable string
                        new ColumnMeta("count", "int", false, false),          // NOT NULL int
                        new ColumnMeta("created_at", "datetime(6)", false, false),  // NOT NULL datetime
                        new ColumnMeta("status", "enum('ACTIVATED','EXPIRED','PENDING')", false, false),  // enum
                        new ColumnMeta("active", "boolean", false, false)      // NOT NULL boolean
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        BackupFile backup = new BackupFile(
                "test_db",
                "mysql",
                new SchemaMeta("test_db", List.of(backupTable)),
                Map.of("test_table", List.of(Map.of("id", 1L))),
                List.of("test_table")
        );

        when(metadataReader.readSchema(dbCredentials, targetDatabase, List.of("test_table")))
                .thenReturn(new SchemaMeta(targetDatabase, List.of(currentTable)));

        // when
        MissingColumnsAnalysisDto analysis = service.generateMissingColumnsAnalysis(
                dbCredentials, backup, targetDatabase);

        // then
        assertThat(analysis.readyToUseMissingFields()).containsKey("test_table");
        
        Map<String, Object> defaults = analysis.readyToUseMissingFields().get("test_table");
        assertThat(defaults).containsEntry("name", null);  // nullable → null
        assertThat(defaults).containsEntry("count", 0);  // int → 0
        assertThat(defaults).containsEntry("created_at", "");  // datetime → ""
        assertThat(defaults).containsEntry("status", "ACTIVATED");  // enum → first value
        assertThat(defaults).containsEntry("active", false);  // boolean → false
    }
}
