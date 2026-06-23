package com.dentapinos.dataguard.integration.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.entity.storage.BackupFileInfo;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.RestoreStatus;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.dentapinos.dataguard.report.BackupReport;
import com.dentapinos.dataguard.report.RestoreReport;
import com.dentapinos.dataguard.report.RestoreSummary;
import com.dentapinos.dataguard.service.BackupFacade;
import com.dentapinos.dataguard.service.restore.RestoreService;
import com.dentapinos.dataguard.storage.BackupStorage;
import com.dentapinos.dataguard.test.BaseResetDatabaseTest;
import com.dentapinos.dataguard.test.annotation.WithResetDatabaseBeforeEach;
import com.dentapinos.dataguard.test.config.TestDatabaseConfig;
import com.dentapinos.dataguard.test.domain.*;
import com.dentapinos.dataguard.utils.DatabaseConfigResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест STRICT режима: бэкап и восстановление данных со строгой проверкой дубликатов и схемы.
 * При наличии существующих данных или расхождений схемы выбрасывает исключение.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { TestDatabaseConfig.class })
@WithResetDatabaseBeforeEach
@DisplayName("IT - строгое бэкапирование и восстановление с FK")
class FullBackupRestoreWithRelationshipsStrictIT extends BaseResetDatabaseTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UserProfileRepository userProfileRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private ProductOrderRepository productOrderRepository;

    @Autowired
    private BackupFacade backupFacade;

    @Autowired
    private BackupStorage backupStorage;

    @Autowired
    private DatabaseConfigResolver databaseConfigResolver;

    @Autowired
    private RestoreService restoreService;

    @BeforeEach
    void setUp() {
        // Генерируем уникальные имена для тестовых данных
        dataPrefix = "test_" + UUID.randomUUID().toString().substring(0, 8) + "_";
    }

    @AfterEach
    @Transactional
    @Rollback(false)
    void tearDown() throws IOException {
        // Очистка тестовых бэкапов
        String backupPath = System.getProperty("user.dir") + "/build/test-backups";
        Path path = Path.of(backupPath);
        if (Files.exists(path)) {
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        System.err.println("Не удалось удалить файл: " + file + " — " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    try {
                        Files.deleteIfExists(dir);
                    } catch (IOException e) {
                        System.err.println("Не удалось удалить директорию: " + dir + " — " + e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }

    // Уникальный префикс для имен тестовых данных
    private String dataPrefix;

    /**
     * 
     * @Test - полный цикл бэкапа/восстановления со всеми типами связей.
     * Восстановление в пустую БД и с существующими данными (исключение).
     */
    @Test
    @DisplayName("Полный цикл бэкапа и восстановления сущностей со всеми типами связей")
    void fullBackupRestoreCycleWithAllRelationships() throws IOException {
        // ===== Шаг 1: Создаем сущности со всеми типами связей =====
        System.out.println("""
                ++++++++++++++++++++++++++++++++++++++++++++++++
                === Шаг 1: Создание сущностей со всеми связями ===
                ++++++++++++++++++++++++++++++++++++++++++++++++
                """);

        // ===== @OneToOne: User <-> UserProfile =====
        User user1 = User.builder()
                .username(dataPrefix + "john_doe")
                .email(dataPrefix + "john@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);

        UserProfile profile1 = UserProfile.builder()
                .user(savedUser1)
                .bio("Java developer")
                .avatarUrl("https://example.com/avatar1.jpg")
                .build();
        UserProfile savedProfile1 = userProfileRepository.save(profile1);

        // Устанавливаем двустороннюю связь
        savedUser1.setUserProfile(savedProfile1);
        savedUser1.setOrders(new ArrayList<>());
        savedUser1.setProducts(new ArrayList<>());
        userRepository.save(savedUser1);

        User user2 = User.builder()
                .username(dataPrefix + "jane_smith")
                .email(dataPrefix + "jane@example.com")
                .build();
        User savedUser2 = userRepository.save(user2);

        UserProfile profile2 = UserProfile.builder()
                .user(savedUser2)
                .bio("Frontend developer")
                .avatarUrl("https://example.com/avatar2.jpg")
                .build();
        UserProfile savedProfile2 = userProfileRepository.save(profile2);

        // Устанавливаем двустороннюю связь
        savedUser2.setUserProfile(savedProfile2);
        savedUser2.setOrders(new ArrayList<>());
        savedUser2.setProducts(new ArrayList<>());
        userRepository.save(savedUser2);

        System.out.println("Создано 2 пользователя с профилями (@OneToOne)");
        System.out.println("Users count: " + userRepository.count());
        System.out.println("UserProfiles count: " + userProfileRepository.count());

        // ===== @OneToMany/@ManyToOne: Category <-> Product =====
        Category category1 = Category.builder()
                .name(dataPrefix + "Electronics")
                .description("Electronic devices and gadgets")
                .build();
        Category savedCategory1 = categoryRepository.save(category1);

        Category category2 = Category.builder()
                .name(dataPrefix + "Books")
                .description("Books and publications")
                .build();
        Category savedCategory2 = categoryRepository.save(category2);

        Product product1 = Product.builder()
                .name(dataPrefix + "Laptop")
                .description("High-performance laptop")
                .price(1500.00)
                .stockQuantity(50)
                .category(savedCategory1)
                .user(savedUser1)
                .build();
        Product savedProduct1 = productRepository.save(product1);

        Product product2 = Product.builder()
                .name(dataPrefix + "Smartphone")
                .description("Latest smartphone model")
                .price(999.99)
                .stockQuantity(100)
                .category(savedCategory1)
                .user(savedUser2)
                .build();
        Product savedProduct2 = productRepository.save(product2);

        Product product3 = Product.builder()
                .name(dataPrefix + "Java Programming Book")
                .description("Complete Java guide")
                .price(49.99)
                .stockQuantity(200)
                .category(savedCategory2)
                .user(savedUser1)
                .build();
        Product savedProduct3 = productRepository.save(product3);

        // Устанавливаем двустороннюю связь
        savedCategory1.setProducts(List.of(savedProduct1, savedProduct2));
        categoryRepository.save(savedCategory1);
        savedCategory2.setProducts(List.of(savedProduct3));
        categoryRepository.save(savedCategory2);

        System.out.println("Создано 2 категории и 3 продукта (@OneToMany/@ManyToOne)");
        System.out.println("Categories count: " + categoryRepository.count());
        System.out.println("Products count: " + productRepository.count());

        // ===== @ManyToMany: Order <-> Product (через промежуточную таблицу) =====
        Order order1 = Order.builder()
                .orderNumber(dataPrefix + "ORD-001")
                .status(OrderStatus.CONFIRMED)
                .user(savedUser1)
                .build();
        order1.getProducts().add(savedProduct1);
        order1.getProducts().add(savedProduct2);
        Order savedOrder1 = orderRepository.save(order1);

        Order order2 = Order.builder()
                .orderNumber(dataPrefix + "ORD-002")
                .status(OrderStatus.PENDING)
                .user(savedUser2)
                .build();
        savedUser2.addOrder(order2);
        order2.getProducts().add(savedProduct3);
        Order savedOrder2 = orderRepository.save(order2);

        savedProduct1.setOrders(new HashSet<>());
        savedProduct2.setOrders(new HashSet<>());
        savedProduct3.setOrders(new HashSet<>());
        savedProduct1.getOrders().add(savedOrder1);
        savedProduct2.getOrders().add(savedOrder1);
        savedProduct3.getOrders().add(savedOrder2);

        productRepository.save(savedProduct1);
        productRepository.save(savedProduct2);
        productRepository.save(savedProduct3);

        savedUser1.getOrders().add(order1);
        savedUser2.getOrders().add(order2);

        System.out.println("Создано 2 заказа с ManyToMany связью (@ManyToMany)");
        System.out.println("Orders count: " + orderRepository.count());

        // ===== ProductOrder (сущность-связка с доп. полями) =====
        // Создаем ProductOrder только с Product (order может быть null)
        ProductOrder productOrder1 = ProductOrder.builder()
                .product(savedProduct1)
                .quantity(2)
                .totalPrice(3000.00)
                .build();
        ProductOrder savedProductOrder1 = productOrderRepository.save(productOrder1);

        // Добавляем ProductOrder в списки связанных сущностей
        savedProduct1.getProductsOrders().add(savedProductOrder1);
        productRepository.save(savedProduct1);

        System.out.println("Создан ProductOrder с дополнительными полями");
        System.out.println("ProductOrders count: " + productOrderRepository.count());

        // ===== Проверяем, что данные сохранены в БД =====
        System.out.println("\n=== Проверка сохраненных данных в БД ===");
        System.out.println("Users count: " + userRepository.count());
        System.out.println("UserProfiles count: " + userProfileRepository.count());
        System.out.println("Categories count: " + categoryRepository.count());
        System.out.println("Products count: " + productRepository.count());
        System.out.println("Orders count: " + orderRepository.count());
        System.out.println("ProductOrders count: " + productOrderRepository.count());

        // ===== Проверка данных перед бэкапом =====
        checkAllEntitiesBeforeBackup(
                savedUser1, savedUser2,
                savedProfile1, savedProfile2,
                savedCategory1, savedCategory2,
                savedProduct1, savedProduct2, savedProduct3,
                (Order) order1, (Order) order2,
                savedProductOrder1);

        // ===== Шаг 2: Список таблиц для бэкапа =====
        List<String> tablesToBackup = List.of(
                "users",
                "user_profiles",
                "categories",
                "products",
                "orders",
                "product_orders",
                "order_products",
                "product_order_users"
        );

        // ===== Шаг 3: Делаем бэкап через BackupFacade =====
        System.out.println("\n=== Шаг 3: Создание бэкапа ===");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);

        // ===== Шаг 4: Сохраняем бэкап во временную директорию =====
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        // ===== Шаг 5: Проверяем, что бэкап создан и сохранен =====
        System.out.println("\n=== Шаг 5: Проверка бэкапа ===");
        assertNotNull(backupName, "Имя бэкапа должно быть сгенерировано");
        assertTrue(backupName.endsWith(".zip"), "Имя бэкапа должно заканчиваться на .zip");

        // Проверяем, что файл бэкапа существует через backupStorage
        List<BackupFileInfo> backups = backupStorage.listWithInfo(BackupTier.DAILY, databaseName);
        assertFalse(backups.isEmpty(), "Список бэкапов не должен быть пустым");
        assertTrue(backups.stream().anyMatch(info -> info.fileName().contains(backupName)),
                "Бэкап должен присутствовать в списке");

        // Строим путь к файлу для отладки
        String userDir = System.getProperty("user.dir");
        String backupFilePath = userDir + File.separator + "build" + File.separator + "test-backups" + File.separator + databaseName + File.separator + "DAILY" + File.separator + backupName;
        Path backupPath = Path.of(backupFilePath);
        System.out.println("Backup file path (for debug): " + backupPath);

        System.out.println("=== Бэкап успешно создан и сохранен ===");
        System.out.println("Backup name: " + backupName);
        System.out.println("Backup storage: " + backups);
        System.out.println("Report: " + envelope.report());

        // ===== Шаг 6: Восстанавливаем данные в пустую БД =====
        System.out.println("\n=== Шаг 7: Первое восстановление (все данные новые, БД была пустая) ===");

        // Очищаем данные перед восстановлением
        cleanUpTestData();

        // Проверяем, что данные удалены
        assertEquals(0, userRepository.count(), "Таблица users должна быть пустой");
        assertEquals(0, userProfileRepository.count(), "Таблица user_profiles должна быть пустой");
        assertEquals(0, categoryRepository.count(), "Таблица categories должна быть пустой");
        assertEquals(0, productRepository.count(), "Таблица products должна быть пустой");
        assertEquals(0, orderRepository.count(), "Таблица orders должна быть пустой");
        assertEquals(0, productOrderRepository.count(), "Таблица product_orders должна быть пустой");

        System.out.println("=== Данные успешно очищены, готовы к восстановлению ===");

        // Восстанавливаем данные из бэкапа в STRICT режиме
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.STRICT,
                tablesToBackup
        );

        // ===== Проверяем результат восстановления =====
        System.out.println("\n=== Проверка результата восстановления ===");
        assertNotNull(restoreReport, "Отчет о восстановлении не должен быть null");
        assertEquals(RestoreMode.STRICT, restoreReport.mode(), "Режим восстановления должен быть STRICT");
        assertEquals(RestoreStatus.SUCCESS, restoreReport.status(), "Статус восстановления должен быть SUCCESS");

        RestoreSummary summary = restoreReport.summary();
        assertEquals(8, summary.tablesProcessed(), "Должно быть обработано 8 таблиц");
        assertEquals(0, summary.tablesFailed(), "Не должно быть ошибок при восстановлении");
        assertEquals(0, summary.tablesSkipped(), "Не должно быть пропущенных таблиц (БД была пустая)");

        System.out.println("Восстановление выполнено успешно в STRICT режиме");
        System.out.println("Tables processed: " + summary.tablesProcessed());
        System.out.println("Rows inserted: " + summary.rowsInserted());
        System.out.println("Rows updated: " + summary.rowsUpdated());
        System.out.println("Rows skipped: " + summary.rowsSkipped());

        // ===== Проверяем, что данные восстановлены =====
        System.out.println("\n=== Проверка восстановленных данных ===");
        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя после восстановления");
        assertEquals(2, userProfileRepository.count(), "Должно быть 2 профиля после восстановления");
        assertEquals(2, categoryRepository.count(), "Должно быть 2 категории после восстановления");
        assertEquals(3, productRepository.count(), "Должно быть 3 продукта после восстановления");
        assertEquals(2, orderRepository.count(), "Должно быть 2 заказа после восстановления");
        assertEquals(1, productOrderRepository.count(), "Должно быть 1 ProductOrder после восстановления");

        // ===== Шаг 8: Проверяем целостность связей после восстановления =====
        System.out.println("\n=== Шаг 8: Проверка целостности связей ===");

        // Проверяем @OneToOne связь
        User restoredUser1 = userRepository.findByUsernameWithProfile(dataPrefix + "john_doe").orElseThrow();
        assertNotNull(restoredUser1.getUserProfile(), "У пользователя должна быть профиль");
        assertEquals(restoredUser1.getId(), restoredUser1.getUserProfile().getUser().getId(),
                "Связь UserProfile -> User должна быть корректной");
        System.out.println("=== Проверка @OneToOne связей пройдена ===");

        // Проверяем @OneToMany/@ManyToOne
        Category restoredCategory1 = categoryRepository.findByNameWithProducts(dataPrefix + "Electronics").orElseThrow();
        assertEquals(2, restoredCategory1.getProducts().size(), "Категория должна содержать 2 продукта");
        assertTrue(restoredCategory1.getProducts().stream()
                .anyMatch(p -> p.getName().contains("Laptop")), "Категория должна содержать Laptop");
        assertTrue(restoredCategory1.getProducts().stream()
                .anyMatch(p -> p.getName().contains("Smartphone")), "Категория должна содержать Smartphone");
        System.out.println("=== Проверка @OneToMany/@ManyToOne связей пройдена ===");

        // Проверяем @ManyToMany
        Order restoredOrder1 = orderRepository.findByOrderNumberWithProducts(dataPrefix + "ORD-001").orElseThrow();
        assertEquals(2, restoredOrder1.getProducts().size(), "Заказ должен содержать 2 продукта");
        assertTrue(restoredOrder1.getProducts().stream()
                .anyMatch(p -> p.getName().contains("Laptop")), "Заказ должен содержать Laptop");
        assertTrue(restoredOrder1.getProducts().stream()
                .anyMatch(p -> p.getName().contains("Smartphone")), "Заказ должен содержать Smartphone");
        System.out.println("=== Проверка @ManyToMany связей пройдена ===");

        // Проверяем ProductOrder
        Product restoredProduct1 = productRepository.findByNameWithProductsOrders(dataPrefix + "Laptop").orElseThrow();
        assertEquals(1, restoredProduct1.getProductsOrders().size(), "Продукт должен содержать 1 ProductOrder");
        ProductOrder restoredProductOrder1 = restoredProduct1.getProductsOrders().get(0);
        assertEquals(Integer.valueOf(2), restoredProductOrder1.getQuantity(), "Количество в ProductOrder должно быть 2");
        assertEquals(Double.valueOf(3000.00), restoredProductOrder1.getTotalPrice(), "Цена в ProductOrder должна быть 3000.00");
        System.out.println("=== Проверка ProductOrder связей пройдена ===");

        // Финальная проверка количества записей
        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя");
        assertEquals(2, userProfileRepository.count(), "Должно быть 2 профиля");
        assertEquals(2, categoryRepository.count(), "Должно быть 2 категории");
        assertEquals(3, productRepository.count(), "Должно быть 3 продукта");
        assertEquals(2, orderRepository.count(), "Должно быть 2 заказа");
        assertEquals(1, productOrderRepository.count(), "Должно быть 1 ProductOrder");

        System.out.println("=== Финальная проверка количества записей пройдена ===");

        // ===== Проверка STRICT режима: восстановление с существующими данными (должно упасть) =====
        System.out.println("\n=== Проверка STRICT режима: добавление нового пользователя и восстановление бэкапа (должно упасть) ===");

        // Создаем нового пользователя, который НЕ входит в бэкап
        User newUser = User.builder()
                .username(dataPrefix + "extra_user")
                .email(dataPrefix + "extra@example.com")
                .build();
        userRepository.save(newUser);
        assertEquals(3, userRepository.count(), "Должно быть 3 пользователя до восстановления (включая extra_user)");

        System.out.println("Восстановление данных из бэкапа в STRICT режиме с существующими данными...");
        System.out.println("Ожидание: исключение при конфликте (строка уже существует)");

        // Восстанавливаем данные из бэкапа в STRICT режиме
        // - strict режим должен выбросить исключение при обнаружении дубликата
        // - восстановление прервется
        // - статус должен быть FAILED
        assertThrows(Exception.class, () -> {
            try {
                restoreService.restoreToExistingDatabase(
                        credentials,
                        BackupTier.DAILY,
                        backupName,
                        databaseName,
                        RestoreMode.STRICT,
                        tablesToBackup
                );
            } catch (Exception e) {
                System.out.println("Ожидаемое исключение при восстановлении с существующими данными: " + e.getClass().getSimpleName());
                throw e;
            }
        }, "STRICT режим должен выбросить исключение при наличии существующих данных");

        // extra_user должен остаться в БД (данные из бэкапа не были вставлены из-за ошибки)
        // Все остальные данные должны остаться в исходном состоянии
        assertEquals(3, userRepository.count(), "Количество пользователей должно остаться 3 (включая extra_user)");
        assertNotNull(userRepository.findByUsername(dataPrefix + "extra_user").orElse(null), "extra_user должен остаться в БД");
        System.out.println("=== Проверка STRICT режима пройдена успешно (исключение выброшено) ===");

        System.out.println("=== Тест полного цикла в STRICT режиме пройден успешно ===");
    }

    /**
     * Тест STRICT режима с частичными данными (дубликаты не разрешены)
     */
    @Test
    @DisplayName("Восстановление в STRICT режиме с частичными данными (дубликаты не разрешены)")
    void partialDataRestoreStrict() throws IOException {
        System.out.println("\n=== Тест: Частичные данные в STRICT режиме (дубликаты не разрешены) ===");

        // Создаем только одного пользователя (не в бэкапе)
        User user1 = User.builder()
                .username(dataPrefix + "partial_user")
                .email(dataPrefix + "partial@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);

        UserProfile profile1 = UserProfile.builder()
                .user(savedUser1)
                .bio("Partial user profile")
                .avatarUrl("https://example.com/partial.jpg")
                .build();
        UserProfile savedProfile1 = userProfileRepository.save(profile1);

        savedUser1.setUserProfile(savedProfile1);
        savedUser1.setOrders(new ArrayList<>());
        savedUser1.setProducts(new ArrayList<>());
        userRepository.save(savedUser1);

        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь до бэкапа");
        assertEquals(1, userProfileRepository.count(), "Должен быть 1 профиль до бэкапа");

        // Создаем бэкап с двумя пользователями
        List<String> tablesToBackup = List.of("users", "user_profiles");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        System.out.println("Существующий пользователь (не в бэкапе): " + savedUser1.getUsername());
        System.out.println("Попытка восстановить бэкап с двумя пользователями в STRICT режиме...");
        System.out.println("Ожидание: исключение при конфликте");

        // Восстанавливаем частичные данные в STRICT режиме
        // STRICT режим не должен позволять восстановление при наличии дубликатов
        assertThrows(Exception.class, () -> {
            restoreService.restoreToExistingDatabase(
                    credentials,
                    BackupTier.DAILY,
                    backupName,
                    databaseName,
                    RestoreMode.STRICT,
                    tablesToBackup
            );
        }, "STRICT режим должен выбросить исключение при дубликатах");

        // Проверяем, что данные не изменились (восстановление не прошло)
        assertEquals(1, userRepository.count(), "Должен остаться 1 пользователь (восстановление не прошло)");
        assertEquals(1, userProfileRepository.count(), "Должен остаться 1 профиль (восстановление не прошло)");
        assertNotNull(userRepository.findByUsername(dataPrefix + "partial_user").orElse(null), "partial_user должен остаться в БД");

        System.out.println("=== Тест частичных данных пройден успешно (исключение выброшено) ===");
    }

    /**
     * Тест STRICT режима с отсутствующей таблицей в целевой БД
     */
    @Test
    @DisplayName("Восстановление в STRICT режиме с отсутствующей таблицей (должно упасть)")
    void restoreWithMissingTableStrict() throws IOException {
        System.out.println("\n=== Тест: Отсутствует таблица в целевой БД (STRICT режим) ===");

        // Создаем данные для бэкапа
        User user1 = User.builder()
                .username(dataPrefix + "user_test_missing_table")
                .email(dataPrefix + "test_missing@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);

        UserProfile profile1 = UserProfile.builder()
                .user(savedUser1)
                .bio("Test user for missing table")
                .avatarUrl("https://example.com/test_missing.jpg")
                .build();
        UserProfile savedProfile1 = userProfileRepository.save(profile1);
        savedUser1.setUserProfile(savedProfile1);
        savedUser1.setProducts(new ArrayList<>());
        savedUser1.setOrders(new ArrayList<>());
        userRepository.save(savedUser1);

        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь до бэкапа");
        assertEquals(1, userProfileRepository.count(), "Должен быть 1 профиль до бэкапа");

        // Создаем бэкап только с пользователями
        List<String> tablesToBackup = List.of("users");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        System.out.println("Восстанавливаем таблицы [users, user_profiles], но в бэкапе только users...");
        System.out.println("Ожидание: исключение из-за отсутствующей таблицы (user_profiles)");

        // Восстанавливаем данные из бэкапа, запрашивая только таблицу users
        // Таблица user_profiles НЕ будет в бэкапе и STRICT режим должен упасть
        assertThrows(Exception.class, () -> {
            restoreService.restoreToExistingDatabase(
                    credentials,
                    BackupTier.DAILY,
                    backupName,
                    databaseName,
                    RestoreMode.STRICT,
                    List.of("users", "user_profiles")  // user_profiles нет в бэкапе
            );
        }, "STRICT режим должен выбросить исключение при отсутствии таблицы в бэкапе");

        System.out.println("=== Тест отсутствующей таблицы пройден успешно (исключение выброшено) ===");
    }

    /**
     * Тест STRICT режима с "лишними" колонками в бэкапе
     */
    @Test
    @DisplayName("Восстановление в STRICT режиме с лишними колонками в бэкапе (должно упасть)")
    void restoreWithMissingColumnsInBackupStrict() throws IOException {
        System.out.println("\n=== Тест: ЛИшние колонки в бэкапе (STRICT режим) ===");

        // Создаем данные для бэкапа
        User user1 = User.builder()
                .username(dataPrefix + "user_missing_cols")
                .email(dataPrefix + "missing_cols@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);

        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь до бэкапа");

        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);

        // Вручную создаем бэкап с ограничением колонок
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        databaseConfigResolver.resolveCredentials(databaseName).url(),
                        databaseConfigResolver.resolveCredentials(databaseName).username(),
                        databaseConfigResolver.resolveCredentials(databaseName).password()
                )
        );

        List<Map<String, Object>> usersData = jdbcTemplate.queryForList(
                "SELECT id, username FROM users WHERE id = ?",
                savedUser1.getId()
        );

        // Получаем все колонки таблицы (используем queryForList, так как их может быть несколько)
        List<Map<String, Object>> schemaColumns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY " +
                        "FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                        "ORDER BY ORDINAL_POSITION",
                databaseName, "users"
        );

        // Проверяем, что получили колонки
        assertFalse(schemaColumns.isEmpty(), "Таблица users должна иметь хотя бы одну колонку");
        System.out.println("Колонки таблицы users: " + schemaColumns.size());
        for (Map<String, Object> col : schemaColumns) {
            System.out.println("  - " + col.get("COLUMN_NAME") + " (" + col.get("DATA_TYPE") + ")");
        }

        // Создаем бэкап вручную с ограничением колонок
        BackupEnvelope envelope = new BackupEnvelope(
                new BackupReport(null, null, null, null, null, null ),
                new BackupFile(
                        databaseName,
                        "mysql",
                        new SchemaMeta(
                                databaseName,
                                List.of(new TableMeta(
                                        "users",
                                        List.of(
                                                new ColumnMeta("id", "BIGINT", true, false),
                                                new ColumnMeta("username", "VARCHAR", false, false)
                                        ),
                                        List.of(),
                                        List.of(),
                                        List.of()
                                ))
                        ),
                        Map.of("users", usersData),
                        List.of("users")
                )
        );
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        System.out.println("Восстанавливаем данные в STRICT режиме с бэкапом, где нет колонки email...");
        System.out.println("Ожидание: исключение из-за отсутствия колонки в бэкапе");

        // Восстанавливаем данные в STRICT режиме
        // STRICT режим должен упасть из-за отсутствия колонки email в бэкапе
        assertThrows(Exception.class, () -> {
            restoreService.restoreToExistingDatabase(
                    credentials,
                    BackupTier.DAILY,
                    backupName,
                    databaseName,
                    RestoreMode.STRICT,
                    List.of("users")
            );
        }, "STRICT режим должен выбросить исключение при отсутствии колонки в бэкапе");

        System.out.println("=== Тест лишних колонок в бэкапе пройден успешно (исключение выброшено) ===");
    }

    /**
     * <p>Тест STRICT режима с нарушением внешних ключей (должно упасть)</p>
     * 
     * <p>ВАЖНО: Для создания FK violation нам нужно нарушить порядок таблиц в бэкапе.
     * Правильный порядок: users -> user_profiles (сначала родитель, потом ребенок)
     * Неправильный порядок: user_profiles -> users (сначала ребенок, потом родитель)</p>
     * 
     * <p>При восстановлении в этом неправильном порядке FK violation гарантирован.</p>
     */
    @Test
    @DisplayName("Восстановление в STRICT режиме с нарушением FK (должно упасть)")
    void restoreWithFKViolationStrict() throws IOException {
        System.out.println("\n=== Тест: Нарушение FK (STRICT режим) ===");

        // Создаем данные для бэкапа
        User user1 = User.builder()
                .username(dataPrefix + "user_fk_violation")
                .email(dataPrefix + "fk_violation@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);

        UserProfile profile1 = UserProfile.builder()
                .user(savedUser1)
                .bio("Test user for FK violation")
                .avatarUrl("https://example.com/fk_violation.jpg")
                .build();
        UserProfile savedProfile1 = userProfileRepository.save(profile1);
        savedUser1.setUserProfile(savedProfile1);
        savedUser1.setProducts(new ArrayList<>());
        savedUser1.setOrders(new ArrayList<>());
        userRepository.save(savedUser1);

        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь до бэкапа");
        assertEquals(1, userProfileRepository.count(), "Должен быть 1 профиль до бэкапа");

        // Создаем бэкап
        List<String> tablesToBackup = List.of("users", "user_profiles");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);

        // Восстанавливаем BackupFile изEnvelope
        BackupFile backupFile = envelope.backup();
        
        // Создаем backup с НЕПРАВИЛЬНЫМ порядком: сначала child (user_profiles), потом parent (users)
        List<String> wrongOrder = List.of("user_profiles", "users"); // сначала дочерняя!
        BackupFile backupWithWrongOrder = new BackupFile(
                backupFile.database(),
                backupFile.engine(),
                backupFile.schema(),
                backupFile.data(),
                wrongOrder  // ЗАДАЕМ НЕПРАВИЛЬНЫЙ ПОРЯДОК
        );
        
        // Создаем новый envelope с измененным порядком
        BackupEnvelope envelopeWithWrongOrder = new BackupEnvelope(envelope.report(), backupWithWrongOrder);
        
        // Сохраняем модифицированный бэкап
        String backupNameWithWrongOrder = backupFacade.storeBackup(envelopeWithWrongOrder, databaseName);
        System.out.println("Бэкап сохранен с неправильным порядком таблиц: " + backupNameWithWrongOrder);
        
        // Вместо оригинального backupName используем модифицированный
        // ===== ИЗМЕНЕННО: Конец изменений =====

        System.out.println("Очищаем БД перед восстановлением с FK нарушениями...");

        // Очищаем данные, чтобы нарушить FK при восстановлении
        userProfileRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        assertEquals(0, userRepository.count(), "Должно быть 0 пользователей после очистки");
        assertEquals(0, userProfileRepository.count(), "Должно быть 0 профилей после очистки");

        // Восстанавливаем данные в STRICT режиме
        // STRICT режим должен выбросить исключение при нарушении FK
        assertThrows(Exception.class, () -> {
            restoreService.restoreToExistingDatabase(
                    credentials,
                    BackupTier.DAILY,
                    backupNameWithWrongOrder,
                    databaseName,
                    RestoreMode.STRICT,
                    tablesToBackup
            );
        }, "STRICT режим должен выбросить исключение при нарушении FK");

        System.out.println("=== Тест FK нарушений пройден успешно (исключение выброшено) ===");
    }

    /**
     * Тест STRICT режима с массовой ошибкой при вставке
     */
    @Test
    @DisplayName("Восстановление в STRICT режиме с массовой ошибкой при вставке (должно упасть)")
    void restoreWithBatchErrorStrict() throws IOException {
        System.out.println("\n=== Тест: Массовая ошибка при вставке (STRICT режим) ===");

        // ===== Шаг 1: Создаем данные для бэкапа (3 пользователя) =====
        System.out.println("\n=== Шаг 1: Создание 3 пользователей для бэкапа ===");

        User user1 = User.builder()
                .username(dataPrefix + "user_batch_1")
                .email(dataPrefix + "batch1@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);
        userRepository.flush(); // Принудительно сбрасываем кэш JPA в БД

        User user2 = User.builder()
                .username(dataPrefix + "user_batch_2")
                .email(dataPrefix + "batch2@example.com")
                .build();
        User savedUser2 = userRepository.save(user2);
        userRepository.flush();

        User user3 = User.builder()
                .username(dataPrefix + "user_batch_3")
                .email(dataPrefix + "batch3@example.com")
                .build();
        User savedUser3 = userRepository.save(user3);
        userRepository.flush();

        // Проверяем, что все 3 пользователя сохранены
        assertEquals(3, userRepository.count(), "Должно быть 3 пользователя до бэкапа");

        // ===== Шаг 2: Создаем бэкап =====
        System.out.println("\n=== Шаг 2: Создание бэкапа с 3 пользователями ===");
        List<String> tablesToBackup = List.of("users");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        // ===== Шаг 3: Очищаем БД перед восстановлением =====
        System.out.println("\n=== Шаг 3: Очистка БД перед первым восстановлением ===");
        userRepository.deleteAllInBatch();
        assertEquals(0, userRepository.count(), "Должно быть 0 пользователей перед первым восстановлением");

        // ===== Шаг 4: Восстанавливаем данные в пустую БД =====
        System.out.println("\n=== Шаг 4: Первое восстановление (все данные новые, БД была пустая) ===");
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.STRICT,
                tablesToBackup
        );

        assertNotNull(restoreReport);
        assertEquals(RestoreStatus.SUCCESS, restoreReport.status(),
                "Статус должен быть SUCCESS при первом восстановлении");

        RestoreSummary summary1 = restoreReport.summary();
        assertEquals(1, summary1.tablesProcessed(), "Должна быть обработана 1 таблица");
        assertEquals(0, summary1.tablesSkipped(), "Не должно быть пропущенных таблиц");
        assertEquals(0, summary1.tablesFailed(), "Не должно быть ошибок");

        // Все 3 строки должны быть вставлены (БД была пустая)
        assertEquals(3, summary1.rowsInserted(), "Должно быть вставлено 3 строки (БД была пустая)");
        assertEquals(0, summary1.rowsSkipped(), "Не должно быть пропущенных строк при первом восстановлении");

        System.out.println("Первое восстановление: rowsInserted=" + summary1.rowsInserted() + ", rowsSkipped=" + summary1.rowsSkipped());
        assertEquals(3, userRepository.count(), "Должно быть 3 пользователя после первого восстановления");

        // ===== Шаг 5: Восстанавливаем повторно (все данные уже существуют) =====
        System.out.println("\n=== Шаг 5: Второе восстановление (все данные уже существуют, должно упасть) ===");

        System.out.println("Ожидание: исключение при дубликатах (строки уже существуют)");

        // Восстанавливаем повторно - должно упасть из-за дубликатов
        assertThrows(Exception.class, () -> {
            restoreService.restoreToExistingDatabase(
                    credentials,
                    BackupTier.DAILY,
                    backupName,
                    databaseName,
                    RestoreMode.STRICT,
                    tablesToBackup
            );
        }, "STRICT режим должен выбросить исключение при дубликатах");

        System.out.println("=== Тест массовой ошибки пройден успешно (исключение выброшено) ===");
    }

    /**
     * <p>Тест STRICT режима с "лишними" колонками в БД (должно упасть)</p>
     * 
     * Сценарий:
     * 1. Создаем бэкап с полными данными (включая колонку email)
     * 2. Восстанавливаем бэкап в пустую БД (успешно)
     * 3. Добавляем колонку в БД, которой нет в бэкапе
     * 4. Пытаемся восстановить бэкап снова - должен упасть из-за лишней колонки в БД
     */
    @Test
    @DisplayName("Восстановление в STRICT режиме с лишними колонками в БД (должно упасть)")
    void restoreWithExtraColumnsStrict() throws IOException {
        System.out.println("\n=== Тест: ЛИшние колонки в БД (STRICT режим) ===");

        // Создаем данные для бэкапа
        User user1 = User.builder()
                .username(dataPrefix + "user_extra_cols")
                .email(dataPrefix + "extra_cols@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);

        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь до бэкапа");

        // Создаем бэкап с полными данными (включая колонку email)
        List<String> tablesToBackup = List.of("users");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        System.out.println("Очищаем БД перед восстановлением...");
        userRepository.deleteAllInBatch();
        assertEquals(0, userRepository.count(), "Должно быть 0 пользователей перед восстановлением");

        // Первое восстановление - должно успешно
        System.out.println("Первое восстановление в STRICT режиме (должно быть успешно)...");
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.STRICT,
                List.of("users")
        );
        
        assertEquals(RestoreStatus.SUCCESS, restoreReport.status(), "Первое восстановление должно быть успешно");
        assertEquals(1, userRepository.count(), "После первого восстановления должен быть 1 пользователь");
        
        // Добавляем колонку в БД, которой нет в бэкапе
        System.out.println("Добавляем колонку extra_field в БД, которой нет в бэкапе...");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        databaseConfigResolver.resolveCredentials(databaseName).url(),
                        databaseConfigResolver.resolveCredentials(databaseName).username(),
                        databaseConfigResolver.resolveCredentials(databaseName).password()
                )
        );
        
        try {
            jdbcTemplate.execute("ALTER TABLE users ADD COLUMN extra_field VARCHAR(255)");
            System.out.println("Колонка extra_field успешно добавлена в таблицу users");
        } catch (Exception e) {
            System.out.println("Ошибка при добавлении колонки extra_field: " + e.getMessage());
            throw new AssertionError("Не удалось добавить колонку extra_field", e);
        }
        
        // Проверяем, что колонка действительно добавлена
        List<String> columns = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?",
                String.class,
                databaseName, "users"
        );
        
        assertTrue(columns.contains("extra_field"), "Колонка extra_field должна быть добавлена");
        System.out.println("Доступные колонки в таблице users: " + columns);

        System.out.println("Пытаемся восстановить данные в STRICT режиме с расхождением схемы...");
        System.out.println("Ожидание: исключение из-за наличия колонки extra_field в БД, но её нет в бэкапе");

        // Восстанавливаем данные в STRICT режиме
        // STRICT режим должен упасть из-за наличия колонки extra_field в БД (она есть в БД, но нет в бэкапе)
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            restoreService.restoreToExistingDatabase(
                    credentials,
                    BackupTier.DAILY,
                    backupName,
                    databaseName,
                    RestoreMode.STRICT,
                    List.of("users")
            );
        }, "STRICT режим должен выбросить IllegalStateException при расхождении схем (есть лишняя колонка в БД)");
        
        String errorMessage = exception.getMessage();
        System.out.println("Полученное исключение: " + errorMessage);
        assertTrue(errorMessage.contains("extra_field") || errorMessage.contains("Column"), 
                "Сообщение об ошибке должно содержать информацию о лишней колонке");

        System.out.println("=== Тест лишних колонок в БД пройден успешно (исключение выброшено) ===");
    }

    /**
     * Проверка всех сущностей перед бэкапом
     */
    private void checkAllEntitiesBeforeBackup(User user1, User user2, UserProfile profile1, UserProfile profile2,
                                              Category category1, Category category2,
                                              Product product1, Product product2, Product product3,
                                              Order order1, Order order2, ProductOrder productOrder1) {
        // Проверяем всех пользователей
        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя");
        User restoredUser1 = userRepository.findById(user1.getId()).orElseThrow();
        User restoredUser2 = userRepository.findById(user2.getId()).orElseThrow();
        assertEquals(user1.getUsername(), restoredUser1.getUsername());
        assertEquals(user2.getUsername(), restoredUser2.getUsername());

        // Проверяем профили
        assertEquals(2, userProfileRepository.count(), "Должно быть 2 профиля");
        UserProfile restoredProfile1 = userProfileRepository.findById(profile1.getId()).orElseThrow();
        UserProfile restoredProfile2 = userProfileRepository.findById(profile2.getId()).orElseThrow();
        assertEquals(profile1.getBio(), restoredProfile1.getBio());
        assertEquals(profile2.getBio(), restoredProfile2.getBio());

        // Проверяем категории
        assertEquals(2, categoryRepository.count(), "Должно быть 2 категории");
        Category restoredCategory1 = categoryRepository.findById(category1.getId()).orElseThrow();
        Category restoredCategory2 = categoryRepository.findById(category2.getId()).orElseThrow();
        assertEquals(category1.getName(), restoredCategory1.getName());
        assertEquals(category2.getName(), restoredCategory2.getName());

        // Проверяем продукты
        assertEquals(3, productRepository.count(), "Должно быть 3 продукта");
        Product restoredProduct1 = productRepository.findById(product1.getId()).orElseThrow();
        Product restoredProduct2 = productRepository.findById(product2.getId()).orElseThrow();
        Product restoredProduct3 = productRepository.findById(product3.getId()).orElseThrow();
        assertEquals(product1.getName(), restoredProduct1.getName());
        assertEquals(product2.getName(), restoredProduct2.getName());
        assertEquals(product3.getName(), restoredProduct3.getName());

        // Проверяем заказы
        assertEquals(2, orderRepository.count(), "Должно быть 2 заказа");
        Order restoredOrder1 = orderRepository.findById(order1.getId()).orElseThrow();
        Order restoredOrder2 = orderRepository.findById(order2.getId()).orElseThrow();
        assertEquals(order1.getOrderNumber(), restoredOrder1.getOrderNumber());
        assertEquals(order2.getOrderNumber(), restoredOrder2.getOrderNumber());

        // Проверяем ProductOrder
        assertEquals(1, productOrderRepository.count(), "Должно быть 1 ProductOrder");
        ProductOrder restoredProductOrder1 = productOrderRepository.findById(productOrder1.getId()).orElseThrow();
        assertEquals(productOrder1.getQuantity(), restoredProductOrder1.getQuantity());
        assertEquals(productOrder1.getTotalPrice(), restoredProductOrder1.getTotalPrice());

        System.out.println("Все сущности успешно проверены перед бэкапом");
    }

    /**
     * Очистка всех тестовых данных из всех таблиц.
     * Очищает все таблицы в правильном порядке для соблюдения внешних ключей.
     */
    @Rollback(false)
    protected void cleanUpTestData() {
        // Очищаем таблицы в порядке, обратном зависимости (Many-to-Many сначала)
        productOrderRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        categoryRepository.deleteAllInBatch();
        userProfileRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        System.out.println("Все тестовые данные успешно очищены");
    }
}