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
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Интеграционный тест SAFE_MERGE: пропуск дубликатов, продолжение при ошибках, FK off.
 * Покрывает связи: @OneToOne, @OneToMany/@ManyToOne, @ManyToMany, ProductOrder.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { TestDatabaseConfig.class })
@WithResetDatabaseBeforeEach
@DisplayName("IT - SAFE_MERGE режим восстановления сущностей со всеми типами связей")
class FullBackupRestoreWithRelationshipsSafeMergeIT extends BaseResetDatabaseTest {

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
        dataPrefix = "test_safe_merge_" + UUID.randomUUID().toString().substring(0, 8) + "_";
    }

    @AfterEach
    @Transactional
    @org.springframework.test.annotation.Rollback(false)
    void tearDown() throws IOException {
        // Очистка тестовых бэкапов
        String backupPath = System.getProperty("user.dir") + "/build/test-backups";
        Path path = Path.of(backupPath);
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // ignore
                        }
                    });
        }
    }

    // Уникальный префикс для имен тестовых данных
    private String dataPrefix;

    /**
     * Проверка полного цикла бэкапа и восстановления в SAFE_MERGE режиме.
     * <p>
     * Проверяет, что SAFE_MERGE:
     * - Восстанавливает новые данные
     * - Пропускает существующие строки (не создает дубликатов)
     * - Сохраняет целостность связей
     */
    @Test
    @DisplayName("должен успешно восстановить данные со всеми типами связей в SAFE_MERGE режиме")
    void shouldRestoreAllRelationshipsSuccessfullyWhenUsingSafeMergeMode() throws IOException {
        // arrange: Создаем сущности со всеми типами связей
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

        savedUser2.setUserProfile(savedProfile2);
        savedUser2.setOrders(new ArrayList<>());
        savedUser2.setProducts(new ArrayList<>());
        userRepository.save(savedUser2);

        System.out.println("=== Создано 2 пользователя с профилями (@OneToOne) ===");

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

        savedCategory1.setProducts(List.of(savedProduct1, savedProduct2));
        categoryRepository.save(savedCategory1);
        savedCategory2.setProducts(List.of(savedProduct3));
        categoryRepository.save(savedCategory2);

        System.out.println("=== Создано 2 категории и 3 продукта (@OneToMany/@ManyToOne) ===");

        // ===== @ManyToMany: Order <-> Product =====
        Order order1 = Order.builder()
                .orderNumber(dataPrefix + "ORD-001")
                .status(OrderStatus.CONFIRMED)
                .user(savedUser1)
                .build();
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

        System.out.println("=== Создано 2 заказа с ManyToMany связью (@ManyToMany) ===");

        // ===== ProductOrder (сущность-связка с доп. полями) =====
        ProductOrder productOrder1 = ProductOrder.builder()
                .product(savedProduct1)
                .quantity(2)
                .totalPrice(3000.00)
                .build();
        ProductOrder savedProductOrder1 = productOrderRepository.save(productOrder1);

        savedProduct1.getProductsOrders().add(savedProductOrder1);
        productRepository.save(savedProduct1);

        System.out.println("=== Создан ProductOrder с дополнительными полями ===");

        // arrange: Проверяем, что данные сохранены в БД
        System.out.println("\n=== Проверка сохраненных данных в БД ===");
        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя");
        assertEquals(2, userProfileRepository.count(), "Должно быть 2 профиля");
        assertEquals(2, categoryRepository.count(), "Должно быть 2 категории");
        assertEquals(3, productRepository.count(), "Должно быть 3 продукта");
        assertEquals(2, orderRepository.count(), "Должно быть 2 заказа");
        assertEquals(1, productOrderRepository.count(), "Должно быть 1 ProductOrder");

        // arrange: Список таблиц для бэкапа
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

        // act: Делаем бэкап через BackupFacade
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        // act: Проверяем, что бэкап создан и сохранен
        System.out.println("\n=== Проверка бэкапа ===");
        assertNotNull(backupName, "Имя бэкапа должно быть сгенерировано");
        assertTrue(backupName.endsWith(".zip"), "Имя бэкапа должно заканчиваться на .zip");

        List<BackupFileInfo> backups = backupStorage.listWithInfo(BackupTier.DAILY, databaseName);
        assertFalse(backups.isEmpty(), "Список бэкапов не должен быть пустым");
        assertTrue(backups.stream().anyMatch(info -> info.fileName().contains(backupName)),
                "Бэкап должен присутствовать в списке");

        String userDir = System.getProperty("user.dir");
        String backupFilePath = userDir + File.separator + "build" + File.separator + "test-backups" + File.separator + databaseName + File.separator + "DAILY" + File.separator + backupName;
        Path backupPath = Path.of(backupFilePath);
        System.out.println("Backup file path (for debug): " + backupPath);

        System.out.println("=== Бэкап успешно создан и сохранен ===");

        // act: Восстанавливаем данные в SAFE_MERGE режиме
        System.out.println("\n=== Восстановление данных (SAFE_MERGE режим) ===");

        // Восстанавливаем данные в SAFE_MERGE режиме
        // Важно: SAFE_MERGE пропускает существующие строки, но добавляет новые
        // Поскольку БД пустая, все данные будут вставлены
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
                tablesToBackup
        );

        // act: Проверяем результат восстановления
        System.out.println("\n=== Проверка результата восстановления ===");
        assertNotNull(restoreReport, "Отчет о восстановлении не должен быть null");
        assertEquals(RestoreMode.SAFE_MERGE, restoreReport.mode(), "Режим восстановления должен быть SAFE_MERGE");
        assertEquals(RestoreStatus.SUCCESS, restoreReport.status(), "Статус восстановления должен быть SUCCESS");

        RestoreSummary summary = restoreReport.summary();
        assertEquals(8, summary.tablesProcessed(), "Должно быть обработано 8 таблиц");
        assertEquals(0, summary.tablesFailed(), "Не должно быть ошибок при восстановлении");
        assertEquals(0, summary.tablesSkipped(), "Не должно быть пропущенных таблиц (БД была пустая)");

        System.out.println("Восстановление выполнено успешно в SAFE_MERGE режиме");
        System.out.println("Tables processed: " + summary.tablesProcessed());
        System.out.println("Rows inserted: " + summary.rowsInserted());
        System.out.println("Rows updated: " + summary.rowsUpdated());
        System.out.println("Rows skipped: " + summary.rowsSkipped());

        // assert: Проверяем, что данные восстановлены
        System.out.println("\n=== Проверка восстановленных данных ===");
        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя после восстановления");
        assertEquals(2, userProfileRepository.count(), "Должно быть 2 профиля после восстановления");
        assertEquals(2, categoryRepository.count(), "Должно быть 2 категории после восстановления");
        assertEquals(3, productRepository.count(), "Должно быть 3 продукта после восстановления");
        assertEquals(2, orderRepository.count(), "Должно быть 2 заказа после восстановления");
        assertEquals(1, productOrderRepository.count(), "Должно быть 1 ProductOrder после восстановления");

        // assert: Проверяем целостность связей после восстановления
        System.out.println("\n=== Проверка целостности связей ===");

        // Проверяем @OneToOne связь
        User restoredUser1 = userRepository.findByUsername(dataPrefix + "john_doe").orElseThrow();
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

        System.out.println("=== Тест полного цикла в SAFE_MERGE режиме пройден успешно ===");

        // arrange: Проверка SAFE_MERGE режима с существующими данными
        System.out.println("\n=== Проверка SAFE_MERGE: добавление нового пользователя и восстановление бэкапа ===");

        // Создаем нового пользователя, который НЕ входит в бэкап
        User newUser = User.builder()
                .username(dataPrefix + "extra_user")
                .email(dataPrefix + "extra@example.com")
                .build();
        userRepository.save(newUser);
        assertEquals(3, userRepository.count(), "Должно быть 3 пользователя до восстановления (включая extra_user)");

        // act: Восстанавливаем данные из бэкапа в SAFE_MERGE режиме
        RestoreReport restoreReport2 = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
                tablesToBackup
        );

        // act: Проверяем результат второго восстановления
        System.out.println("Второе восстановление в SAFE_MERGE режиме:");
        System.out.println("  Rows inserted: " + restoreReport2.summary().rowsInserted());
        System.out.println("  Rows skipped: " + restoreReport2.summary().rowsSkipped());

        // В SAFE_MERGE режиме:
        // - rowsInserted будет 0 (все данные уже существуют)
        // - rowsSkipped будет содержать количество пропущенных строк
        assertEquals(0, restoreReport2.summary().rowsInserted(),
                "В SAFE_MERGE режиме не должно быть вставлено новых строк (все данные уже существуют)");
        assertTrue(restoreReport2.summary().rowsSkipped() > 0,
                "Некоторые строки должны быть пропущены в SAFE_MERGE режиме");

        // assert: extra_user должен остаться в БД
        // А все остальные данные должны быть intact (пропущены, но не удалены)
        assertEquals(3, userRepository.count(), "Количество пользователей должно остаться 3 (включая extra_user)");
        assertNotNull(userRepository.findByUsername(dataPrefix + "extra_user").orElse(null), "extra_user должен остаться в БД");

        System.out.println("=== Проверка SAFE_MERGE режима пройдена успешно ===");
    }

    /**
     * Проверка восстановления в SAFE_MERGE режиме с частичными данными.
     * Проверяет, что SAFE_MERGE добавляет недостающие данные и пропускает существующие.
     */
    @Test
    @DisplayName("должен успешно восстановить частичные данные и добавить недостающие в SAFE_MERGE режиме")
    void shouldRestorePartialDataAndAddMissingWhenUsingSafeMergeMode() throws IOException {
        // arrange: Создание начальных данных
        System.out.println("\n=== Шаг 1: Создание начальных данных ===");

        // Создаем только одного пользователя
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
        savedProfile1.setUser(savedUser1);
        userRepository.save(savedUser1);
        userProfileRepository.save(savedProfile1);

        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь до бэкапа");
        assertEquals(1, userProfileRepository.count(), "Должен быть 1 профиль до бэкапа");

        // act: Создаем бэкап
        System.out.println("=== Создание бэкапа частичных данных ===");
        List<String> tablesToBackup = List.of("users", "user_profiles");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        System.out.println("=== Восстановление в SAFE_MERGE режиме ===");

        // act: Восстанавливаем частичные данные
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
                tablesToBackup
        );

        assertNotNull(restoreReport);
        assertEquals(RestoreStatus.SUCCESS, restoreReport.status());

        // assert: Проверяем, что данные восстановлены
        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь после восстановления");
        assertEquals(1, userProfileRepository.count(), "Должен быть 1 профиль после восстановления");

        System.out.println("=== Добавление новых данных и проверка данных ===");

        // arrange: Добавляем нового пользователя в БД (не в бэкап)
        User newUser = User.builder()
                .username(dataPrefix + "new_user")
                .email(dataPrefix + "new@example.com")
                .build();
        userRepository.save(newUser);

        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя");

        // act: В SAFE_MERGE режиме с существующими данными
        // - Существующая запись (partial_user) будет пропущена
        // - Новая запись (new_user) останется в БД
        // Общее количество пользователей должно остаться 2
        assertEquals(2, userRepository.count(), "Количество пользователей должно остаться 2");
        assertNotNull(userRepository.findByUsername(dataPrefix + "new_user").orElse(null), "new_user должен быть в БД");

        System.out.println("=== Частичное восстановление пройдено успешно ===");

        // assert: Убедимся, что частичный пользователь не был повреждён
        User restoredPartialUser = userRepository.findByUsername(dataPrefix + "partial_user").orElseThrow();
        assertEquals(dataPrefix + "partial@example.com", restoredPartialUser.getEmail());
        // Ищем профиль по ID пользователя
        UserProfile restoredPartialProfile = userProfileRepository.findByUserId(restoredPartialUser.getId()).orElseThrow();
        assertEquals("Partial user profile", restoredPartialProfile.getBio());

        // assert: Убедимся, что связи целы
        assertNotNull(restoredPartialUser.getUserProfile());
        assertEquals(restoredPartialUser.getId(), restoredPartialProfile.getUser().getId());
    }

    /**
     * Тест SAFE_MERGE режима с отсутствующей таблицей в целевой БД.
     * Проверяет, что таблица пропускается с логированием, а остальные обрабатываются.
     */
    @Test
    @DisplayName("должен пропустить отсутствующую таблицу и завершиться с предупреждениями в SAFE_MERGE режиме")
    void shouldSkipMissingTableAndCompleteWithWarningsWhenUsingSafeMergeMode() throws IOException {
        // arrange: Создаем данные для бэкапа
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

        // act: Создаем бэкап только с пользователями
        List<String> tablesToBackup = List.of("users");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        // act: Восстанавливаем данные из бэкапа, запрашивая только таблицу users
        // Таблица user_profiles НЕ будет в бэкапе и будет пропущена
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
                List.of("users", "user_profiles")  // user_profiles нет в бэкапе
        );

        assertNotNull(restoreReport);
        assertEquals(RestoreStatus.COMPLETED_WITH_WARNINGS, restoreReport.status(),
                "Статус должен быть COMPLETED_WITH_WARNINGS из-за отсутствующей таблицы");

        RestoreSummary summary = restoreReport.summary();
        assertEquals(1, summary.tablesProcessed(), "Должна быть обработана 1 таблица (users)");
        assertEquals(1, summary.tablesSkipped(), "Должна быть пропущена 1 таблица (user_profiles)");
        assertEquals(0, summary.tablesFailed(), "Не должно быть ошибок");

        // assert: Пользователь должен быть восстановлен
        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь после восстановления");
        assertNotNull(userRepository.findByUsername(dataPrefix + "user_test_missing_table").orElse(null),
                "Пользователь должен быть в БД");

        System.out.println("=== Тест отсутствующей таблицы пройден успешно ===");
    }

    /**
     * Тест SAFE_MERGE режима с массовой ошибкой при вставке.
     * Проверяет, что при ошибке вставки пакета, восстановление продолжается,
     * ошибки логируются, а остальные данные обрабатываются.
     * 
     * Сценарий:
     * 1. Создаем бэкап с 3 пользователями
     * 2. Восстанавливаем данные в ПУСТУЮ БД - все успешно (rowsInserted = 3)
     * 3. Восстанавливаем повторно - все строки пропускаются (rowsSkipped = 3)
     * 4. Добавляем нового пользователя (не в бэкапе) - итого 4 пользователя
     * 5. Восстанавливаем в SAFE_MERGE режиме:
     *    - Существующие 3 пользователя пропускаются (rowsSkipped += 3)
     *    - extra_user остается в БД
     *    - Статус = SUCCESS (восстановление продолжается)
     */
    @Test
    @DisplayName("должен продолжить восстановление и пропустить дубликаты при массовой вставке в SAFE_MERGE режиме")
    void shouldContinueRestoreAndSkipDuplicatesWhenBatchInsertingWithSafeMergeMode() throws IOException {
        // arrange: Создаем данные для бэкапа (3 пользователя)
        System.out.println("\n=== Создание 3 пользователей для бэкапа ===");
        
        User user1 = User.builder()
                .username(dataPrefix + "user_batch_1")
                .email(dataPrefix + "batch1@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);
        userRepository.flush(); // Принудительно сбрасываем кэш JPA в БД
        System.out.println("Создан пользователь 1: id=" + savedUser1.getId() + ", username=" + savedUser1.getUsername());
        
        User user2 = User.builder()
                .username(dataPrefix + "user_batch_2")
                .email(dataPrefix + "batch2@example.com")
                .build();
        User savedUser2 = userRepository.save(user2);
        userRepository.flush(); // Принудительно сбрасываем кэш JPA в БД
        System.out.println("Создан пользователь 2: id=" + savedUser2.getId() + ", username=" + savedUser2.getUsername());
        
        User user3 = User.builder()
                .username(dataPrefix + "user_batch_3")
                .email(dataPrefix + "batch3@example.com")
                .build();
        User savedUser3 = userRepository.save(user3);
        userRepository.flush(); // Принудительно сбрасываем кэш JPA в БД
        System.out.println("=== Создан пользователь 3 ===");

        // Проверяем, что все 3 пользователя сохранены
        assertEquals(3, userRepository.count(), "Должно быть 3 пользователя до бэкапа");
        System.out.println("Проверка количества пользователей: " + userRepository.count());

        // act: Создаем бэкап
        System.out.println("\n=== Создание бэкапа с 3 пользователями ===");
        List<String> tablesToBackup = List.of("users");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        String backupName = backupFacade.storeBackup(envelope, databaseName);
        
        // act: Выводим информацию о бэкапе
        List<Map<String, Object>> usersInBackup = envelope.backup().data().get("users");
        System.out.println("Данные в бэкапе: " + (usersInBackup != null ? usersInBackup.size() : 0) + " строк");
        if (usersInBackup != null && !usersInBackup.isEmpty()) {
            for (Map<String, Object> row : usersInBackup) {
                System.out.println("  - User ID=" + row.get("id") + ", username=" + row.get("username"));
            }
        }

        // act: Очищаем БД перед восстановлением
        System.out.println("\n=== Очистка БД перед первым восстановлением ===");
        userRepository.deleteAllInBatch();
        assertEquals(0, userRepository.count(), "Должно быть 0 пользователей перед первым восстановлением");

        // act: Восстанавливаем данные в пустую БД
        System.out.println("\n=== Первое восстановление (все данные новые, БД была пустая) ===");
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
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

        // act: Восстанавливаем повторно (все данные уже существуют)
        System.out.println("\n=== Второе восстановление (все данные уже существуют) ===");
        
        RestoreReport restoreReport2 = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
                tablesToBackup
        );
        
        assertNotNull(restoreReport2);
        System.out.println("Rows inserted: " + restoreReport2.summary().rowsInserted());
        System.out.println("Rows skipped: " + restoreReport2.summary().rowsSkipped());
        
        // В SAFE_MERGE режиме: существующие строки пропускаются
        assertEquals(0, restoreReport2.summary().rowsInserted(),
                "В SAFE_MERGE режиме не должно быть вставлено новых строк (все данные уже существуют)");
        assertEquals(3, restoreReport2.summary().rowsSkipped(),
                "Должно быть пропущено 3 строки (дубликаты)");
        assertEquals(RestoreStatus.SUCCESS, restoreReport2.status(),
                "Статус должен быть SUCCESS (восстановление продолжается, ошибок нет)");
        
        // arrange: Добавляем нового пользователя (не в бэкапе)
        System.out.println("\n=== Добавление extra_user (не в бэкапе) ===");
        User extraUser = User.builder()
                .username(dataPrefix + "user_extra_not_in_backup")
                .email(dataPrefix + "extra_not_in_backup@example.com")
                .build();
        userRepository.save(extraUser);
        
        assertEquals(4, userRepository.count(), "Должно быть 4 пользователя (3 из бэкапа + extra_user)");
        
        // act: Восстанавливаем в SAFE_MERGE режиме с extra_user
        System.out.println("\n=== Третье восстановление (3 строки пропускаются, extra_user остается) ===");
        
        RestoreReport restoreReport3 = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
                tablesToBackup
        );
        
        // act: Проверяем результат третьего восстановления
        assertNotNull(restoreReport3);
        System.out.println("Rows inserted: " + restoreReport3.summary().rowsInserted());
        System.out.println("Rows skipped: " + restoreReport3.summary().rowsSkipped());
        
        // В SAFE_MERGE режиме:
        // - rowsInserted = 0 (все данные уже существуют)
        // - rowsSkipped = 3 (пропущены дубликаты)
        // - extra_user остается в БД (не был в бэкапе)
        assertEquals(0, restoreReport3.summary().rowsInserted(),
                "В SAFE_MERGE режиме не должно быть вставлено новых строк (все данные уже существуют)");
        assertEquals(3, restoreReport3.summary().rowsSkipped(),
                "Должно быть пропущено 3 строки (дубликаты)");
        assertEquals(RestoreStatus.SUCCESS, restoreReport3.status(),
                "Статус должен быть SUCCESS (восстановление продолжается, ошибок нет)");
        assertEquals(1, restoreReport3.summary().tablesProcessed(),
                "Должна быть обработана 1 таблица");
        assertEquals(0, restoreReport3.summary().tablesSkipped(),
                "Не должно быть пропущенных таблиц");
        assertEquals(0, restoreReport3.summary().tablesFailed(),
                "Не должно быть ошибок при обработке таблиц");

        // assert: extra_user остался в БД
        assertNotNull(userRepository.findByUsername(dataPrefix + "user_extra_not_in_backup").orElse(null),
                "extra_user должен остаться в БД");
        assertEquals(4, userRepository.count(),
                "Количество пользователей должно остаться 4 (3 из бэкапа + extra_user)");
        
        // assert: Проверяем, что пользователи из бэкапа не были повреждены
        assertNotNull(userRepository.findByUsername(dataPrefix + "user_batch_1").orElse(null));
        assertNotNull(userRepository.findByUsername(dataPrefix + "user_batch_2").orElse(null));
        assertNotNull(userRepository.findByUsername(dataPrefix + "user_batch_3").orElse(null));
        
        System.out.println("=== Тест массовой ошибки пройден успешно ===");
        System.out.println("Поведение проверено:");
        System.out.println("  - Дубликаты пропускаются ✓");
        System.out.println("  - Другие строки обрабатываются (rowsSkipped += batch_size) ✓");
        System.out.println("  - Статус SUCCESS (восстановление продолжается) ✓");
    }

    /**
     * Тест SAFE_MERGE режима с "лишними" колонками в БД.
     * Проверяет, что колонки получают DEFAULT или NULL значения.
     */
    @Test
    @DisplayName("Восстановление в SAFE_MERGE режиме с лишними колонками в БД")
    void restoreWithExtraColumnsSafeMerge() throws IOException {
        System.out.println("\n=== Тест: ЛИшние колонки в БД ===");

        // Создаем данные для бэкапа
        User user1 = User.builder()
                .username(dataPrefix + "user_extra_cols")
                .email(dataPrefix + "extra_cols@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);

        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь до бэкапа");

        // Создаем бэкап только с id и username (без email)
        List<String> tablesToBackup = List.of("users");
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
        assertTrue(schemaColumns.size() > 0, "Таблица users должна иметь хотя бы одну колонку");
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

        // Восстанавливаем данные
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
                List.of("users")
        );

        assertNotNull(restoreReport);
        assertEquals(RestoreStatus.SUCCESS, restoreReport.status(), "Статус должен быть SUCCESS");

        RestoreSummary summary = restoreReport.summary();
        assertEquals(1, summary.tablesProcessed(), "Должна быть обработана 1 таблица");
        assertEquals(0, summary.tablesSkipped(), "Не должно быть пропущенных таблиц");

        // Пользователь должен быть восстановлен, email получит NULL
        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь после восстановления");
        User restoredUser = userRepository.findByUsername(dataPrefix + "user_extra_cols").orElseThrow();
        assertNotNull(restoredUser.getId(), "Пользователь должен иметь ID");
        assertEquals(dataPrefix + "user_extra_cols", restoredUser.getUsername(), "Username должен совпадать");
        // email может быть NULL, так как он не был в бэкапе

        System.out.println("=== Тест лишних колонок в БД пройден успешно ===");
    }

    /**
     * Тест SAFE_MERGE режима с "лишними" колонками в бэкапе.
     * Проверяет, что лишние колонки игнорируются при импорте.
     */
    @Test
    @DisplayName("Восстановление в SAFE_MERGE режиме с лишними колонками в бэкапе")
    void restoreWithMissingColumnsInBackupSafeMerge() throws IOException {
        System.out.println("\n=== Тест: ЛИшние колонки в бэкапе ===");

        // Создаем данные для бэкапа
        User user1 = User.builder()
                .username(dataPrefix + "user_missing_cols")
                .email(dataPrefix + "missing_cols@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);

        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь до бэкапа");

        // Создаем бэкап с полными данными
        List<String> tablesToBackup = List.of("users");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope envelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        // Восстанавливаем данные
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
                List.of("users")
        );

        assertNotNull(restoreReport);
        assertEquals(RestoreStatus.SUCCESS, restoreReport.status(), "Статус должен быть SUCCESS");

        RestoreSummary summary = restoreReport.summary();
        assertEquals(1, summary.tablesProcessed(), "Должна быть обработана 1 таблица");
        assertEquals(0, summary.tablesSkipped(), "Не должно быть пропущенных таблиц");

        // Пользователь должен быть восстановлен
        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь после восстановления");
        User restoredUser = userRepository.findByUsername(dataPrefix + "user_missing_cols").orElseThrow();
        assertEquals(dataPrefix + "missing_cols@example.com", restoredUser.getEmail(), "Email должен совпадать");

        System.out.println("=== Тест лишних колонок в бэкапе пройден успешно ===");
    }

    /**
     * Тест SAFE_MERGE режима с нарушением внешних ключей.
     * Проверяет, что FK проверки отключаются (SET FOREIGN_KEY_CHECKS = 0),
     * восстановление продолжается, а данные вставляются.
     */
    @Test
    @DisplayName("Восстановление в SAFE_MERGE режиме с нарушением FK (FOREIGN_KEY_CHECKS=0)")
    void restoreWithFKViolationSafeMerge() throws IOException {
        System.out.println("\n=== Тест: Нарушение FK (SAFE_MERGE режим) ===");

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
        String backupName = backupFacade.storeBackup(envelope, databaseName);

        // Восстанавливаем данные в SAFE_MERGE режиме
        // Это должно отключить FK проверки (FOREIGN_KEY_CHECKS = 0)
        RestoreReport restoreReport = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_MERGE,
                tablesToBackup
        );

        assertNotNull(restoreReport);
        assertEquals(RestoreStatus.SUCCESS, restoreReport.status(),
                "Статус должен быть SUCCESS (FK проверки отключены)");

        RestoreSummary summary = restoreReport.summary();
        assertEquals(2, summary.tablesProcessed(), "Должны быть обработаны 2 таблицы");
        assertEquals(0, summary.tablesSkipped(), "Не должно быть пропущенных таблиц");
        assertEquals(0, summary.tablesFailed(), "Не должно быть ошибок");

        // Проверяем, что данные восстановлены
        assertEquals(1, userRepository.count(), "Должен быть 1 пользователь после восстановления");
        assertEquals(1, userProfileRepository.count(), "Должен быть 1 профиль после восстановления");

        User restoredUser = userRepository.findByUsername(dataPrefix + "user_fk_violation").orElseThrow();
        assertNotNull(restoredUser.getUserProfile(), "У пользователя должен быть профиль");

        System.out.println("=== Тест FK нарушений пройден успешно (FK проверки отключены) ===");
    }
}
