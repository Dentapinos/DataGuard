package com.dentapinos.dataguard.unit.service.restore;

import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.service.restore.RestoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit-тесты для применения значений по умолчанию (missingFields) при восстановлении.
 */
@DisplayName("Unit-test для применения missingFields")
@ExtendWith(MockitoExtension.class)
class MissingFieldsApplyTest {

    private RestoreService restoreService;

    @BeforeEach
    void setUp() {
        // Создаём RestoreService с моками — нам нужен только приватный метод applyMissingFieldsDefaults
        restoreService = new RestoreService(
                mock(com.dentapinos.dataguard.service.restore.RestorePolicyService.class),
                mock(com.dentapinos.dataguard.service.restore.strategy.RestoreStrategyFactory.class),
                mock(com.dentapinos.dataguard.storage.BackupStorage.class),
                mock(com.dentapinos.dataguard.storage.BackupFileNamingService.class),
                mock(com.dentapinos.dataguard.storage.BackupFileReader.class),
                mock(com.dentapinos.dataguard.service.restore.SchemaCompatibilityService.class),
                mock(com.dentapinos.dataguard.service.restore.order.RestoreOrderService.class),
                new com.dentapinos.dataguard.service.restore.DefaultFunctionResolver()
        );
    }

    @Test
    @DisplayName("Должен вернуть оригинальный бэкап когда missingFields пустой")
    void shouldReturnOriginalBackupWhenMissingFieldsEmpty() {
        // given
        BackupFile backup = createBackupWithUsers();

        // when
        BackupFile result = invokeApplyDefaults(backup, null);

        // then
        assertThat(result).isSameAs(backup);
    }

    @Test
    @DisplayName("Должен добавить недостающие поля в строки таблицы")
    void shouldAddMissingFieldsToRows() {
        // given
        BackupFile backup = createBackupWithUsers();
        Map<String, Map<String, Object>> missingFields = Map.of(
                "users", Map.of(
                        "address", "NO ADDRESS",
                        "date", "2028-08-09"
                )
        );

        // when
        BackupFile result = invokeApplyDefaults(backup, missingFields);

        // then
        List<Map<String, Object>> rows = result.data().get("users");
        assertThat(rows).hasSize(2);

        Map<String, Object> row1 = rows.get(0);
        assertThat(row1).containsEntry("id", 1L);
        assertThat(row1).containsEntry("name", "Alice");
        assertThat(row1).containsEntry("address", "NO ADDRESS");
        assertThat(row1).containsEntry("date", "2028-08-09");

        Map<String, Object> row2 = rows.get(1);
        assertThat(row2).containsEntry("id", 2L);
        assertThat(row2).containsEntry("name", "Bob");
        assertThat(row2).containsEntry("address", "NO ADDRESS");
        assertThat(row2).containsEntry("date", "2028-08-09");
    }

    @Test
    @DisplayName("Должен не перезаписывать существующие значения")
    void shouldNotOverwriteExistingValues() {
        // given
        BackupFile backup = createBackupWithUsers();
        Map<String, Map<String, Object>> missingFields = Map.of(
                "users", Map.of(
                        "name", "OVERRIDE",  // это поле уже есть в бэкапе
                        "address", "NEW YORK"
                )
        );

        // when
        BackupFile result = invokeApplyDefaults(backup, missingFields);

        // then
        List<Map<String, Object>> rows = result.data().get("users");
        assertThat(rows.get(0).get("name")).isEqualTo("Alice");  // не перезаписано
        assertThat(rows.get(0).get("address")).isEqualTo("NEW YORK");  // добавлено
        assertThat(rows.get(1).get("name")).isEqualTo("Bob");  // не перезаписано
    }

    @Test
    @DisplayName("Должен добавить дефолты для нескольких таблиц")
    void shouldAddDefaultsForMultipleTables() {
        // given
        BackupFile backup = createBackupWithUsersAndOrders();
        Map<String, Map<String, Object>> missingFields = Map.of(
                "users", Map.of("address", "NO ADDRESS"),
                "orders", Map.of("status", "PENDING")
        );

        // when
        BackupFile result = invokeApplyDefaults(backup, missingFields);

        // then
        assertThat(result.data().get("users").get(0)).containsEntry("address", "NO ADDRESS");
        assertThat(result.data().get("orders").get(0)).containsEntry("status", "PENDING");
    }

    @Test
    @DisplayName("Должен игнорировать таблицы без дефолтов")
    void shouldIgnoreTablesWithoutDefaults() {
        // given
        BackupFile backup = createBackupWithUsersAndOrders();
        Map<String, Map<String, Object>> missingFields = Map.of(
                "users", Map.of("address", "NO ADDRESS")
                // orders не имеет дефолтов
        );

        // when
        BackupFile result = invokeApplyDefaults(backup, missingFields);

        // then
        assertThat(result.data().get("users").get(0)).containsEntry("address", "NO ADDRESS");
        assertThat(result.data().get("orders").get(0)).doesNotContainKey("status");
    }

    @Test
    @DisplayName("Должен работать с разными типами значений")
    void shouldHandleDifferentValueTypes() {
        // given
        BackupFile backup = createBackupWithUsers();
        Map<String, Object> userDefaults = new java.util.HashMap<>();
        userDefaults.put("title", 0);  // Integer
        userDefaults.put("active", true);  // Boolean
        userDefaults.put("score", 99.5);  // Double
        userDefaults.put("code", null);  // null value
        
        Map<String, Map<String, Object>> missingFields = Map.of(
                "users", userDefaults
        );

        // when
        BackupFile result = invokeApplyDefaults(backup, missingFields);

        // then
        Map<String, Object> row = result.data().get("users").get(0);
        assertThat(row.get("title")).isEqualTo(0);
        assertThat(row.get("active")).isEqualTo(true);
        assertThat(row.get("score")).isEqualTo(99.5);
        assertThat(row.get("code")).isNull();
    }

    @Test
    @DisplayName("Должен разрешать плейсхолдеры #uuid, #now, #random_int")
    void shouldResolvePlaceholders() {
        // given
        BackupFile backup = createBackupWithUsers();
        Map<String, Object> userDefaults = new java.util.HashMap<>();
        userDefaults.put("uuid", "#uuid");
        userDefaults.put("created_at", "#now");
        userDefaults.put("random_id", "#random_int");
        
        Map<String, Map<String, Object>> missingFields = Map.of(
                "users", userDefaults
        );

        // when
        BackupFile result = invokeApplyDefaults(backup, missingFields);

        // then
        Map<String, Object> row = result.data().get("users").get(0);
        
        // UUID должен быть валидным
        assertThat(row.get("uuid")).isInstanceOf(String.class);
        String uuid = (String) row.get("uuid");
        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        
        // created_at должен быть строкой с датой
        assertThat(row.get("created_at")).isInstanceOf(String.class);
        
        // random_id должен быть integer
        assertThat(row.get("random_id")).isInstanceOf(Integer.class);
    }

    @Test
    @DisplayName("Должен генерировать разные значения для каждой строки")
    void shouldGenerateDifferentValuesForEachRow() {
        // given
        BackupFile backup = createBackupWithUsers();
        Map<String, Object> userDefaults = new java.util.HashMap<>();
        userDefaults.put("uuid", "#uuid");  // каждый UUID должен быть уникальным
        
        Map<String, Map<String, Object>> missingFields = Map.of(
                "users", userDefaults
        );

        // when
        BackupFile result = invokeApplyDefaults(backup, missingFields);

        // then
        List<Map<String, Object>> rows = result.data().get("users");
        assertThat(rows).hasSize(2);
        
        String uuid1 = (String) rows.get(0).get("uuid");
        String uuid2 = (String) rows.get(1).get("uuid");
        
        assertThat(uuid1).isNotEqualTo(uuid2);
    }

    // --- Helpers ---

    private BackupFile createBackupWithUsers() {
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

        return new BackupFile(
                "test_db",
                "mysql",
                new SchemaMeta("test_db", List.of(tableMeta)),
                Map.of(
                        "users", List.of(
                                Map.of("id", 1L, "name", "Alice"),
                                Map.of("id", 2L, "name", "Bob")
                        )
                ),
                List.of("users")
        );
    }

    private BackupFile createBackupWithUsersAndOrders() {
        TableMeta usersMeta = new TableMeta(
                "users",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("name", "varchar(255)", false, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        TableMeta ordersMeta = new TableMeta(
                "orders",
                List.of(
                        new ColumnMeta("id", "bigint", false, true),
                        new ColumnMeta("amount", "double", false, false)
                ),
                List.of("id"),
                List.of(),
                List.of()
        );

        return new BackupFile(
                "test_db",
                "mysql",
                new SchemaMeta("test_db", List.of(usersMeta, ordersMeta)),
                Map.of(
                        "users", List.of(
                                Map.of("id", 1L, "name", "Alice")
                        ),
                        "orders", List.of(
                                Map.of("id", 1L, "amount", 99.99)
                        )
                ),
                List.of("users", "orders")
        );
    }

    /**
     * Инвокает приватный метод через reflection.
     */
    private BackupFile invokeApplyDefaults(BackupFile backup, Map<String, Map<String, Object>> missingFields) {
        try {
            java.lang.reflect.Method method = RestoreService.class.getDeclaredMethod(
                    "applyMissingFieldsDefaults",
                    BackupFile.class,
                    Map.class
            );
            method.setAccessible(true);
            return (BackupFile) method.invoke(restoreService, backup, missingFields);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke applyMissingFieldsDefaults", e);
        }
    }
}
