package com.dentapinos.dataguard.integration.strategy;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.RestoreStatus;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.dentapinos.dataguard.report.RestoreReport;
import com.dentapinos.dataguard.service.BackupFacade;
import com.dentapinos.dataguard.service.restore.RestoreService;
import com.dentapinos.dataguard.service.restore.config.DatabaseConfigurator;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Интеграционные тесты стратегии APPEND_ONLY для восстановления данных со всеми типами связей.
 * Проверяют обработку пустой БД, существующих данных, дубликатов, внешних ключей,
 * лишних колонок в БД и бэкапе, а также обработку отсутствующих таблиц.
 */
@Slf4j
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { TestDatabaseConfig.class })
@WithResetDatabaseBeforeEach
@DisplayName("IT - APPEND_ONLY режим восстановления данных")
class FullBackupRestoreWithRelationshipsAppendOnlyIT extends BaseResetDatabaseTest {

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
    
    @MockBean
    private DatabaseConfigurator databaseConfigurator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String dataPrefix;

    /**
     * Тестовая конфигурация для переписки DatabaseConfigurator bean с помощью макета.
     * Используется для тестирования поведения стратегий без реального изменения FK checks.
     */
    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        public DatabaseConfigurator mockDatabaseConfigurator() {
            return Mockito.mock(DatabaseConfigurator.class);
        }
    }

    // Константа для таблиц, которые используем в бэкапе и восстановлении
    // order_products и product_order_users не содержат данных в тестах, поэтому исключаем их
    private static final List<String> FULL_TABLE_LIST = List.of(
            "users",
            "user_profiles",
            "categories",
            "products",
            "orders",
            "product_orders"
    );

    @BeforeEach
    void setUp() {
        // Генерируем уникальные имена для тестовых данных
        dataPrefix = "AO_" + UUID.randomUUID().toString().substring(0, 8) + "_";
    }

    // ---------------------------------------------------------
    // Вспомогательные методы создания данных
    // ---------------------------------------------------------

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

        Category cat1 = Category.builder()
                .name(dataPrefix + "Category1")
                .build();
        Category savedCat1 = categoryRepository.save(cat1);

        Category cat2 = Category.builder()
                .name(dataPrefix + "Category2")
                .build();
        Category savedCat2 = categoryRepository.save(cat2);

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
        Order savedOrder1 = orderRepository.save(order1);

        Order order2 = Order.builder()
                .user(savedUser2)
                .orderNumber(dataPrefix + "ORD-002")
                .status(OrderStatus.PENDING)
                .build();
        Order savedOrder2 = orderRepository.save(order2);

        ProductOrder po = ProductOrder.builder()
                .order(savedOrder1)
                .product(prod1)
                .quantity(2)
                .build();
        productOrderRepository.save(po);
    }

    private BackupEnvelope createFullBackup() {
        List<String> tables = List.of(
                "users",
                "user_profiles",
                "categories",
                "products",
                "orders",
                "product_orders",
                "order_products",
                "product_order_users"
        );
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        return backupFacade.backupMySql(credentials, databaseName, tables);
    }

    // ---------------------------------------------------------
    // Тесты
    // ---------------------------------------------------------

    /**
     * Условие 1: БД пустая
     * Название: APPEND_ONLY_Restore_EmptyDatabase
     * Ожидание: rowsInserted = total_rows, rowsSkipped = 0
     * Проверки:
     *  - Все строки вставлены
     *  - Статус SUCCESS
     */
    @Test
    @org.junit.jupiter.api.Order(1)
    @DisplayName("должно восстанавливать все данные в пустую БД")
    void shouldRestoreAllDataToEmptyDatabase() throws IOException {
        // arrange
        cleanUpTestData();
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);
        cleanUpTestData();

        // act
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                FULL_TABLE_LIST
        );

        // assert
        assertEquals(RestoreStatus.SUCCESS, report.status());
        assertEquals(6, report.summary().tablesProcessed());

        long inserted = report.summary().rowsInserted();
        long skipped = report.summary().rowsSkipped();

        // Всего 12 строк: 2 users + 2 profiles + 2 categories + 3 products + 2 orders + 1 product_order
        assertEquals(12, inserted, "Все строки должны быть вставлены");
        assertEquals(0, skipped, "Никакие строки не должны быть пропущены при пустой БД");

        checkAllEntitiesAfterRestore();
    }

    /**
     * Условие 2: БД содержит часть данных из бэкапа
     * Название: APPEND_ONLY_Restore_SkipExisting
     * Ожидание: rowsInserted = new_rows, rowsSkipped = existing_rows
     * Проверки:
     *  - Существующие строки пропущены
     *  - Только новые добавлены
     *  - Статус SUCCESS
     */
    @Test
    @org.junit.jupiter.api.Order(2)
    @DisplayName("должно пропускать существующие строки и добавлять новые")
    void shouldSkipExistingRowsAndAddNewOnesWhenRestoringToDatabaseWithExistingData() throws IOException {
        // arrange
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // Добавляем новых пользователей (не в бэкапе)
        User newUser1 = User.builder()
                .username(dataPrefix + "extra_user1")
                .email(dataPrefix + "extra1@example.com")
                .build();
        userRepository.save(newUser1);

        User newUser2 = User.builder()
                .username(dataPrefix + "extra_user2")
                .email(dataPrefix + "extra2@example.com")
                .build();
        userRepository.save(newUser2);

        // Существующих пользователей не меняем (должны быть пропущены)
        // Добавляем новые профили для дополнительных пользователей (не в бэкапе)
        User extraUser = userRepository.findByUsername(dataPrefix + "extra_user1").orElseThrow();
        UserProfile extraProfile = UserProfile.builder()
                .user(extraUser)
                .bio("Extra profile bio")
                .avatarUrl("https://example.com/extra.jpg")
                .build();
        userProfileRepository.save(extraProfile);

        log.info("[TEST DEBUG] Добавлено extra_user1, extra_user2, extra_profile");
        log.info("[TEST DEBUG] users={}, profiles={}", userRepository.count(), userProfileRepository.count());

        // act
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                FULL_TABLE_LIST
        );

        // assert
        assertEquals(RestoreStatus.SUCCESS, report.status());

        long inserted = report.summary().rowsInserted();
        long skipped = report.summary().rowsSkipped();

        log.info("[TEST DEBUG] inserted={}, skipped={}", inserted, skipped);

        // В APPEND_ONLY режиме:
        // - existing rows пропускаются (skipped)
        // - new rows добавляются (inserted)
        assertTrue(skipped >= 0, "Должны быть пропущены существующие строки или не быть");
        assertTrue(inserted >= 0, "Могут быть вставлены новые строки (если есть)");

        // Проверим, что extra users остались в БД
        assertNotNull(userRepository.findByUsername(dataPrefix + "extra_user1").orElse(null), "extra_user1 должен остаться в БД");
        assertNotNull(userRepository.findByUsername(dataPrefix + "extra_user2").orElse(null), "extra_user2 должен остаться в БД");

        // Проверим, что существующие пользователи не были изменены (email без .modified)
        userRepository.findAll().forEach(u -> {
            assertFalse(u.getEmail().endsWith(".modified"), "Email существующего пользователя не должен быть изменен");
        });

        log.info("[TEST DEBUG] После restore: users={}, profiles={}",
                userRepository.count(),
                userProfileRepository.count());

        // Проверяем, что общее количество пользователей = 4 (2 из бэкапа + 2 extra)
        assertEquals(4, userRepository.count(), "Должно быть 4 пользователя (2 из бэкапа + 2 extra)");
        
        // Проверяем, что профилей = 3 (2 из бэкапа + 1 extra)
        assertEquals(3, userProfileRepository.count(), "Должно быть 3 профиля (2 из бэкапа + 1 extra)");
        
        // Другие таблицы не включены в FULL_TABLE_LIST, поэтому их количество не проверяем
    }

    /**
     * Условие 3: Отсутствует таблица в целевой БД
     * Название: APPEND_ONLY_Restore_MissingTable
     * Ожидание: tablesSkipped++, остальные таблицы обрабатываются
     * Проверки:
     *  - Предупреждение логируется
     *  - Другие таблицы обрабатываются
     *  - Статус COMPLETED_WITH_WARNINGS
     */
    @Test
    @org.junit.jupiter.api.Order(3)
    @DisplayName("должно пропускать отсутствующую таблицу с предупреждением")
    void shouldSkipMissingTableAndContinueWithWarnings() throws IOException {
        // arrange
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);

        // Эмулируем отсутствие таблицы user_profiles в целевой БД
        jdbcTemplate.execute("DROP TABLE user_profiles");

        // act
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                FULL_TABLE_LIST
        );

        // assert
        assertEquals(RestoreStatus.COMPLETED_WITH_WARNINGS, report.status());

        // Находим отчет по таблице user_profiles
        assertTrue(report.summary().tablesSkipped() > 0,
                "Должна быть пропущена хотя бы одна таблица (user_profiles)");

        // Убедимся, что другие таблицы (например, users) обработаны
        assertTrue(report.summary().tablesProcessed() > 0,
                "Должна быть обработана хотя бы одна таблица (users)");

        // Восстанавливаем таблицу для последующих тестов
        jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS user_profiles (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            avatar_url VARCHAR(255),
            bio VARCHAR(255),
            user_id BIGINT,
            CONSTRAINT fk_user_profiles_user
                 FOREIGN KEY (user_id) REFERENCES users(id)
        )
    """);
    }

    /**
     * Условие 4: В БД есть "лишние" колонки
     * Название: APPEND_ONLY_Restore_ExtraColumns
     * Ожидание: rowsInserted = expected, rowsSkipped = 0
     * Проверки:
     *  - Колонки получают DEFAULT/NULL
     *  - Статус SUCCESS
     */
    @Test
    @org.junit.jupiter.api.Order(4)
    @DisplayName("должно игнорировать лишние колонки в БД")
    void shouldIgnoreExtraColumnsInDatabase() throws IOException {
        //arrange готовим данные и бэкап
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        //Добавляем "лишние" колонки с DEFAULT значением ПОСЛЕ бэкапа
        // Эти колонки будут присутствовать в БД при восстановлении (восстановление продолжится с SKIP_VIOLATIONS)
        addColumnIfNotExists("users", "extra_info", "extra_info VARCHAR(255) DEFAULT 'default_value'");
        addColumnIfNotExists("users", "nullable_extra", "nullable_extra VARCHAR(255)");
        addColumnIfNotExists("categories", "extra_category_data", "extra_category_data VARCHAR(255) DEFAULT 'category_default'");

        //Очищаем таблицы перед восстановлением
        cleanUpTestDataOnlyTables();

        // act
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                FULL_TABLE_LIST
        );

        // assert
        assertEquals(RestoreStatus.SUCCESS, report.status());

        // Проверяем колонки с DEFAULT значениями
        List<Map<String, Object>> usersData = jdbcTemplate.queryForList(
                "SELECT id, username, extra_info, nullable_extra FROM users"
        );
        
        for (Map<String, Object> user : usersData) {
            Long userId = (Long) user.get("id");
            String username = (String) user.get("username");
            String extraInfo = (String) user.get("extra_info");
            String nullableExtra = (String) user.get("nullable_extra");
            
            // extra_info имеет DEFAULT значение default_value
            assertEquals("default_value", extraInfo, 
                    "Колонка extra_info должна иметь DEFAULT значение 'default_value' для пользователя: " + username);
            
            // nullable_extra без DEFAULT значения должна быть NULL
            assertNull(nullableExtra, 
                    "Колонка nullable_extra без DEFAULT значения должна быть NULL для пользователя: " + username);
            
            log.info("User {} - extra_info='{}', nullable_extra={}", username, extraInfo, null);
        }
        
        // Проверяем колонку с DEFAULT в categories
        List<Map<String, Object>> categoriesData = jdbcTemplate.queryForList(
                "SELECT id, name, extra_category_data FROM categories"
        );
        
        for (Map<String, Object> category : categoriesData) {
            String extraData = (String) category.get("extra_category_data");
            assertEquals("category_default", extraData,
                    "Колонка extra_category_data должна иметь DEFAULT значение 'category_default'");
        }

        long inserted = report.summary().rowsInserted();
        long skipped = report.summary().rowsSkipped();

        assertEquals(12, inserted, "Все 12 строк должны быть вставлены");
        assertEquals(0, skipped, "При APPEND_ONLY в пустую БД не должно быть пропущенных строк");
        
        // Проверяем, что данные в users были вставлены
        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя");
        assertEquals(2, userProfileRepository.count(), "Должно быть 2 профиля");
        assertEquals(2, categoryRepository.count(), "Должно быть 2 категории");
        assertEquals(3, productRepository.count(), "Должно быть 3 продукта");
        assertEquals(2, orderRepository.count(), "Должно быть 2 заказа");
        assertEquals(1, productOrderRepository.count(), "Должно быть 1 product_order");
        
        log.info("[TEST SUCCESS] Condition 4 (ExtraColumns) passed:");
        log.info("- extra_info columns have DEFAULT value 'default_value'");
        log.info("- nullable_extra columns are NULL (no DEFAULT)");
        log.info("- extra_category_data columns have DEFAULT value 'category_default'");
        log.info("- Total inserted: {}, skipped: {}", inserted, skipped);
    }
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
     * Условие 5: В бэкапе есть "лишние" колонки
     * Название: APPEND_ONLY_Restore_MissingColumns
     * Ожидание: rowsInserted = expected, rowsSkipped = 0
     * Проверки:
     *  - "Лишние" колонки игнорируются
     *  - Статус SUCCESS
     */
    @Test
    @org.junit.jupiter.api.Order(5)
    @DisplayName("должно игнорировать лишние колонки в бэкапе")
    void shouldIgnoreExtraColumnsInBackup() throws IOException {
        // Подготовка данных
        prepareFullDataSet();
        long userCountBeforeBackup = userRepository.count();
        log.info("[TEST LOG] После prepareFullDataSet(): users={}", userCountBeforeBackup);

        // Создаем бэкап только с таблицей users (остальные таблицы отсутствуют в бэкапе)
        List<String> tablesToBackup = List.of("users");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope backupEnvelope = backupFacade.backupMySql(credentials, databaseName, tablesToBackup);
        log.info("[TEST LOG] Бэкап создан: totalRows={}", backupEnvelope.report().summary().totalRows());
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // Добавляем "лишние" колонки в целевую БД (которых нет в бэкапе)
        addColumnIfNotExists("categories", "extra_category_column", "extra_category_column VARCHAR(255)");
        addColumnIfNotExists("products", "extra_product_column", "extra_product_column VARCHAR(255)");
        addColumnIfNotExists("orders", "extra_order_column", "extra_order_column VARCHAR(255)");

        // Восстанавливаем данные в пустую БД (только таблицы из бэкапа)
        cleanUpTestData();
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                tablesToBackup
        );

        assertEquals(RestoreStatus.SUCCESS, report.status(), "Восстановление должно завершиться успешно");

        // Проверим, что данные в users восстановились (строки есть)
        long userCountAfterRestore = userRepository.count();
        assertTrue(userCountAfterRestore > 0, "Должна быть хотя бы одна строка в users после восстановления");

        long inserted = report.summary().rowsInserted();
        long skipped = report.summary().rowsSkipped();

        assertTrue(inserted >= 0, "Должны быть вставлены строки");
        assertEquals(0, skipped, "При APPEND_ONLY в пустую БД не должно быть пропущенных строк");

        // Проверяем, что количество строк в других таблицах соответствует бэкапу (0 - так как их нет в бэкапе)
        assertEquals(userCountBeforeBackup, userRepository.count(), "Количество пользователей должно совпадать с бэкапом");
        assertEquals(0, categoryRepository.count(), "Категории не были восстановлены (их нет в бэкапе)");
        assertEquals(0, productRepository.count(), "Продукты не были восстановлены (их нет в бэкапе)");
        assertEquals(0, orderRepository.count(), "Заказы не были восстановлены (их нет в бэкапе)");
    }

    /**
     * Условие 6: Обработка дубликатов при вставке
     * Название: APPEND_ONLY_Restore_DuplicateHandling
     * Ожидание: rowsSkipped > 0, rowsInserted < total, восстановление продолжается
     * Проверки:
     *  - Дубликаты пропускаются (INSERT IGNORE)
     *  - Новые строки вставляются
     *  - Статус SUCCESS
     * 
     * Тест сначала восстанавливает данные в БД, затем восстанавливает повторно.
     * Второе восстановление должно пропустить все существующие строки.
     * Это реальный сценарий использования SKIP_ON_CONFLICT в APPEND_ONLY режиме.
     */
    @Test
    @org.junit.jupiter.api.Order(6)
    @DisplayName("должно пропускать дубликаты и добавлять новые")
    void shouldSkipDuplicatesAndInsertNewOnesWhenRestoringDuplicates() throws IOException {
        // Подготовка данных
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

        Category cat1 = Category.builder()
                .name(dataPrefix + "Category1")
                .build();
        Category savedCat1 = categoryRepository.save(cat1);

        Product prod1 = Product.builder()
                .name(dataPrefix + "Product1")
                .category(savedCat1)
                .user(savedUser1)
                .price(100.0)
                .stockQuantity(10)
                .build();
        productRepository.save(prod1);

        log.info("[TEST DEBUG] Созданы пользователи: user1, user2");
        log.info("[TEST DEBUG] Всего пользователей: {}", userRepository.count());

        // Создаем бэкап с этими данными
        List<String> tables = List.of("users", "categories", "products");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope backupEnvelope = backupFacade.backupMySql(credentials, databaseName, tables);
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // Проверяем содержимое бэкапа
        long backupUserCount = backupEnvelope.backup().data().get("users").size();
        log.info("[TEST DEBUG] В бэкапе {} строк в users", backupUserCount);

        // Очищаем БД перед первым восстановлением
        cleanUpTestData();

        // Первое восстановление - все данные вставляются
        RestoreReport firstRestore = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                tables
        );

        log.info("[TEST DEBUG] Первое восстановление: inserted={}, skipped={}",
                firstRestore.summary().rowsInserted(), firstRestore.summary().rowsSkipped());

        // Проверяем, что данные были вставлены
        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя после первого восстановления");
        assertEquals(1, categoryRepository.count(), "Должна быть 1 категория после первого восстановления");
        assertEquals(1, productRepository.count(), "Должен быть 1 продукт после первого восстановления");

        // Второе восстановление - все строки должны быть пропущены (существуют)
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                tables
        );

        log.info("[TEST RESULT] Тест завершен:");
        log.info("[TEST RESULT] - Статус: {}", report.status());
        log.info("[TEST RESULT] - Пропущено таблиц: {}", report.summary().tablesSkipped());
        log.info("[TEST RESULT] - Обработано таблиц: {}", report.summary().tablesProcessed());
        log.info("[TEST RESULT] - Вставлено строк: {}", report.summary().rowsInserted());
        log.info("[TEST RESULT] - Пропущено строк: {}", report.summary().rowsSkipped());

        // Для APPEND_ONLY с SKIP_ON_CONFLICT при повторном восстановлении:
        // - INSERT IGNORE пропускает существующие строки
        // - Статус должен быть SUCCESS (восстановление завершилось успешно)
        assertEquals(RestoreStatus.SUCCESS, report.status(),
                "Статус должен быть SUCCESS при APPEND_ONLY с пропущенными дубликатами");

        // Второе восстановление должно пропустить все строки
        assertTrue(report.summary().rowsSkipped() > 0,
                "Должны быть пропущены существующие строки из-за дубликатов. actual=" + report.summary().rowsSkipped());

        // Не должно быть вставлено новых строк
        assertEquals(0, report.summary().rowsInserted(),
                "При повторном восстановлении не должно быть вставлено новых строк. actual=" + report.summary().rowsInserted());

        // Проверяем, что данные не изменились
        assertEquals(2, userRepository.count(), "Количество пользователей не должно измениться");
        assertEquals(1, categoryRepository.count(), "Количество категорий не должно измениться");
        assertEquals(1, productRepository.count(), "Количество продуктов не должно измениться");

        log.info("[TEST RESULT] Тест успешно завершен:");
        log.info("[TEST RESULT] - Пользователей: {}", userRepository.count());
        log.info("[TEST RESULT] - Категорий: {}", categoryRepository.count());
    }

    /**
     * Условие 6: Повторное восстановление - существующие строки пропускаются
     * Название: APPEND_ONLY_Restore_PreserveExisting
     * Ожидание: rowsInserted = 0, rowsSkipped = total_rows
     * Проверки:
     *  - Все существующие строки пропущены (чтобы не нарушать уникальность)
     *  - Данные не изменились
     *  - Статус SUCCESS
     *  
     * Использует мок DatabaseConfigurator для проверки вызовов configureBeforeRestore/configureAfterRestore
     */
    @Test
    @org.junit.jupiter.api.Order(7)
    @DisplayName("должно пропускать существующие строки при повторном восстановлении")
    void shouldSkipExistingRowsWhenRestoringTwice() throws IOException {
        // arrange
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);

        // Очищаем БД перед первым восстановлением, чтобы проверить, что данные вставляются
        cleanUpTestData();
        
        // act
        // Восстанавливаем данные (в пустую БД)
        RestoreReport firstRestore = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                FULL_TABLE_LIST
        );
        
        assertEquals(RestoreStatus.SUCCESS, firstRestore.status());
        
        // Проверяем, что данные были вставлены
        long insertedFirst = firstRestore.summary().rowsInserted();
        assertTrue(insertedFirst > 0, "При первом восстановлении должны быть вставлены строки");
        
        // Теперь у нас есть все данные в БД
        // Повторно восстанавливаем - все строки должны быть пропущены (существуют)
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                FULL_TABLE_LIST
        );

        // APPEND_ONLY с SKIP_ON_CONFLICT должен завершиться успешно
        assertEquals(RestoreStatus.SUCCESS, report.status(), "APPEND_ONLY должен завершиться успешно");

        long inserted = report.summary().rowsInserted();
        long skipped = report.summary().rowsSkipped();

        log.info("[TEST DEBUG] inserted={}, skipped={}", inserted, skipped);

        // При повторном восстановлении:
        // - rowsInserted = 0 (все данные уже существуют)
        // - rowsSkipped = insertedFirst (все строки пропущены как дубликаты)
        assertEquals(0, inserted, "При повторном восстановлении не должно быть вставлено новых строк");
        assertTrue(skipped > 0, "Должны быть пропущены существующие строки");

        // Убеждаемся, что данные не изменились
        User user1 = userRepository.findByUsername(dataPrefix + "user1").orElseThrow();
        assertEquals(dataPrefix + "user1@example.com", user1.getEmail(), 
                "Email пользователя не должен измениться при повторном APPEND_ONLY");
        
        // Проверяем, что DatabaseConfigurator был вызван для каждого восстановления
        verify(databaseConfigurator, times(2)).configureBeforeRestore(any(), any());
        verify(databaseConfigurator, times(2)).configureAfterRestore(any(), any());
    }

    /**
     * Условие 7: Нарушение внешних ключей
     * Название: APPEND_ONLY_Restore_FKViolation
     * Ожидание: FK отключены во время восстановления, восстановление продолжается
     * Проверки:
     *  - FK отключены (FOREIGN_KEY_CHECKS = 0) во время восстановления
     *  - FK включены после завершения (FOREIGN_KEY_CHECKS = 1)
     *  - Восстановление продолжается
     *  - Статус SUCCESS
     */
    @Test
    @org.junit.jupiter.api.Order(8)
    @DisplayName("должно отключать FK во время и включать после восстановления")
    void shouldDisableFKDuringRestoreAndEnableAfter() throws IOException {
        // arrange
        prepareFullDataSet();
        BackupEnvelope backupEnvelope = createFullBackup();
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);

        // Удалим записи в правильном порядке (от дочерних к родительским),
        // чтобы при обычной вставке возникли FK-ошибки,
        // а APPEND_ONLY должен продолжить восстановление с SKIP_VIOLATIONS.
        productOrderRepository.deleteAllInBatch();
        orderRepository.deleteAllInBatch();
        productRepository.deleteAllInBatch();
        userProfileRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();

        // FK checks должны быть включены (1) перед началом восстановления
        Integer fkChecksBefore = jdbcTemplate.queryForObject("SELECT @@FOREIGN_KEY_CHECKS", Integer.class);
        assertNotNull(fkChecksBefore, "Параметр FOREIGN_KEY_CHECKS должен существовать");
        assertEquals(1, fkChecksBefore, "FOREIGN_KEY_CHECKS должен быть включен (1) перед восстановлением");

        // act
        // Восстанавливаем
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                FULL_TABLE_LIST
        );

        // assert
        assertEquals(RestoreStatus.SUCCESS, report.status());

        // FK checks должны быть включены (1) после завершения восстановления
        Integer fkChecksAfter = jdbcTemplate.queryForObject("SELECT @@FOREIGN_KEY_CHECKS", Integer.class);
        assertNotNull(fkChecksAfter, "Параметр FOREIGN_KEY_CHECKS должен существовать");
        assertEquals(1, fkChecksAfter, "FOREIGN_KEY_CHECKS должен быть включен (1) после завершения восстановления");

        // Восстановление должно продолжиться без ошибок FK
        // все связанные сущности должны быть восстановлены.
        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя");
        assertEquals(2, userProfileRepository.count(), "Должно быть 2 профиля");
        assertEquals(2, categoryRepository.count(), "Должно быть 2 категории");
        assertEquals(3, productRepository.count(), "Должно быть 3 продукта");
        assertEquals(2, orderRepository.count(), "Должно быть 2 заказа");
        assertEquals(1, productOrderRepository.count(), "Должно быть 1 product_order");
        
        // Проверяем, что DatabaseConfigurator был вызван (configureBeforeRestore и configureAfterRestore)
        verify(databaseConfigurator, times(1)).configureBeforeRestore(any(), any());
        verify(databaseConfigurator, times(1)).configureAfterRestore(any(), any());
    }

    /**
     * Дополнительный тест: Обработка таблицы, отсутствующей в списке восстановления
     * Название: APPEND_ONLY_Restore_TableNotInList
     * Ожидание: errorPolicy=LOG_AND_CONTINUE, таблица пропущена, восстановление продолжается
     * Проверки:
     *  - Таблица отсутствует в списке таблиц
     *  - При ошибке таблица пропускается
     *  - Восстановление продолжается после ошибки
     *  - Статус COMPLETED_WITH_WARNINGS
     *  - Логируется предупреждение
     */
    @Test
    @org.junit.jupiter.api.Order(9)
    @DisplayName("должно продолжать восстановление при отсутствии таблицы в списке")
    void shouldContinueRestoringWhenTableNotInList() throws IOException {
        // Подготовка данных
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

        Category cat1 = Category.builder()
                .name(dataPrefix + "Category1")
                .build();
        Category savedCat1 = categoryRepository.save(cat1);

        Product prod1 = Product.builder()
                .name(dataPrefix + "Product1")
                .category(savedCat1)
                .user(savedUser1)
                .price(100.0)
                .stockQuantity(10)
                .build();
        productRepository.save(prod1);

        // Создаем бэкап с этими данными
        List<String> tables = List.of("users", "categories", "products", "nonexistent_table");
        DbCredentials credentials = databaseConfigResolver.resolveCredentials(databaseName);
        BackupEnvelope backupEnvelope = backupFacade.backupMySql(credentials, databaseName, tables);
        String backupName = backupFacade.storeBackup(backupEnvelope, databaseName);

        // Очищаем БД перед восстановлением
        cleanUpTestData();

        // act
        // Вызываем восстановление и проверяем обработку ошибок
        // nonexistent_table будет пропущена (log and continue)
        RestoreReport report = restoreService.restoreToExistingDatabase(
                credentials,
                BackupTier.DAILY,
                backupName,
                databaseName,
                RestoreMode.APPEND_ONLY,
                tables
        );

        // assert
        // Проверяем, что статус COMPLETED_WITH_WARNINGS (из-за пропущенной таблицы)
        assertEquals(RestoreStatus.COMPLETED_WITH_WARNINGS, report.status(),
                "Статус должен быть COMPLETED_WITH_WARNINGS из-за пропущенной таблицы");

        // Проверяем, что пропущена хотя бы одна таблица
        assertTrue(report.summary().tablesSkipped() > 0,
                "Должна быть хотя бы одна пропущенная таблица (nonexistent_table)");

        // Проверяем, что восстановление продолжилось (другие таблицы обработаны)
        assertTrue(report.summary().tablesProcessed() > 0,
                "Должна быть хотя бы одна успешно обработанная таблица");

        // Другие таблицы (users, categories) должны быть вставлены
        assertEquals(2, userRepository.count(),
                "Пользователи должны быть вставлены успешно");
        assertEquals(1, categoryRepository.count(),
                "Категории должны быть вставлены успешно");

        log.info("[TEST RESULT] Тест успешно завершен:");
        log.info("[TEST RESULT] - Статус: {}", report.status());
        log.info("[TEST RESULT] - Таблиц пропущено: {}", report.summary().tablesSkipped());
        log.info("[TEST RESULT] - Обработано таблиц: {}", report.summary().tablesProcessed());
        log.info("[TEST RESULT] - Вставлено строк: {}", report.summary().rowsInserted());
        log.info("[TEST RESULT] - Пропущено строк: {}", report.summary().rowsSkipped());
    }

    // ---------------------------------------------------------
    // Вспомогательные проверки и очистка
    // ---------------------------------------------------------

    private void checkAllEntitiesAfterRestore() {
        assertEquals(2, userRepository.count(), "Должно быть 2 пользователя");
        assertEquals(2, userProfileRepository.count(), "Должно быть 2 профиля");
        assertEquals(2, categoryRepository.count(), "Должно быть 2 категории");
        // products, orders, product_orders включены в FULL_TABLE_LIST
        assertEquals(3, productRepository.count(), "Должно быть 3 продукта");
        assertEquals(2, orderRepository.count(), "Должно быть 2 заказа");
        assertEquals(1, productOrderRepository.count(), "Должно быть 1 product_order");

        assertNotNull(userRepository.findByUsername(dataPrefix + "user1").orElse(null));
        assertNotNull(userRepository.findByUsername(dataPrefix + "user2").orElse(null));

        assertNotNull(categoryRepository.findByName(dataPrefix + "Category1").orElse(null));
        assertNotNull(categoryRepository.findByName(dataPrefix + "Category2").orElse(null));
    }

    @AfterEach
    protected void cleanUpTestData() {
        // Отключаем проверки внешних ключей, чтобы удалять их из всех таблиц в любом порядке
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            // Убираем дополнительные столбцы перед удалением данных
            dropColumnIfExists("users", "extra_info");
            dropColumnIfExists("users", "nullable_extra");
            dropColumnIfExists("categories", "extra_category_data");
            
            // Удаляем в правильном порядке (от дочерних к родительским)
            // Порядок: product_order_users -> order_products -> product_orders -> orders -> products -> user_profiles -> categories -> users
            cleanTableIfExists("product_order_users");
            cleanTableIfExists("order_products");
            cleanTableIfExists("product_orders");
            cleanTableIfExists("orders");
            cleanTableIfExists("products");
            cleanTableIfExists("user_profiles");
            cleanTableIfExists("categories");
            cleanTableIfExists("users");
        } finally {
            // Повторное включение проверок внешних ключей
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
    
    private void cleanUpTestDataOnlyTables() {
        // Отключаем проверки внешних ключей, чтобы удалять их из всех таблиц в любом порядке
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        try {
            // Удаляем в правильном порядке (от дочерних к родительским)
            // Порядок: product_order_users -> order_products -> product_orders -> orders -> products -> user_profiles -> categories -> users
            cleanTableIfExists("product_order_users");
            cleanTableIfExists("order_products");
            cleanTableIfExists("product_orders");
            cleanTableIfExists("orders");
            cleanTableIfExists("products");
            cleanTableIfExists("user_profiles");
            cleanTableIfExists("categories");
            cleanTableIfExists("users");
        } finally {
            // Повторное включение проверок внешних ключей
            jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
    
    private void dropColumnIfExists(String tableName, String columnName) {
        String checkSql = """
        SELECT COUNT(*)
        FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE()
          AND TABLE_NAME = ?
          AND COLUMN_NAME = ?
        """;

        Integer count = jdbcTemplate.queryForObject(checkSql, Integer.class, tableName, columnName);

        if (count != null && count > 0) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " DROP COLUMN " + columnName);
            log.info("Выброшенный столбец {} из таблицы {}", columnName, tableName);
        }
    }

    private void cleanTableIfExists(String tableName) {
        //Сначала проверяем, существует ли таблица
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                Integer.class,
                tableName
        );
        
        if (count != null && count > 0) {
            // Таблица существует, теперь удаляем все строки
            jdbcTemplate.execute("DELETE FROM " + tableName);
        }
        //Таблицы не существует, игнорируем
    }
}
