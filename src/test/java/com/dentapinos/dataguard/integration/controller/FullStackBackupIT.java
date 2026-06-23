package com.dentapinos.dataguard.integration.controller;

import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.dto.ExportResponse;
import com.dentapinos.dataguard.dto.TablesResponse;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Интеграционные тесты для REST-контроллера резервного копирования.
 * Проверяет HTTP-эндпоинты и взаимодействие с реальной базой данных через Testcontainers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {TestDatabaseConfig.class})
@WithResetDatabaseBeforeEach
@DisplayName("IT - REST-контроллера резервного копирования")
class FullStackBackupIT extends BaseResetDatabaseTest {

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
    @DisplayName("должен вернуть список таблиц для указанной базы данных")
    void shouldReturnTablesWhenDatabaseNameProvided() {
        // act
        ResponseEntity<TablesResponse> response = restTemplate.getForEntity(
                baseUrl + "/api/backup/" + databaseName + "/tables",
                TablesResponse.class
        );

        // assert
        assertThat(response.getBody()).isNotNull();
    }

    @Test
    @DisplayName("должен создать бэкап указанных таблиц")
    void shouldCreateBackupWhenTablesProvided() {
        // arrange
        String requestBody = "{\"tables\":[\"users\",\"orders\"]}";
        HttpEntity<String> request = new HttpEntity<>(requestBody, headers());

        // act
        ResponseEntity<ExportResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/backup/" + databaseName + "/tables",
                request,
                ExportResponse.class
        );

        // assert
        assertThat(response.getBody()).isNotNull();
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

    private HttpHeaders headers() {
        // arrange
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}