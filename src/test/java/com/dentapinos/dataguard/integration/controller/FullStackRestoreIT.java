package com.dentapinos.dataguard.integration.controller;

import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.dto.*;
import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.RestoreStatus;
import com.dentapinos.dataguard.exception.ApiErrorResponse;
import com.dentapinos.dataguard.report.RestoreReport;
import com.dentapinos.dataguard.service.BackupFacade;
import com.dentapinos.dataguard.storage.BackupStorage;
import com.dentapinos.dataguard.test.BaseResetDatabaseTest;
import com.dentapinos.dataguard.test.annotation.WithResetDatabaseBeforeEach;
import com.dentapinos.dataguard.test.config.TestDatabaseConfig;
import com.dentapinos.dataguard.test.domain.*;
import com.dentapinos.dataguard.utils.DatabaseConfigResolver;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Интеграционные тесты для REST-контроллера восстановления.
 * Проверяет HTTP-эндпоинты восстановления данных и взаимодействие с реальной базой данных через Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = { TestDatabaseConfig.class })
@WithResetDatabaseBeforeEach
@DisplayName("IT - REST-контроллера восстановления")
class FullStackRestoreIT extends BaseResetDatabaseTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

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

    private String baseUrl;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        // arrange
        baseUrl = "http://localhost:" + port;
    }

    @Test
    @DisplayName("должен вернуть список конфигурированных баз данных")
    void shouldReturnConfiguredDatabases() {
        // act
        ResponseEntity<BackupDatabasesProperties.DatabaseConfig[]> response = restTemplate.getForEntity(
                baseUrl + "/api/backup/databases",
                BackupDatabasesProperties.DatabaseConfig[].class
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertNotNull(response.getBody());
        assertThat(response.getBody().length).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("должен восстановить данные в существующую базу (после создания бэкапа)")
    void shouldRestoreToExistingDatabaseWhenBackupCreated() {
        // arrange
        Category category = categoryRepository.save(Category.builder()
                .name("Electronics")
                .description("Electronic items")
                .build());
        
        User user = userRepository.save(User.builder()
                .username("john_doe")
                .email("john@example.com")
                .build());
        
        UserProfile userProfile = userProfileRepository.save(UserProfile.builder()
                .user(user)
                .bio("John Doe")
                .avatarUrl("123 Main St")
                .build());
        
        Product product = productRepository.save(Product.builder()
                .name("Laptop")
                .description("Laptop")
                .price(999.99)
                .stockQuantity(100)
                .category(category)
                .user(user)
                .build());
        
        Order order = orderRepository.save(Order.builder()
                .orderNumber("ORD-001")
                .status(OrderStatus.SHIPPED)
                .user(user)
                .build());
        
        productOrderRepository.save(ProductOrder.builder()
                .product(product)
                .order(order)
                .quantity(1)
                .totalPrice(999.99)
                .build());

        entityManager.clear();

        String backupName = createBackup("testdb", List.of("users", "user_profile", "categories", "products", "orders", "product_orders"));

        assertNotNull(backupName);
        assertTrue(backupName.endsWith(".zip"));

        resetDatabase();

        RestoreRequest request = new RestoreRequest(
                backupName,
                "testdb",
                RestoreMode.STRICT,
                List.of("users", "user_profile", "categories", "products", "orders", "product_orders")
        );

        HttpEntity<RestoreRequest> requestEntity = new HttpEntity<>(request, headers());

        // act
        ResponseEntity<RestoreReport> response = restTemplate.postForEntity(
                baseUrl + "/api/restore?tier=DAILY",
                requestEntity,
                RestoreReport.class
        );

        // assert
        assertThat(response.getBody()).isNotNull();
        RestoreReport report = response.getBody();
        
        assertThat(report.status()).isIn(RestoreStatus.SUCCESS, RestoreStatus.COMPLETED_WITH_WARNINGS);
        assertTrue(report.summary().tablesProcessed() > 0);
    }

    @Test
    @DisplayName("должен вернуть совместимость схемы при анализе бэкапа")
    void shouldReturnSchemaCompatibilityWhenAnalyzingBackup() {
        // arrange
        Category category = categoryRepository.save(Category.builder()
                .name("Electronics")
                .description("Electronic items")
                .build());
        
        User user = userRepository.save(User.builder()
                .username("john_doe")
                .email("john@example.com")
                .build());
        
        Product product = productRepository.save(Product.builder()
                .name("Laptop")
                .description("Laptop")
                .price(999.99)
                .stockQuantity(100)
                .category(category)
                .user(user)
                .build());
        
        entityManager.clear();

        String backupName = createBackup("testdb", List.of("users", "user_profile", "categories", "products"));

        assertNotNull(backupName);

        AnalyzeSchemaRequest request = new AnalyzeSchemaRequest(
                backupName,
                "testdb"
        );

        HttpEntity<AnalyzeSchemaRequest> requestEntity = new HttpEntity<>(request, headers());

        // act
        ResponseEntity<SchemaCompatibilityAnalysisDto> response = restTemplate.postForEntity(
                baseUrl + "/api/restore/testdb/analyze-schema?tier=DAILY",
                requestEntity,
                SchemaCompatibilityAnalysisDto.class
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        SchemaCompatibilityAnalysisDto analysis = response.getBody();
        
        assertTrue(analysis.compatibleStrict() || analysis.compatibleRelaxed());
    }

    @Test
    @DisplayName("должен вернуть ошибку 409 при восстановлении в существующую базу данных")
    void shouldReturnConflictWhenRestoringToExistingDatabase() {
        // arrange
        Category category = categoryRepository.save(Category.builder()
                .name("Electronics")
                .description("Electronic items")
                .build());
        
        User user = userRepository.save(User.builder()
                .username("john_doe")
                .email("john@example.com")
                .build());
        
        Product product = productRepository.save(Product.builder()
                .name("Laptop")
                .description("Laptop")
                .price(999.99)
                .stockQuantity(100)
                .category(category)
                .user(user)
                .build());
        
        entityManager.clear();

        String backupName = createBackup("testdb", List.of("users", "user_profile", "categories", "products"));

        assertNotNull(backupName);

        String existingDatabaseName = "testdb";
        String existingDatabaseUrl = "jdbc:mysql://localhost:3306/" + existingDatabaseName + "?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true";
        
        RestoreToNewDatabaseRequest request = new RestoreToNewDatabaseRequest(
                backupName,
                existingDatabaseName,
                new DbCredentials(existingDatabaseUrl, "testuser", "testpass")
        );

        HttpEntity<RestoreToNewDatabaseRequest> requestEntity = new HttpEntity<>(request, headers());

        // act
        ResponseEntity<ApiErrorResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/restore/new-database?tier=DAILY",
                requestEntity,
                ApiErrorResponse.class
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetails()).contains("уже существует");
    }

    @Test
    @DisplayName("должен вернуть ошибку 404 при восстановлении из несуществующего бэкапа")
    void shouldReturnNotFoundWhenRestoringNonExistentBackup() {
        // arrange
        RestoreRequest request = new RestoreRequest(
                "nonexistent_backup.zip",
                "nonexistent_db",
                RestoreMode.STRICT,
                null
        );

        HttpEntity<RestoreRequest> requestEntity = new HttpEntity<>(request, headers());

        // act
        ResponseEntity<ApiErrorResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/restore?tier=DAILY",
                requestEntity,
                ApiErrorResponse.class
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("должен вернуть ошибку 404 при анализе схемы для несуществующей базы")
    void shouldReturnNotFoundWhenAnalyzingNonExistentDatabase() {
        // arrange
        AnalyzeSchemaRequest request = new AnalyzeSchemaRequest(
                "nonexistent_backup.zip",
                "nonexistent_db"
        );

        HttpEntity<AnalyzeSchemaRequest> requestEntity = new HttpEntity<>(request, headers());

        // act
        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "/api/restore/nonexistent_db/analyze-schema?tier=DAILY",
                requestEntity,
                String.class
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("должен вернуть успех при запросе к эндпоинту списка БД")
    void shouldReturnSuccessWhenQueryingDatabasesEndpoint() {
        // act
        ResponseEntity<String> response = restTemplate.getForEntity(
                baseUrl + "/api/backup/databases",
                String.class
        );

        // assert
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * Создает бэкап указанных таблиц для указанной базы данных.
     */
    private String createBackup(String databaseName, List<String> tables) {
        BackupTablesRequest request = new BackupTablesRequest(tables);
        
        HttpEntity<BackupTablesRequest> requestEntity = new HttpEntity<>(request, headers());

        ResponseEntity<ExportResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/backup/" + databaseName + "/tables",
                requestEntity,
                ExportResponse.class
        );

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return response.getBody().backupName();
        }
        
        return null;
    }

    private HttpHeaders headers() {
        // arrange
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
