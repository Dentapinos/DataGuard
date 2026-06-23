package com.dentapinos.dataguard.unit.service.restore.order;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ForeignKeyMeta;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.exception.CircularDependencyException;
import com.dentapinos.dataguard.service.metadata.DatabaseMetadataReader;
import com.dentapinos.dataguard.service.restore.order.RestoreOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Юнит-тесты для RestoreOrderService.
 * Проверяет определение правильного порядка восстановления таблиц на основе внешних ключей,
 * включая топологическую сортировку, обработку зависимостей и обход циклических зависимостей.
 */
@DisplayName("Unit-test для сервиса определения порядка восстановления")
class RestoreOrderServiceTest {

    @Mock
    private DatabaseMetadataReader metadataReader;

    private RestoreOrderService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new RestoreOrderService(metadataReader);
    }

    /**
     * Создает метаданные таблицы для тестов с указанными внешними ключами.
     */
    private static TableMeta createTable(String name, List<ForeignKeyMeta> foreignKeys) {
        return new TableMeta(name, null, null, foreignKeys, null);
    }

    /**
     * Создает метаданные внешнего ключа для тестов с одним столбцом.
     */
    private static ForeignKeyMeta createForeignKey(String name, String referencedTable) {
        return new ForeignKeyMeta(name, List.of("column"), referencedTable, List.of("id"));
    }

    /**
     * Создает метаданные внешнего ключа для тестов с несколькими столбцами.
     */
    private static ForeignKeyMeta createForeignKey(String name, List<String> columnNames, String referencedTable, List<String> referencedColumnNames) {
        return new ForeignKeyMeta(name, columnNames, referencedTable, referencedColumnNames);
    }

    /**
     * Создает метаданные схемы для тестов.
     */
    private static SchemaMeta createSchema(List<TableMeta> tables) {
        return new SchemaMeta(null, tables);
    }

    /**
     * Создает учетные данные для подключения к тестовой базе данных.
     */
    private DbCredentials createCredentials() {
        return new DbCredentials("host",  "user", "pass");
    }

    // === Тесты для determineRestoreOrder ===

    @Nested
    class DetermineRestoreOrderTests {

        @Test
        @DisplayName("должен возвращать пустой список, когда не указаны таблицы и в базе данных нет таблиц")
        void shouldReturnEmptyListWhenNoTablesSpecifiedAndNoTablesInDatabase() {
            // arrange
            DbCredentials creds = createCredentials();
            String db = "testdb";
            when(metadataReader.listTables(creds, db)).thenReturn(Collections.emptyList());

            // act
            List<String> result = service.determineRestoreOrder(creds, db, null);

            // assert
            assertEquals(Collections.emptyList(), result);
        }

        @Test
        @DisplayName("должен возвращать пустой список, когда указанный список пустой")
        void shouldReturnEmptyListWhenSpecifiedListIsEmpty() {
            // arrange
            DbCredentials creds = createCredentials();
            String db = "testdb";
            when(metadataReader.listTables(creds, db)).thenReturn(List.of());

            // act
            List<String> result = service.determineRestoreOrder(creds, db, new ArrayList<>());

            // assert
            assertEquals(Collections.emptyList(), result);
        }

        @Test
        @DisplayName("должен читать все таблицы, когда список для восстановления равен null")
        void shouldReadAllTablesWhenTablesToRestoreIsNull() {
            // arrange
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> allTables = List.of("users", "orders");
            when(metadataReader.listTables(creds, db)).thenReturn(allTables);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(allTables)))
                    .thenReturn(createSchema(List.of(
                            createTable("users", List.of()),
                            createTable("orders", List.of(createForeignKey("fk_orders_users", "users")))
                    )));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, null);

            // assert
            assertEquals(List.of("users", "orders"), result);
            verify(metadataReader).listTables(creds, db);
            verify(metadataReader).readSchema(eq(creds), eq(db), eq(allTables));
        }

        @Test
        @DisplayName("должен фильтровать несуществующие таблицы, когда указан конкретный список")
        void shouldFilterNonExistentTablesWhenListIsSpecified() {
            // arrange
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> requested = List.of("users", "nonexistent");
            List<String> existing = List.of("users");
            when(metadataReader.listTables(creds, db)).thenReturn(existing);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(existing)))
                    .thenReturn(createSchema(List.of(createTable("users", List.of()))));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, requested);

            // assert
            assertEquals(List.of("users"), result);
        }

        @Test
        @DisplayName("должен отфильтровать все несуществующие таблицы")
        void shouldFilterAllNonExistentTables() {
            // arrange
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> requested = List.of("nonexistent1", "nonexistent2");
            List<String> existing = List.of("users", "orders");
            when(metadataReader.listTables(creds, db)).thenReturn(existing);

            // act
            List<String> result = service.determineRestoreOrder(creds, db, requested);

            // assert
            assertEquals(Collections.emptyList(), result);
        }

        @Test
        @DisplayName("должен сортировать таблицы по зависимостям внешних ключей (простой случай)")
        void shouldSortByForeignKeyDependencyCorrectSimpleCase() {
            // arrange
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> tables = List.of("users", "orders", "products");
            when(metadataReader.listTables(creds, db)).thenReturn(tables);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(tables)))
                    .thenReturn(createSchema(List.of(
                            createTable("users", List.of()),
                            createTable("orders", List.of(createForeignKey("fk_order_user", "users"))),
                            createTable("products", List.of(createForeignKey("fk_product_user", "users")))
                    )));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, tables);

            // assert
            assertEquals(List.of("users", "orders", "products"), result);
        }

        @Test
        @DisplayName("должен обрабатывать множественные зависимости")
        void shouldHandleMultipleDependencies() {
            // arrange: orders → users, line_items → orders & users
            // Граф зависимостей:
            //   users: []
            //   orders: [users]
            //   line_items: [users, orders]
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> tables = List.of("users", "orders", "line_items");
            when(metadataReader.listTables(creds, db)).thenReturn(tables);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(tables)))
                    .thenReturn(createSchema(List.of(
                            createTable("users", List.of()),
                            createTable("orders", List.of(createForeignKey("fk_order_user", "users"))),
                            createTable("line_items",
                                    List.of(
                                            createForeignKey("fk_line_user", "users"),
                                            createForeignKey("fk_line_order", "orders")
                                    )
                            )
                    )));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, tables);

            // assert
            // users должен быть первым, orders и line_items после, но orders должен быть перед line_items
            assertTrue(result.indexOf("users") < result.indexOf("orders"));
            assertTrue(result.indexOf("users") < result.indexOf("line_items"));
            assertTrue(result.indexOf("orders") < result.indexOf("line_items"));
            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("должен возвращать null и логировать ошибку при возникновении исключения")
        void shouldReturnNullAndLogOnError() {
            // arrange: исключение во время readSchema
            DbCredentials creds = createCredentials();
            String db = "testdb";
            when(metadataReader.listTables(creds, db)).thenReturn(List.of("users"));
            when(metadataReader.readSchema(eq(creds), eq(db), eq(List.of("users"))))
                    .thenThrow(new RuntimeException("DB error"));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, List.of("users"));

            // assert
            assertNull(result);
        }

        @Test
        @DisplayName("должен возвращать независимые таблицы в любом порядке")
        void shouldReturnIndependentTablesInAnyOrder() {
            // arrange: Никаких зависимостей
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> tables = List.of("a", "b", "c");
            when(metadataReader.listTables(creds, db)).thenReturn(tables);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(tables)))
                    .thenReturn(createSchema(List.of(
                            createTable("a", List.of()),
                            createTable("b", List.of()),
                            createTable("c", List.of())
                    )));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, tables);

            // assert - все таблицы должны быть возвращены, порядок не важен
            assertEquals(3, result.size());
            assertTrue(result.contains("a"));
            assertTrue(result.contains("b"));
            assertTrue(result.contains("c"));
        }

        @Test
        @DisplayName("должен обрабатывать глубокую цепочку зависимостей")
        void shouldHandleDeepDependencyChain() {
            // arrange: a -> b -> c -> d -> e
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> tables = List.of("a", "b", "c", "d", "e");
            when(metadataReader.listTables(creds, db)).thenReturn(tables);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(tables)))
                    .thenReturn(createSchema(List.of(
                            createTable("a", List.of(createForeignKey("fk_a_b", "b"))),
                            createTable("b", List.of(createForeignKey("fk_b_c", "c"))),
                            createTable("c", List.of(createForeignKey("fk_c_d", "d"))),
                            createTable("d", List.of(createForeignKey("fk_d_e", "e"))),
                            createTable("e", List.of())
                    )));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, tables);

            // assert
            assertEquals(List.of("e", "d", "c", "b", "a"), result);
        }

        @Test
        @DisplayName("должен фильтровать только существующие таблицы")
        void shouldFilterToOnlyExistingTables() {
            // arrange: Некоторые определённые таблицы не существуют
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> requested = List.of("a", "b", "c", "nonexistent");
            List<String> existing = List.of("a", "b", "c");
            when(metadataReader.listTables(creds, db)).thenReturn(existing);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(existing)))
                    .thenReturn(createSchema(List.of(
                            createTable("a", List.of()),
                            createTable("b", List.of()),
                            createTable("c", List.of())
                    )));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, requested);

            // assert
            assertEquals(3, result.size());
            assertTrue(result.contains("a"));
            assertTrue(result.contains("b"));
            assertTrue(result.contains("c"));
        }
    }

    // === Тесты для resolveTablesToRestore (через public API) ===

    @Nested
    class ResolveTablesToRestoreViaPublicAPI {

        @Test
        @DisplayName("должен возвращать все таблицы, когда указанный список равен null")
        void shouldReturnAllTablesWhenSpecifiedListIsNull() {
            // arrange
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> allTables = List.of("users", "orders");
            when(metadataReader.listTables(creds, db)).thenReturn(allTables);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(allTables)))
                    .thenReturn(createSchema(List.of(
                            createTable("users", List.of()),
                            createTable("orders", List.of())
                    )));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, null);

            // assert - обе таблицы должны быть возвращены (порядок не важен, так как нет зависимостей)
            assertNotNull(result);
            assertEquals(2, result.size());
            assertTrue(result.contains("users"));
            assertTrue(result.contains("orders"));
        }

        @Test
        @DisplayName("должен фильтровать указанный список по существующим таблицам")
        void shouldFilterSpecifiedListByExisting() {
            // arrange
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> specified = List.of("users", "orders", "missing");
            List<String> existing = List.of("users", "orders");
            when(metadataReader.listTables(creds, db)).thenReturn(existing);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(existing)))
                    .thenReturn(createSchema(List.of(
                            createTable("users", List.of()),
                            createTable("orders", List.of())
                    )));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, specified);

            // assert - обе таблицы должны быть возвращены, порядок не важен, так как нет зависимостей
            assertNotNull(result);
            assertEquals(2, result.size());
            assertTrue(result.contains("users"));
            assertTrue(result.contains("orders"));
        }

        @Test
        @DisplayName("должен обрабатывать пустой указанный список")
        void shouldHandleEmptySpecifiedList() {
            // arrange
            DbCredentials creds = createCredentials();
            String db = "testdb";
            List<String> allTables = List.of("users", "orders");
            when(metadataReader.listTables(creds, db)).thenReturn(allTables);
            when(metadataReader.readSchema(eq(creds), eq(db), eq(allTables)))
                    .thenReturn(createSchema(List.of(
                            createTable("users", List.of()),
                            createTable("orders", List.of())
                    )));

            // act
            List<String> result = service.determineRestoreOrder(creds, db, new ArrayList<>());

            // assert - пустой список должен вызывать чтение всех таблиц, порядок не важен, так как нет зависимостей
            assertNotNull(result);
            assertEquals(2, result.size());
            assertTrue(result.contains("users"));
            assertTrue(result.contains("orders"));
        }
    }

    @Nested
    class TopologicalSortTests {

        @Test
        @DisplayName("должен сортировать простую цепочку")
        void shouldSortSimpleChain() {
            // arrange: users ← orders ← line_items
            Map<String, Set<String>> graph = Map.of(
                    "users", Set.of(),
                    "orders", Set.of("users"),
                    "line_items", Set.of("users", "orders")
            );

            List<String> result = topologicalSort(graph, new HashSet<>(List.of("users", "orders", "line_items")));

            // assert
            assertEquals(List.of("users", "orders", "line_items"), result);
        }

        @Test
        @DisplayName("должен сортировать независимые таблицы")
        void shouldSortIndependentTables() {
            // arrange
            Map<String, Set<String>> graph = Map.of(
                    "a", Set.of(),
                    "b", Set.of(),
                    "c", Set.of()
            );

            List<String> result = topologicalSort(graph, new HashSet<>(List.of("a", "b", "c")));

            // assert
            assertEquals(3, result.size());
            assertTrue(result.containsAll(List.of("a", "b", "c")));
        }

        @Test
        @DisplayName("должен сортировать одну таблицу без зависимостей")
        void shouldSortSingleTableWithNoDependencies() {
            // arrange
            Map<String, Set<String>> graph = Map.of(
                    "a", Set.of()
            );

            List<String> result = topologicalSort(graph, new HashSet<>(List.of("a")));

            // assert
            assertEquals(List.of("a"), result);
        }

        @Test
        @DisplayName("должен выбрасывать исключение при наличии циклических зависимостей")
        void shouldThrowExceptionOnCycle() {
            // arrange: A → B → C → A
            Map<String, Set<String>> graph = Map.of(
                    "A", Set.of("B"),
                    "B", Set.of("C"),
                    "C", Set.of("A")
            );

            CircularDependencyException exception = assertThrows(
                    CircularDependencyException.class,
                    () -> topologicalSort(graph, new HashSet<>(List.of("A", "B", "C")))
            );

            // assert
            assertTrue(exception.getMessage().contains("циклические зависимости"));
        }

        @Test
        @DisplayName("должен выбрасывать исключение при наличии самоссылки")
        void shouldHandleSelfReference() {
            // arrange
            Map<String, Set<String>> graph = Map.of(
                    "A", Set.of("A")
            );

            CircularDependencyException exception = assertThrows(
                    CircularDependencyException.class,
                    () -> topologicalSort(graph, new HashSet<>(List.of("A")))
            );

            // assert
            assertTrue(exception.getMessage().contains("циклические зависимости"));
        }

        @Test
        @DisplayName("должен сортировать сложный граф зависимостей")
        void shouldSortComplexDependencyGraph() {
            // arrange: A -> B, C
            // B -> D, E
            // C -> F
            // D, E, F -> no dependencies
            Map<String, Set<String>> graph = Map.of(
                    "A", Set.of("B", "C"),
                    "B", Set.of("D", "E"),
                    "C", Set.of("F"),
                    "D", Set.of(),
                    "E", Set.of(),
                    "F", Set.of()
            );

            List<String> result = topologicalSort(graph, new HashSet<>(List.of("A", "B", "C", "D", "E", "F")));

            // assert
            // A должен быть последним, D, E, F должны быть первыми (в любом порядке), B после D,E, C после F
            assertTrue(result.indexOf("D") < result.indexOf("B"));
            assertTrue(result.indexOf("E") < result.indexOf("B"));
            assertTrue(result.indexOf("F") < result.indexOf("C"));
            assertTrue(result.indexOf("B") < result.indexOf("A"));
            assertTrue(result.indexOf("C") < result.indexOf("A"));
            assertEquals(6, result.size());
        }
    }

    @Nested
    class BuildDependencyGraphTests {

        @Test
        @DisplayName("должен правильно строить граф зависимостей")
        void shouldBuildGraphCorrectly() {
            // arrange: users: no FK
            // orders: FK -> users
            // products: FK -> users
            SchemaMeta schema = createSchema(List.of(
                    createTable("users", List.of()),
                    createTable("orders", List.of(createForeignKey("fk_o_u", "users"))),
                    createTable("products", List.of(createForeignKey("fk_p_u", "users")))
            ));

            Set<String> targets = Set.of("users", "orders", "products");

            Map<String, Set<String>> graph = buildDependencyGraph(schema, targets);

            // assert
            assertEquals(Map.of(
                    "users", Set.of(),
                    "orders", Set.of("users"),
                    "products", Set.of("users")
            ), graph);
        }

        @Test
        @DisplayName("должен игнорировать внешние ключи к таблицам вне целевого множества")
        void shouldIgnoreForeignKeyToTablesOutsideTargetSet() {
            // arrange: orders: FK -> users, но users не в целевом множестве
            SchemaMeta schema = createSchema(List.of(
                    createTable("users", List.of()),
                    createTable("orders", List.of(createForeignKey("fk_o_u", "users")))
            ));

            Set<String> targets = Set.of("orders");

            Map<String, Set<String>> graph = buildDependencyGraph(schema, targets);

            // assert
            assertEquals(Map.of("orders", Set.of()), graph);
        }

        @Test
        @DisplayName("должен обрабатывать пустое целевое множество")
        void shouldHandleEmptyTargetSet() {
            // arrange
            SchemaMeta schema = createSchema(List.of(
                    createTable("users", List.of())
            ));

            Set<String> targets = Set.of();

            Map<String, Set<String>> graph = buildDependencyGraph(schema, targets);

            // assert
            assertEquals(Map.of(), graph);
        }

        @Test
        @DisplayName("должен обрабатывать несколько внешних ключей")
        void shouldHandleMultipleForeignKeys() {
            // arrange: line_items: FK -> users, FK -> orders
            SchemaMeta schema = createSchema(List.of(
                    createTable("users", List.of()),
                    createTable("orders", List.of()),
                    createTable("line_items",
                            List.of(
                                    createForeignKey("fk_li_user", "users"),
                                    createForeignKey("fk_li_order", "orders")
                            )
                    )
            ));

            Set<String> targets = Set.of("users", "orders", "line_items");

            Map<String, Set<String>> graph = buildDependencyGraph(schema, targets);

            // assert
            assertEquals(Map.of(
                    "users", Set.of(),
                    "orders", Set.of(),
                    "line_items", Set.of("users", "orders")
            ), graph);
        }

        @Test
        @DisplayName("должен обрабатывать смешанные существующие и несуществующие ссылки FK")
        void shouldHandleMixedExistingAndNonExistingFKReferences() {
            // arrange: orders: FK -> users (существует), FK -> products (не существует в целевых)
            SchemaMeta schema = createSchema(List.of(
                    createTable("users", List.of()),
                    createTable("orders", List.of(
                            createForeignKey("fk_o_u", "users"),
                            createForeignKey("fk_o_p", "products")
                    ))
            ));

            Set<String> targets = Set.of("users", "orders");

            Map<String, Set<String>> graph = buildDependencyGraph(schema, targets);

            // assert: только users должен быть в зависимостях, products не в целевом множестве
            assertEquals(Map.of(
                    "users", Set.of(),
                    "orders", Set.of("users")
            ), graph);
        }

        @Test
        @DisplayName("должен обрабатывать схему с независимыми таблицами")
        void shouldHandleSchemaWithUnrelatedTables() {
            // arrange: users: no FK
            // orders: FK -> users
            // products: no FK (независимая)
            SchemaMeta schema = createSchema(List.of(
                    createTable("users", List.of()),
                    createTable("orders", List.of(createForeignKey("fk_o_u", "users"))),
                    createTable("products", List.of())
            ));

            Set<String> targets = Set.of("users", "orders", "products");

            Map<String, Set<String>> graph = buildDependencyGraph(schema, targets);

            // assert
            assertEquals(Map.of(
                    "users", Set.of(),
                    "orders", Set.of("users"),
                    "products", Set.of()
            ), graph);
        }

        @Test
        @DisplayName("должен обрабатывать таблицу с несколькими столбцами в внешнем ключе")
        void shouldHandleTableWithMultipleColumnsInForeignKey() {
            // arrange: FK с составным ключом -> пользователей
            SchemaMeta schema = createSchema(List.of(
                    createTable("users", List.of()),
                    createTable("orders", List.of(
                            createForeignKey("fk_o_u", List.of("user_id", "tenant_id"), "users", List.of("id", "tenant_id"))
                    ))
            ));

            Set<String> targets = Set.of("users", "orders");

            Map<String, Set<String>> graph = buildDependencyGraph(schema, targets);

            // assert
            assertEquals(Map.of(
                    "users", Set.of(),
                    "orders", Set.of("users")
            ), graph);
        }
    }

    // Вспомогательные методы для прямого тестирования частных методов
    private List<String> topologicalSort(Map<String, Set<String>> dependencyGraph, Set<String> allTables) {
        return invokePrivateMethod("topologicalSort", dependencyGraph, allTables);
    }

    private Map<String, Set<String>> buildDependencyGraph(SchemaMeta schema, Set<String> targetTables) {
        return invokePrivateMethod("buildDependencyGraph", schema, targetTables);
    }

    /**
     * Вызывает приватный метод через reflection для тестирования внутренней логики.
     */
    @SuppressWarnings("unchecked")
    private <T> T invokePrivateMethod(String methodName, Object... args) {
        try {
            // Use the actual parameter types for method lookup
            Class<?>[] paramTypes = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Map) {
                    paramTypes[i] = Map.class;
                } else if (args[i] instanceof Set) {
                    paramTypes[i] = Set.class;
                } else if (args[i] instanceof List) {
                    paramTypes[i] = List.class;
                } else {
                    paramTypes[i] = args[i].getClass();
                }
            }
            
            var method = RestoreOrderService.class.getDeclaredMethod(methodName, paramTypes);
            method.setAccessible(true);
            return (T) method.invoke(service, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Перебрасываем исходное исключение, разворачивая CircularDependencyException
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            } else {
                throw new RuntimeException(cause);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
