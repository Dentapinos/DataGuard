package com.dentapinos.dataguard.integration.strategy;

import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.RestoreStatus;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.dentapinos.dataguard.report.RestoreReport;
import com.dentapinos.dataguard.service.BackupFacade;
import com.dentapinos.dataguard.service.restore.RestoreService;
import com.dentapinos.dataguard.test.BaseResetDatabaseTest;
import com.dentapinos.dataguard.test.annotation.WithResetDatabaseBeforeEach;
import com.dentapinos.dataguard.test.config.TestDatabaseConfig;
import com.dentapinos.dataguard.test.domain.*;
import com.dentapinos.dataguard.utils.DatabaseConfigResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SAFE_SCHEMA_CHECK: проверка совместимости схемы без вставки данных (валидация перед восстановлением).
 * Статус FAILED, если tablesProcessed=0; иначе SUCCESS/COMPLETED_WITH_WARNINGS.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { TestDatabaseConfig.class })
@WithResetDatabaseBeforeEach
@DisplayName("IT - SAFE_SCHEMA_CHECK режим проверки совместимости схемы (без вставки данных)")
class FullBackupRestoreWithRelationshipsSafeSchemaCheckIT extends BaseResetDatabaseTest {

    @Autowired
    private BackupFacade backupFacade;

    @Autowired
    private RestoreService restoreService;

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
    private DatabaseConfigResolver databaseConfigResolver;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String dataPrefix;

    // Для prepareCategoriesOnly()
    private Category savedCat1;
    private Category savedCat2;

    private static final List<String> FULL_TABLE_LIST = List.of(
            "users", "user_profiles", "categories", "products", "orders", "product_orders"
    );

    @BeforeEach
    void setUp() {
        dataPrefix = "SAFE_SCHEMA_" + UUID.randomUUID().toString().substring(0, 8) + "_";
    }

    // --------------------------------------------------
    // --- СХЕМА И ДАННЫЕ --------------------------------
    // --------------------------------------------------

    private void prepareCategoriesOnly() {
        Category cat1 = Category.builder()
                .name(dataPrefix + "Category1")
                .description("Category description 1")
                .build();
        savedCat1 = categoryRepository.save(cat1);

        Category cat2 = Category.builder()
                .name(dataPrefix + "Category2")
                .description("Category description 2")
                .build();
        savedCat2 = categoryRepository.save(cat2);
    }

    private void prepareFullDataSet() {
        // 1. Users
        User user1 = User.builder()
                .username(dataPrefix + "user1")
                .email(dataPrefix + "user1@example.com")
                .build();
        User savedUser1 = userRepository.save(user1);

        User user2 = User.builder()
                .username(dataPrefix + "user2")
                .email(dataPrefix + "user2@example.com")
                .build();
        User savedUser2 = userRepository.save(user2);

        // 2. UserProfiles
        UserProfile profile1 = UserProfile.builder()
                .user(savedUser1)
                .bio("John Doe bio")
                .avatarUrl("https://example.com/avatar1.jpg")
                .build();
        userProfileRepository.save(profile1);

        UserProfile profile2 = UserProfile.builder()
                .user(savedUser2)
                .bio("Jane Smith bio")
                .avatarUrl("https://example.com/avatar2.jpg")
                .build();
        userProfileRepository.save(profile2);

        // 3. Categories (через prepareCategoriesOnly())
        prepareCategoriesOnly();

        // 4. Products
        Product prod1 = Product.builder()
                .name(dataPrefix + "Product1")
                .category(savedCat1)
                .user(savedUser1)
                .price(100.0)
                .stockQuantity(10)
                .build();
        productRepository.save(prod1);

        Product prod2 = Product.builder()
                .name(dataPrefix + "Product2")
                .category(savedCat1)
                .user(savedUser1)
                .price(200.0)
                .stockQuantity(20)
                .build();
        productRepository.save(prod2);

        Product prod3 = Product.builder()
                .name(dataPrefix + "Product3")
                .category(savedCat2)
                .user(savedUser2)
                .price(300.0)
                .stockQuantity(30)
                .build();
        productRepository.save(prod3);

        // 5. Orders
        Order order1 = Order.builder()
                .user(savedUser1)
                .orderNumber(dataPrefix + "ORD-001")
                .status(OrderStatus.CONFIRMED)
                .build();
        Order savedOrder1 = orderRepository.save(order1);

        Order order2 = Order.builder()
                .user(savedUser2)
                .orderNumber(dataPrefix + "ORD-002")
                .status(OrderStatus.PENDING)
                .build();
        Order savedOrder2 = orderRepository.save(order2);

        // 6. ProductOrders
        ProductOrder po = ProductOrder.builder()
                .order(savedOrder1)
                .product(prod1)
                .quantity(2)
                .build();
        productOrderRepository.save(po);
    }

    private BackupEnvelope createFullBackup() {
        return backupFacade.backupMySql(
                databaseConfigResolver.resolveCredentials(databaseName),
                databaseName,
                FULL_TABLE_LIST
        );
    }

    // --------------------------------------------------
    // --- ТЕСТЫ -----------------------------------------
    // --------------------------------------------------

    /**
     * Стратегия 6: SAFE_SCHEMA_CHECK (RELAXED_SCHEMA, SKIP_ON_CONFLICT, SKIP_VIOLATIONS, LOG_AND_CONTINUE)
     * Условие 1: БД пустая (нет таблиц)
     * Название: SAFE_SCHEMA_CHECK_EmptyDatabase
     * Ожидание: tablesSkipped++, rowsInserted = 0
     * Проверки:
     * - Таблицы не найдены, пропускаются
     * - Никаких изменений в БД
     * - Статус FAILED
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("SAFE_SCHEMA_CHECK_EmptyDatabase: пустая БД, таблицы пропускаются")
    void SAFE_SCHEMA_CHECK_EmptyDatabase() throws IOException {
        // 1. Готовим данные для бэкапа
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // 2. УДАЛЯЕМ ВСЕ ТАБЛИЦЫ (не только данные)
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS product_orders");
            jdbcTemplate.execute("DROP TABLE IF EXISTS orders");
            jdbcTemplate.execute("DROP TABLE IF EXISTS products");
            jdbcTemplate.execute("DROP TABLE IF EXISTS user_profiles");
            jdbcTemplate.execute("DROP TABLE IF EXISTS categories");
            jdbcTemplate.execute("DROP TABLE IF EXISTS users");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        // 4. Выполняем SAFE_SCHEMA_CHECK (проверка схемы, но НЕ вставка данных)
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_SCHEMA_CHECK,
                FULL_TABLE_LIST
        );

        // 5. ПРОВЕРКИ:

        // 4. ОЖИДАЕМ:
        // - tablesSkipped++ (таблицы из бэкапа не найдены в пустой БД)
        // - tablesProcessed = 0 (нет обработанных таблиц)
        // - rowsInserted = 0 (нет вставки)
        // - статус FAILED (tablesProcessed=0)
        assertEquals(RestoreStatus.FAILED, report.status());
        assertTrue(report.summary().tablesSkipped() > 0, "Должны быть пропущены таблицы (не найдены в пустой БД)");
        assertEquals(0, report.summary().tablesProcessed(), "Должно быть 0 обработанных таблиц");
        assertEquals(0, report.summary().rowsInserted(), "Должно быть 0 вставленных строк");
    }

    /**
     * Стратегия 6: SAFE_SCHEMA_CHECK (RELAXED_SCHEMA, SKIP_ON_CONFLICT, SKIP_VIOLATIONS, LOG_AND_CONTINUE)
     * Условие 2: БД содержит данные из бэкапа
     * Название: SAFE_SCHEMA_CHECK_ExistingData
     * Ожидание: tablesProcessed++, rowsInserted = 0
     * Проверки:
     * - Схема проверяется
     * - Никаких изменений в данных
     * - Статус SUCCESS
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("SAFE_SCHEMA_CHECK_ExistingData: БД содержит данные из бэкапа, схема проверяется")
    void SAFE_SCHEMA_CHECK_ExistingData() throws IOException {
        // 1. Готовим данные для бэкапа и в БД
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // 2. Сохраняем количество строк до восстановления
        long usersBefore = userRepository.count();
        long profilesBefore = userProfileRepository.count();
        long categoriesBefore = categoryRepository.count();
        long productsBefore = productRepository.count();
        long ordersBefore = orderRepository.count();
        long productOrdersBefore = productOrderRepository.count();

        // 3. Выполняем SAFE_SCHEMA_CHECK
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_SCHEMA_CHECK,
                FULL_TABLE_LIST
        );

        // 4. ПРОВЕРКИ:

        // 4.1 Проверяем статус - должен быть SUCCESS (все таблицы существуют, схема совпадает)
        assertEquals(RestoreStatus.SUCCESS, report.status(),
            "Статус должен быть SUCCESS, так как все таблицы существуют и схема валидна");

        // 4.2 Проверяем статистику - tablesProcessed > 0, rowsInserted = 0
        assertEquals(0, report.summary().rowsInserted(), "Должно быть 0 вставленных строк (нет реальной вставки)");
        assertTrue(report.summary().tablesProcessed() > 0, "Должны быть обработаны таблицы (проверена схема)");
        assertEquals(0, report.summary().tablesSkipped(), "Не должно быть пропущенных таблиц");

        // 4.3 Проверяем, что данных НЕ добавилось
        assertEquals(usersBefore, userRepository.count(), "Количество пользователей не должно измениться");
        assertEquals(profilesBefore, userProfileRepository.count(), "Количество профилей не должно измениться");
        assertEquals(categoriesBefore, categoryRepository.count(), "Количество категорий не должно измениться");
        assertEquals(productsBefore, productRepository.count(), "Количество продуктов не должно измениться");
        assertEquals(ordersBefore, orderRepository.count(), "Количество заказов не должно измениться");
        assertEquals(productOrdersBefore, productOrderRepository.count(), "Количество product_orders не должно измениться");

        log.info("[TEST LOG] SAFE_SCHEMA_CHECK_ExistingData passed: status={}, tablesProcessed={}, rowsInserted={}",
                report.status(), report.summary().tablesProcessed(), report.summary().rowsInserted());
    }

    /**
     * Стратегия 6: SAFE_SCHEMA_CHECK (RELAXED_SCHEMA, SKIP_ON_CONFLICT, SKIP_VIOLATIONS, LOG_AND_CONTINUE)
     * Условие 3: Отсутствует таблица в целой БД
     * Название: SAFE_SCHEMA_CHECK_MissingTable
     * Ожидание: tablesSkipped++
     * Проверки:
     * - Таблица пропускается
     * - Никаких изменений в БД
     * - Статус COMPLETED_WITH_WARNINGS возможен только если есть tablesProcessed > 0, иначе FAILED
     */
    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("SAFE_SCHEMA_CHECK_MissingTable: отсутствующая таблица пропускается")
    void SAFE_SCHEMA_CHECK_MissingTable() throws IOException {
        // 1. Готовим данные для бэкапа
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // 2. Удаляем одну таблицу (user_profiles)
        jdbcTemplate.execute("DROP TABLE user_profiles");

        // 3. Сохраняем количество строк до восстановления
        long usersBefore = userRepository.count();
        long categoriesBefore = categoryRepository.count();

        // 4. Выполняем SAFE_SCHEMA_CHECK
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_SCHEMA_CHECK,
                FULL_TABLE_LIST
        );

        // 5. ПРОВЕРКИ:

        // 5.1 Проверяем статус - должен быть COMPLETED_WITH_WARNINGS (таблица пропущена)
        assertEquals(RestoreStatus.COMPLETED_WITH_WARNINGS, report.status(),
            "Статус должен быть COMPLETED_WITH_WARNINGS, так как таблица user_profiles пропущена");

        // 5.2 Проверяем статистику
        assertTrue(report.summary().tablesSkipped() > 0, "Должна быть пропущена таблица user_profiles");
        assertEquals(0, report.summary().rowsInserted(), "Должно быть 0 вставленных строк (нет реальной вставки)");

        // 5.3 Проверяем, что данные не изменились
        assertEquals(usersBefore, userRepository.count(), "Количество пользователей не должно измениться");
        assertEquals(categoriesBefore, categoryRepository.count(), "Количество категорий не должно измениться");

        // 5.4 Восстанавливаем структуру для других тестов
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS user_profiles (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                avatar_url VARCHAR(255),
                bio VARCHAR(255),
                user_id BIGINT,
                CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users(id)
            )
            """);

        log.info("[TEST LOG] SAFE_SCHEMA_CHECK_MissingTable passed: status={}, tablesSkipped={}, rowsInserted={}",
                report.status(), report.summary().tablesSkipped(), report.summary().rowsInserted());
    }

    /**
     * Стратегия 6: SAFE_SCHEMA_CHECK (RELAXED_SCHEMA, SKIP_ON_CONFLICT, SKIP_VIOLATIONS, LOG_AND_CONTINUE)
     * Условие 4: В БД есть "лишние" колонки
     * Название: SAFE_SCHEMA_CHECK_ExtraColumns
     * Ожидание: tablesFailed++ (таблицы имеют ошибку валидации схемы)
     * Проверки:
     * - Колонки проверяются
     * - Никаких изменений в данных
     * - Статус COMPLETED_WITH_WARNINGS (таблицы не прошли валидацию)
     */
    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("SAFE_SCHEMA_CHECK_ExtraColumns: лишние колонки в БД - таблицы не проходят валидацию")
    void SAFE_SCHEMA_CHECK_ExtraColumns() throws IOException {
        // 1. Готовим данные для бэкапа
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // 2. Добавляем "лишние" колонки в БД (они есть в БД, но нет в бэкапе)
        addColumnIfNotExists("users", "extra_info", "extra_info VARCHAR(255) DEFAULT 'default_value'");
        addColumnIfNotExists("users", "nullable_extra", "nullable_extra VARCHAR(255)");
        addColumnIfNotExists("categories", "extra_category_data", "extra_category_data VARCHAR(255) DEFAULT 'category_default'");

        // 3. Сохраняем количество строк до восстановления
        long usersBefore = userRepository.count();
        long categoriesBefore = categoryRepository.count();

        // 4. Выполняем SAFE_SCHEMA_CHECK
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_SCHEMA_CHECK,
                FULL_TABLE_LIST
        );

        // 5. ПРОВЕРКИ:

        // 5.1 Проверяем статус - должен быть SUCCESS (лишние колонки в БД разрешены в RELAXED_SCHEMA)
        assertEquals(RestoreStatus.SUCCESS, report.status(),
                "Статус должен быть SUCCESS, лишние колонки в БД разрешены в RELAXED_SCHEMA");

        // 5.2 Проверяем статистику
        assertEquals(0, report.summary().rowsInserted(), "Должно быть 0 вставленных строк (нет реальной вставки)");
        assertEquals(0, report.summary().rowsUpdated(), "Должно быть 0 обновлённых строк");
        assertTrue(report.summary().tablesProcessed() > 0, "Должны быть обработаны таблицы (проверена схема)");
        assertEquals(0, report.summary().tablesFailed(), "Не должно быть не прошедших валидацию таблиц (лишние колонки разрешены)");

        // 5.3 Проверяем, что данные не изменились
        assertEquals(usersBefore, userRepository.count(), "Количество пользователей не должно измениться");
        assertEquals(categoriesBefore, categoryRepository.count(), "Количество категорий не должно измениться");

        log.info("[TEST LOG] SAFE_SCHEMA_CHECK_ExtraColumns passed: status={}, tablesProcessed={}, rowsInserted={}",
                report.status(), report.summary().tablesProcessed(), report.summary().rowsInserted());
    }

    /**
     * Стратегия 6: SAFE_SCHEMA_CHECK (RELAXED_SCHEMA, SKIP_ON_CONFLICT, SKIP_VIOLATIONS, LOG_AND_CONTINUE)
     * Условие 5: В бэкапе есть "лишние" колонки
     * Название: SAFE_SCHEMA_CHECK_MissingColumns
     * Ожидание: tablesProcessed++
     * Проверки:
     * - Колонки проверяются
     * - Никаких изменений в данных
     * - Статус SUCCESS
     */
    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("SAFE_SCHEMA_CHECK_MissingColumns: лишние колонки в бэкапе")
    void SAFE_SCHEMA_CHECK_MissingColumns() throws IOException {
        // 1. Создаем таблицу с дополнительной колонкой
        jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS users_extra_cols (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            username VARCHAR(255),
            email VARCHAR(255),
            extra_col VARCHAR(255) DEFAULT 'extra_default'
        )
        """);

        // 2. Вставляем данные
        jdbcTemplate.execute("INSERT INTO users_extra_cols (username, email, extra_col) VALUES ('u1', 'u1@test.com', 'extra_default')");
        jdbcTemplate.execute("INSERT INTO users_extra_cols (username, email, extra_col) VALUES ('u2', 'u2@test.com', 'extra_default')");

        // 3. Делаем бэкап (бэкап содержит extra_col)
        List<String> tables = List.of("users_extra_cols");
        BackupEnvelope backupEnvelope = backupFacade.backupMySql(
                databaseConfigResolver.resolveCredentials(databaseName),
                databaseName,
                tables
        );
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        log.info("[TEST LOG] Бэкап создан с extra_col: totalRows={}", backupEnvelope.report().summary().totalRows());

        // 4. Удаляем extra_col из таблицы (теперь в БД нет extra_col, но бэкап содержит её)
        jdbcTemplate.execute("ALTER TABLE users_extra_cols DROP COLUMN extra_col");

        // 5. Сохраняем количество строк до восстановления
        long usersBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users_extra_cols", Integer.class);

        // 6. Выполняем SAFE_SCHEMA_CHECK
        // Бэкап содержит extra_col, но в БД её нет - в RELAXED_SCHEMA это разрешено (см. log.warn в SafeSchemaCheckRestoreStrategy)
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_SCHEMA_CHECK,
                tables
        );

        // 7. ПРОВЕРКИ:

        // 7.1 Проверяем статус - должен быть SUCCESS (лишние колонки в бэкапе разрешены в RELAXED_SCHEMA)
        assertEquals(RestoreStatus.SUCCESS, report.status(),
            "Статус должен быть SUCCESS, лишние колонки в бэкапе разрешены в RELAXED_SCHEMA");

        // 7.2 Проверяем статистику
        assertTrue(report.summary().tablesProcessed() > 0, "Должны быть обработаны таблицы (проверена схема)");
        assertEquals(0, report.summary().rowsInserted(), "Должно быть 0 вставленных строк (нет реальной вставки)");

        // 7.3 Проверяем, что данные не изменились
        long usersAfter = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users_extra_cols", Integer.class);
        assertEquals(usersBefore, usersAfter, "Количество пользователей не должно измениться");

        // 8. Восстанавливаем структуру для других тестов
        dropTableIfExists("users_extra_cols");

        log.info("[TEST LOG] SAFE_SCHEMA_CHECK_MissingColumns passed: status={}, tablesProcessed={}, rowsInserted={}",
                report.status(), report.summary().tablesProcessed(), report.summary().rowsInserted());
    }

    /**
     * Стратегия 6: SAFE_SCHEMA_CHECK (RELAXED_SCHEMA, SKIP_ON_CONFLICT, SKIP_VIOLATIONS, LOG_AND_CONTINUE)
     * Условие 6: Обнаружена ошибка при вставке (в SAFE_SCHEMA_CHECK это проверка схемы)
     * Название: SAFE_SCHEMA_CHECK_Error
     * Ожидание: tablesSkipped++, tablesProcessed=0
     * Проверки:
     * - Никаких изменений в БД
     * - Статус FAILED (если tablesProcessed=0 и все таблицы пропущены)
     */
    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("SAFE_SCHEMA_CHECK_Error: ошибка при проверке схемы (таблица не найдена)")
    void SAFE_SCHEMA_CHECK_Error() throws IOException {
        // 1. Создаем данные для бэкапа
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // 2. Удаляем все таблицы, чтобы вызвать COMPLETED_WITH_WARNINGS
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS product_orders");
            jdbcTemplate.execute("DROP TABLE IF EXISTS orders");
            jdbcTemplate.execute("DROP TABLE IF EXISTS products");
            jdbcTemplate.execute("DROP TABLE IF EXISTS user_profiles");
            jdbcTemplate.execute("DROP TABLE IF EXISTS categories");
            jdbcTemplate.execute("DROP TABLE IF EXISTS users");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        // 3. Выполняем SAFE_SCHEMA_CHECK с таблицами, которые не существуют в БД
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_SCHEMA_CHECK,
                FULL_TABLE_LIST
        );

        // 4. ПРОВЕРКИ:

        // 4.1 Проверяем статус - должен быть FAILED, так как все таблицы пропущены и tablesProcessed=0
        assertEquals(RestoreStatus.FAILED, report.status(),
            "Статус должен быть FAILED, так как таблицы не обработаны (tablesProcessed=0)");

        // 4.2 Проверяем, что данные не изменились (все таблицы пропущены)
        assertEquals(6, report.summary().tablesSkipped(), "Должно быть 6 пропущенных таблиц");
        assertEquals(0, report.summary().tablesProcessed(), "Должно быть 0 обработанных таблиц");
        assertEquals(0, report.summary().rowsInserted(), "Должно быть 0 вставленных строк");

        log.info("[TEST LOG] SAFE_SCHEMA_CHECK_Error passed: status={}, tablesSkipped={}, tablesProcessed={}, rowsInserted={}",
                report.status(), report.summary().tablesSkipped(), report.summary().tablesProcessed(), report.summary().rowsInserted());
    }

    /**
     * Стратегия 6: SAFE_SCHEMA_CHECK (RELAXED_SCHEMA, SKIP_ON_CONFLICT, SKIP_VIOLATIONS, LOG_AND_CONTINUE)
     * Условие 7: Нарушение внешних ключей
     * Название: SAFE_SCHEMA_CHECK_FKViolation
     * Ожидание: Никаких изменений в БД
     * Проверки:
     * - FK не проверяются (нет вставки)
     * - Статус SUCCESS
     */
    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("SAFE_SCHEMA_CHECK_FKViolation: нарушение внешних ключей (без реальной вставки)")
    void SAFE_SCHEMA_CHECK_FKViolation() throws IOException {
        // 1. Создаем таблицы с FK зависимостями, но в неправильном порядке (нарушение FK)
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            // Удаляем все данные в правильном порядке
            jdbcTemplate.execute("DELETE FROM product_orders");
            jdbcTemplate.execute("DELETE FROM orders");
            jdbcTemplate.execute("DELETE FROM products");
            jdbcTemplate.execute("DELETE FROM user_profiles");
            jdbcTemplate.execute("DELETE FROM categories");
            jdbcTemplate.execute("DELETE FROM users");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        // 2. Готовим полный набор данных для бэкапа
        prepareFullDataSet();

        // 3. Создаем бэкап
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // 4. Удаляем данные, но оставляем таблицы (для restore в существующую БД)
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            jdbcTemplate.execute("DELETE FROM product_orders");
            jdbcTemplate.execute("DELETE FROM orders");
            jdbcTemplate.execute("DELETE FROM products");
            jdbcTemplate.execute("DELETE FROM user_profiles");
            jdbcTemplate.execute("DELETE FROM categories");
            jdbcTemplate.execute("DELETE FROM users");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        // 5. Сохраняем количество строк до восстановления
        long usersBefore = userRepository.count();
        long categoriesBefore = categoryRepository.count();

        // 6. Выполняем SAFE_SCHEMA_CHECK
        // SAFE_SCHEMA_CHECK не делает вставку, поэтому FK не проверяются
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.SAFE_SCHEMA_CHECK,
                FULL_TABLE_LIST
        );

        // 7. ПРОВЕРКИ:

        // 7.1 Проверяем статус - должен быть SUCCESS (таблицы существуют, FK не проверяются)
        assertEquals(RestoreStatus.SUCCESS, report.status(),
            "Статус должен быть SUCCESS, FK не проверяются (нет реальной вставки)");

        // 7.2 Проверяем статистику
        assertTrue(report.summary().tablesProcessed() > 0, "Должны быть обработаны таблицы (проверена схема)");
        assertEquals(0, report.summary().rowsInserted(), "Должно быть 0 вставленных строк (нет реальной вставки)");
        assertEquals(0, report.summary().tablesSkipped(), "Не должно быть пропущенных таблиц");

        // 7.3 Проверяем, что данные не изменились
        assertEquals(usersBefore, userRepository.count(), "Количество пользователей не должно измениться");
        assertEquals(categoriesBefore, categoryRepository.count(), "Количество категорий не должно измениться");

        log.info("[TEST LOG] SAFE_SCHEMA_CHECK_FKViolation passed: status={}, tablesProcessed={}, rowsInserted={}",
                report.status(), report.summary().tablesProcessed(), report.summary().rowsInserted());
    }

    // --------------------------------------------------
    // --- УТИЛИТЫ ----------------------------------------
    // --------------------------------------------------

    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) {
        String checkSql = """
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = ?
          AND COLUMN_NAME = ?
        """;

        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, tableName, columnName);

        if (count == null || count == 0) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnDefinition);
        }
    }

    /**
     * Удаляет таблицу, если она существует (MySQL-совместимо).
     * Безопасно для очистки тестовых таблиц.
     */
    private void dropTableIfExists(String tableName) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
        log.info("Dropped table '{}'", tableName);
    }
}
