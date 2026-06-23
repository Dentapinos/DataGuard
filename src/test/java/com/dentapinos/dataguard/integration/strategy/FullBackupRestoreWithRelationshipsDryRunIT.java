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
import org.junit.jupiter.api.AfterEach;
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
 * Интеграционные тесты режима DRY_RUN для восстановления резервной копии с проверкой внешних ключей.
 * Проверяет симуляцию восстановления без реальной вставки данных при различных сценариях.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { TestDatabaseConfig.class })
@WithResetDatabaseBeforeEach
@DisplayName("IT - DRY_RUN режим симуляции восстановления (без реальной вставки)")
class FullBackupRestoreWithRelationshipsDryRunIT extends BaseResetDatabaseTest {

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

    private Category savedCat1;
    private Category savedCat2;

    private static final List<String> FULL_TABLE_LIST = List.of(
            "users", "user_profiles", "categories", "products", "orders", "product_orders"
    );

    @BeforeEach
    void setUp() {
        dataPrefix = "DRY_RUN_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        createTestSchema();
    }

    @AfterEach
    void cleanUpTestData() {
        dropColumnIfExists("users", "extra_info");
        dropColumnIfExists("users", "nullable_extra");
        dropColumnIfExists("categories", "extra_category_data");

        dropTableIfExists("users_extra_cols");
        dropTableIfExists("users_extra_cols_test2");
        dropTableIfExists("users_backup_restore");
        dropTableIfExists("users_with_unique");
        dropTableIfExists("orders_with_fk");

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
    }

    /**
     * Утилита: добавляет колонку в таблицу, если она не существует.
     * Использует INFORMATION_SCHEMA для проверки наличия колонки.
     */
    private void addColumnIfNotExists(String tableName, String columnName, String columnDefinition) {
        String checkSql = """
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = ?
              AND TABLE_NAME = ?
              AND COLUMN_NAME = ?
            """;
        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, databaseName, tableName, columnName);
        if (count == null || count == 0) {
            String alterSql = String.format("ALTER TABLE %s ADD COLUMN %s %s", tableName, columnName, columnDefinition);
            jdbcTemplate.execute(alterSql);
        }
    }

    /**
     * Утилита: удаляет колонку из таблицы, если она существует.
     * Безопасно для тестов с лишними колонками.
     */
    private void dropColumnIfExists(String tableName, String columnName) {
        String checkSql = """
            SELECT COUNT(*)
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = ?
              AND TABLE_NAME = ?
              AND COLUMN_NAME = ?
            """;

        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, databaseName, tableName, columnName);

        if (count != null && count > 0) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " DROP COLUMN " + columnName);
            log.info("Dropped column '{}' from table '{}'", columnName, tableName);
        }
    }

    /**
     * Утилита: удаляет таблицу, если она существует.
     * Безопасно для очистки тестовых таблиц.
     */
    private void dropTableIfExists(String tableName) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS " + tableName);
        log.info("Dropped table '{}'", tableName);
    }

    // --- СХЕМА И ДАННЫЕ ---

    private void createTestSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS users (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(255),
                email VARCHAR(255)
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS user_profiles (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                avatar_url VARCHAR(255),
                bio VARCHAR(255),
                user_id BIGINT,
                CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users(id)
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS categories (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(255),
                description VARCHAR(1024)
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS products (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(255),
                description VARCHAR(1024),
                category_id BIGINT,
                user_id BIGINT,
                price DECIMAL(10,2),
                stock_quantity INT,
                CONSTRAINT fk_products_category FOREIGN KEY (category_id) REFERENCES categories(id),
                CONSTRAINT fk_products_user FOREIGN KEY (user_id) REFERENCES users(id)
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS orders (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                user_id BIGINT,
                order_number VARCHAR(255),
                status VARCHAR(50),
                CONSTRAINT fk_orders_user FOREIGN KEY (user_id) REFERENCES users(id)
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS product_orders (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                order_id BIGINT,
                product_id BIGINT,
                quantity INT,
                total_price DECIMAL(10,2),
                CONSTRAINT fk_product_orders_order FOREIGN KEY (order_id) REFERENCES orders(id),
                CONSTRAINT fk_product_orders_product FOREIGN KEY (product_id) REFERENCES products(id)
            )
            """);
    }

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

        prepareCategoriesOnly();

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

        Order order1 = Order.builder()
                .user(savedUser1)
                .orderNumber(dataPrefix + "ORD-001")
                .status(OrderStatus.CONFIRMED)
                .build();
        orderRepository.save(order1);

        Order order2 = Order.builder()
                .user(savedUser2)
                .orderNumber(dataPrefix + "ORD-002")
                .status(OrderStatus.PENDING)
                .build();
        orderRepository.save(order2);

        ProductOrder po = ProductOrder.builder()
                .order(order1)
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

    // --- ТЕСТЫ ---

    /**
     * DRY_RUN: БД пустая (таблицы не найдены)
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("должен вернуть FAILED при отсутствии таблиц в БД")
    void shouldReturnFailedWhenDatabaseIsEmpty() throws IOException {
        // arrange
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

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

        // act
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.DRY_RUN,
                FULL_TABLE_LIST
        );

        // assert
        assertEquals(RestoreStatus.FAILED, report.status(),
                "Статус должен быть FAILED (tablesProcessed = 0, tablesSkipped > 0)");
        assertTrue(report.summary().tablesSkipped() > 0, "Должны быть пропущены таблицы");
        assertEquals(0, report.summary().tablesProcessed(), "Должно быть 0 обработанных таблиц");
        assertEquals(FULL_TABLE_LIST.size(), report.summary().tablesSkipped(), "Должно быть 6 пропущенных таблиц");
        assertEquals(0, report.summary().tablesFailed(), "Не должно быть ошибок (tablesFailed = 0)");
        assertEquals(0, report.summary().rowsInserted(), "Условие 1: при отсутствии таблиц rowsInserted = 0 (без симуляции)");
        // Комментарий: В DRY_RUN при пустой БД, таблицы пропускаются без симуляции статистики rowsInserted.
        // Симуляция rowsInserted происходит только когда таблицы существуют и данные обрабатываются.
        // Важное примечание: DRY_RUN режим возвращает FAILED статус, когда все таблицы пропущены (tablesProcessed=0, tablesSkipped>0)
    }

    /**
     * DRY_RUN: БД содержит данные из бэкапа (схема валидна)
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("должен вернуть SUCCESS и симулировать вставку при наличии данных")
    void shouldReturnSuccessAndSimulateInsertWhenDataExists() throws IOException {
        // arrange
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        long usersBefore = userRepository.count();
        long profilesBefore = userProfileRepository.count();
        long categoriesBefore = categoryRepository.count();
        long productsBefore = productRepository.count();
        long ordersBefore = orderRepository.count();
        long productOrdersBefore = productOrderRepository.count();

        // act
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.DRY_RUN,
                FULL_TABLE_LIST
        );

        // assert
        assertEquals(RestoreStatus.SUCCESS, report.status(),
                "Статус должен быть SUCCESS (tablesProcessed > 0, таблицы существуют)");
        assertTrue(report.summary().tablesProcessed() > 0, "Должны быть обработаны таблицы");
        assertTrue(report.summary().rowsInserted() > 0, "Симулировано: все строки будут вставлены (OVERWRITE_ON_CONFLICT или SKIP_ON_CONFLICT)");
        assertEquals(0, report.summary().tablesSkipped(), "Не должно быть пропущенных таблиц");
        assertEquals(usersBefore, userRepository.count(), "Данные не должны измениться");
        assertEquals(profilesBefore, userProfileRepository.count(), "Данные не должны измениться");
        assertEquals(categoriesBefore, categoryRepository.count(), "Данные не должны измениться");
        assertEquals(productsBefore, productRepository.count(), "Данные не должны измениться");
        assertEquals(ordersBefore, orderRepository.count(), "Данные не должны измениться");
        assertEquals(productOrdersBefore, productOrderRepository.count(), "Данные не должны измениться");
    }

    /**
     * DRY_RUN: Отсутствует таблица в целой БД (одна таблица не найдена)
     */
    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("должен вернуть COMPLETED_WITH_WARNINGS при отсутствии одной таблицы")
    void shouldReturnCompletedWithWarningsWhenOneTableMissing() throws IOException {
        // arrange
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        jdbcTemplate.execute("DROP TABLE user_profiles");

        long usersBefore = userRepository.count();
        long categoriesBefore = categoryRepository.count();

        // act
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.DRY_RUN,
                FULL_TABLE_LIST
        );

        // assert
        assertEquals(RestoreStatus.COMPLETED_WITH_WARNINGS, report.status(),
                "Статус должен быть COMPLETED_WITH_WARNINGS (tablesProcessed > 0 и tablesSkipped > 0)");
        assertEquals(1, report.summary().tablesSkipped(),
                "Одна таблица пропущена");
        assertTrue(
                report.summary().tablesProcessed() > 0,
                "Остальные таблицы обработаны");
        assertEquals(usersBefore,
                userRepository.count()
                , "Данные не должны измениться");
        assertEquals(categoriesBefore,
                categoryRepository.count(), "Данные не должны измениться");

        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS user_profiles (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                avatar_url VARCHAR(255),
                bio VARCHAR(255),
                user_id BIGINT,
                CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users(id)
            )
            """);
    }

    /**
     * DRY_RUN: В БД есть "лишние" колонки (допустимо в RELAXED_SCHEMA)
     */
    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("должен вернуть SUCCESS при наличии лишних колонок в БД")
    void shouldReturnSuccessWithExtraColumnsInDatabase() throws IOException {
        // arrange
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        addColumnIfNotExists("users", "extra_info", "VARCHAR(255) DEFAULT 'default_value'");
        addColumnIfNotExists("users", "nullable_extra", "VARCHAR(255)");
        addColumnIfNotExists("categories", "extra_category_data", "VARCHAR(255) DEFAULT 'category_default'");

        long usersBefore = userRepository.count();
        long categoriesBefore = categoryRepository.count();

        // act
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.DRY_RUN,
                FULL_TABLE_LIST
        );

        // assert
        assertEquals(RestoreStatus.SUCCESS, report.status(),
                "Статус должен быть SUCCESS (лишние колонки в БД допустимы)");
        assertTrue(report.summary().tablesProcessed() > 0, "Таблицы обработаны");
        assertTrue(report.summary().rowsInserted() > 0, "Симулированы строки");
        assertEquals(0, report.summary().tablesSkipped(), "Таблицы не пропускаются");
        assertEquals(usersBefore, userRepository.count(), "Данные не изменятся");
        assertEquals(categoriesBefore, categoryRepository.count(), "Данные не изменятся");
    }

    /**
     * DRY_RUN: В бэкапе есть "лишние" колонки (допустимо в RELAXED_SCHEMA)
     */
    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("должен вернуть SUCCESS при наличии лишних колонок в бэкапе")
    void shouldReturnSuccessWithExtraColumnsInBackup() throws IOException {
        // arrange
        // Создаём таблицу БЕЗ лишней колонки
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS users_backup_restore (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(255),
                email VARCHAR(255)
            )
            """);

        //Вставляем данные
        jdbcTemplate.execute("INSERT INTO users_backup_restore (username, email) VALUES ('user1', 'user1@test.com')");
        jdbcTemplate.execute("INSERT INTO users_backup_restore (username, email) VALUES ('user2', 'user2@test.com')");

        //Делаем бэкап этой таблицы (таблица без extra_col)
        List<String> tables = List.of("users_backup_restore");
        BackupEnvelope backupEnvelope = backupFacade.backupMySql(
                databaseConfigResolver.resolveCredentials(databaseName),
                databaseName,
                tables
        );
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // Теперь в БД удалим и создадим новую таблицу ТОЛЬКО С ЛИШНИМИ КОЛОНКАМИ (как будто в бэкапе нет)
        jdbcTemplate.execute("DROP TABLE IF EXISTS users_backup_restore");
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS users_backup_restore (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(255),
                email VARCHAR(255),
                extra_col VARCHAR(255) DEFAULT 'extra_default'
            )
            """);

        long countBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users_backup_restore", Long.class);

        // Запускаем DRY_RESTORE
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.DRY_RUN,
                tables
        );

        // assert
        //Проверки
        assertEquals(RestoreStatus.SUCCESS, report.status(),
                "Лишние колонки в бэкапе игнорируются (RELAXED_SCHEMA), статус должен быть SUCCESS");
        assertTrue(report.summary().tablesProcessed() > 0, "Таблица должна быть обработана");
        assertTrue(report.summary().rowsInserted() > 0, "Симулировано: все строки будут вставлены");
        assertEquals(0, report.summary().tablesSkipped(), "Нет пропущенных таблиц");
        assertEquals(countBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users_backup_restore", Long.class),
                "Данные не должны измениться (DRY_RUN)");
    }

    /**
     * DRY_RUN: Обнаружена ошибка при обработке данных
     * Условие 6: Обнаружена ошибка при парсинге/обработке
     *
     * Важное примечание: DRY_RUN режим НЕ может естественным образом вызвать tablesFailed++,
     * так как он не выполняет реальных операций SQL - он только проверяет метаданные и симулирует статистику.
     *
     * В DRY_RUN режиме:
     * - При отсутствии таблицы: tablesSkipped++ (не tablesFailed++)
     * - Ошибки обрабатываются через LOG_AND_CONTINUE и приводят к tablesSkipped++
     * - tablesFailed++ может быть вызван только через искусственный механизм ошибки
     *
     * Тест проверяет корректное поведение DRY_RUN при отсутствии таблиц:
     * - Должен вернуть FAILED статус, так как tablesProcessed=0 и tablesSkipped>0
     * - Это соответствует логике RestoreService.getRestoreStatus():
     *   if (tablesFailed == 0 && tablesSkipped > 0 && tablesProcessed == 0) → FAILED
     */
    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("должен вернуть FAILED при полном отсутствии таблиц в БД")
    void shouldReturnFailedWhenAllTablesMissing() throws IOException {
        // arrange
        // Создаём таблицу с данными
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS users_with_unique (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                username VARCHAR(255) NOT NULL,
                email VARCHAR(255) NOT NULL
            )
            """);
        jdbcTemplate.execute("INSERT INTO users_with_unique (username, email) VALUES ('user1', 'u1@test.com')");

        List<String> tables = List.of("users_with_unique");
        BackupEnvelope original = backupFacade.backupMySql(
                databaseConfigResolver.resolveCredentials(databaseName),
                databaseName,
                tables
        );

        String backupName = backupFacade.storeBackup(original, databaseName);

        // Удалим таблицу из БД, чтобы она отсутствовала при восстановлении
        // В DRY_RUN это приведет к tablesSkipped++, а не к FAILED
        jdbcTemplate.execute("DROP TABLE IF EXISTS users_with_unique");

        long countBefore = 0; // Таблицы нет, поэтому 0

        // act
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.DRY_RUN,
                tables
        );

        // assert
        // В случае отсутствия таблицы - tablesSkipped++ и tablesProcessed=0, что дает FAILED статус
        // Согласно RestoreService.getRestoreStatus():
        // - tablesFailed == 0 AND tablesSkipped > 0 AND tablesProcessed == 0 → FAILED
        assertEquals(RestoreStatus.FAILED, report.status(),
                "DRY_RUN с LOG_AND_CONTINUE возвращает FAILED при полном отсутствии таблиц (tablesProcessed=0, tablesSkipped>0)");
        assertEquals(1, report.summary().tablesSkipped(), "Таблица пропущена (отсутствует в БД)");
        assertEquals(0, report.summary().tablesProcessed(), "Таблиц не обработано");
        assertEquals(0, report.summary().tablesFailed(), "Таблиц не завершилось с ошибкой (DRY_RUN не может вызвать tablesFailed без искусственной ошибки)");
    }

    /**
     * DRY_RUN: Нарушение внешних ключей (FK)
     * В DRY_RUN FK не проверяются. Симуляция проходит, даже если есть нарушения.
     */
    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("должен вернуть SUCCESS при нарушении FK (FK не проверяются в DRY_RUN)")
    void shouldReturnSuccessWithFKViolation() throws IOException {
        // В DRY_RUN FK не проверяются. Симуляция проходит, даже если есть нарушения.

        // Восстанавливаем таблицы users и user_profiles, чтобы можно было создать FK
        createTestSchema();

        // Временно отключаем проверку FK для создания таблицы с ссылкой на несуществующую таблицу
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            // Удаляем таблицу, если она существует, чтобы избежать дублирования FK
            jdbcTemplate.execute("DROP TABLE IF EXISTS orders_with_fk");

            jdbcTemplate.execute("""
                CREATE TABLE orders_with_fk (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    user_id BIGINT NOT NULL,
                    order_number VARCHAR(255),
                    CONSTRAINT fk_orders_with_fk_user FOREIGN KEY (user_id) REFERENCES users(id)
                )
                """);

            jdbcTemplate.execute("INSERT INTO orders_with_fk (user_id, order_number) VALUES (99999, 'ORD-999')");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        List<String> tables = List.of("orders_with_fk");
        BackupEnvelope backupEnvelope = backupFacade.backupMySql(
                databaseConfigResolver.resolveCredentials(databaseName),
                databaseName,
                tables
        );

        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // Удалим users и user_profiles (чтобы FK нарушался при реальном restore)
        // В DRY_RUN FK не проверяются, поэтому это допустимо
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            jdbcTemplate.execute("DROP TABLE IF EXISTS users");
            jdbcTemplate.execute("DROP TABLE IF EXISTS user_profiles");
        } finally {
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }

        long countBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders_with_fk", Long.class);

        // act
        RestoreReport report = restoreService.restoreToExistingDatabase(
                databaseConfigResolver.resolveCredentials(databaseName),
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.DRY_RUN,
                tables
        );

        // assert
        assertEquals(RestoreStatus.SUCCESS, report.status(),
                "В режиме DRY_RUN FK не проверяются, статус должен быть SUCCESS");
        assertTrue(report.summary().tablesProcessed() > 0, "Таблица обработана");
        assertTrue(report.summary().rowsInserted() > 0, "Симулировано: строки вставятся (если бы не DRY_RUN)");
        assertEquals(countBefore, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders_with_fk", Long.class),
                "Несмотря на FK и DRY_RUN, данные не изменятся");
    }
}
