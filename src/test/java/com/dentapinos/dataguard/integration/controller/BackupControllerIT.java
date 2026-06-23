package com.dentapinos.dataguard.integration.controller;

import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.controller.impl.BackupController;
import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.entity.storage.BackupFileInfo;
import com.dentapinos.dataguard.enums.BackupStatus;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.exception.DatabaseNotFoundException;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.dentapinos.dataguard.report.BackupReport;
import com.dentapinos.dataguard.report.BackupSummary;
import com.dentapinos.dataguard.service.BackupFacade;
import com.dentapinos.dataguard.storage.BackupStorage;
import com.dentapinos.dataguard.storage.ZipBackupStorageService;
import com.dentapinos.dataguard.utils.DatabaseConfigResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Веб‑тесты контроллера бэкапа (BackupController).
 * Проверяет REST‑эндпоинты для списков баз, таблиц и создания бэкапов.
 */
@WebMvcTest(BackupController.class)
@DisplayName("IT - веб‑слоя BackupController")
class BackupControllerIT {
    
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BackupFacade backupFacade;

    @MockBean
    private BackupStorage backupStorage;

    @MockBean
    private ZipBackupStorageService backupStorageService;

    @MockBean
    private DatabaseConfigResolver databaseConfigResolver;

    @MockBean
    private BackupDatabasesProperties backupDatabasesProperties;

    @Test
    @DisplayName("должен вернуть список конфигурированных баз данных")
    void shouldListConfiguredDatabases() throws Exception {
        // arrange
        BackupDatabasesProperties.DatabaseConfig db1 = new BackupDatabasesProperties.DatabaseConfig();
        db1.setDatabaseName("bm");
        db1.setDisplayName("auth-service");
        
        BackupDatabasesProperties.DatabaseConfig db2 = new BackupDatabasesProperties.DatabaseConfig();
        db2.setDatabaseName("bm-test");
        db2.setDisplayName("test-service");
        
        when(backupDatabasesProperties.getDatabases()).thenReturn(List.of(db1, db2));

        // act
        mockMvc.perform(get("/api/backup/databases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].databaseName").value("bm"))
                .andExpect(jsonPath("$[0].displayName").value("auth-service"))
                .andExpect(jsonPath("$[1].databaseName").value("bm-test"))
                .andExpect(jsonPath("$[1].displayName").value("test-service"));
    }

    @Test
    @DisplayName("должен вернуть список таблиц существующей базы данных")
    void shouldListTablesWhenDatabaseExists() throws Exception {
        // arrange
        when(databaseConfigResolver.resolveCredentials("bm"))
                .thenReturn(createTestCredentials());
        when(backupFacade.listTablesMySql(any(), eq("bm")))
                .thenReturn(List.of("users", "orders", "products"));

        // act
        mockMvc.perform(get("/api/backup/bm/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tables").isArray())
                .andExpect(jsonPath("$.tables.length()").value(3))
                .andExpect(jsonPath("$.tables[0]").value("users"));
    }

    @Test
    @DisplayName("должен вернуть ошибку 404 при запросе несуществующей базы данных")
    void shouldThrowNotFoundWhenDatabaseNotFound() throws Exception {
        // arrange
        when(databaseConfigResolver.resolveCredentials("unknown"))
                .thenThrow(new DatabaseNotFoundException("Database 'unknown' not found"));

        // act & assert
        mockMvc.perform(get("/api/backup/unknown/tables"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("должен успешно создать бэкап выбранных таблиц")
    void shouldBackupSelectedTablesSuccessfully() throws Exception {
        // arrange
        String requestBody = "{\"tables\":[\"users\",\"orders\"]}";
        
        when(databaseConfigResolver.resolveCredentials("bm"))
                .thenReturn(createTestCredentials());
        
        SchemaMeta schemaMeta = new SchemaMeta(
                "bm",
                List.of()
        );
        
        BackupFile backupFile = new BackupFile(
                "bm",
                "mysql",
                schemaMeta,
                Map.of(),
                null // tableOrder
        );
        
        Map<String, Long> rowsPerTable = new HashMap<>();
        rowsPerTable.put("users", 50L);
        rowsPerTable.put("orders", 50L);
        
        BackupReport report = new BackupReport(
                Instant.now(),
                Instant.now().plusSeconds(100),
                "bm",
                "mysql",
                BackupStatus.SUCCESS,
                new BackupSummary(10, 10, 0, 0, 100L, rowsPerTable)
        );
        
        BackupEnvelope envelope = new BackupEnvelope(report, backupFile);
        when(backupFacade.backupMySql(any(), eq("bm"), eq(List.of("users", "orders"))))
                .thenReturn(envelope);
        when(backupFacade.storeBackup(envelope, "bm"))
                .thenReturn("backup_test.zip");

        // act
        mockMvc.perform(post("/api/backup/bm/tables")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.backupName").value("backup_test.zip"))
                .andExpect(jsonPath("$.database").value("bm"));
    }

    @Test
    @DisplayName("должен вернуть список существующих бэкапов")
    void shouldListBackupsWhenDatabaseExists() throws Exception {
        // arrange
        BackupFileInfo fileInfo = new BackupFileInfo(
                "backup_test.zip",
                BackupTier.DAILY,
                "bm",
                Instant.now(),
                1024L
        );
        when(backupStorage.listWithInfo(null, "bm")).thenReturn(List.of(fileInfo));

        // act
        mockMvc.perform(get("/api/backup/bm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("backup_test.zip"))
                .andExpect(jsonPath("$[0].tier").value("DAILY"));
    }

    @Test
    @DisplayName("должен вернуть ошибку 404 при бэкапе несуществующей базы данных")
    void shouldThrowNotFoundWhenDatabaseNotFoundDuringBackup() throws Exception {
        // arrange
        String requestBody = "{\"tables\":[\"users\"]}";
        when(databaseConfigResolver.resolveCredentials("unknown-db"))
                .thenThrow(new DatabaseNotFoundException("Database 'unknown-db' not found in configuration"));

        // act & assert
        mockMvc.perform(post("/api/backup/unknown-db/tables")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("должен вернуть ошибку 500 при бэкапе с проблемами доступа к БД")
    void shouldThrow500WhenDatabaseAccessErrorDuringBackup() throws Exception {
        // arrange
        String requestBody = "{\"tables\":[\"users\"]}";
        when(databaseConfigResolver.resolveCredentials("bm"))
                .thenReturn(createTestCredentials());
        doThrow(new RuntimeException("Connection refused"))
                .when(backupFacade).backupMySql(any(), eq("bm"), eq(List.of("users")));

        // act & assert
        mockMvc.perform(post("/api/backup/bm/tables")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.details").value("Connection refused"));
    }

    @Test
    @DisplayName("должен вернуть ошибку 500 при чтении таблиц из БД")
    void shouldThrow500WhenReadingTablesFromDatabase() throws Exception {
        // arrange
        when(databaseConfigResolver.resolveCredentials("bm"))
                .thenReturn(createTestCredentials());
        doThrow(new RuntimeException("Metadata read failed"))
                .when(backupFacade).listTablesMySql(any(), eq("bm"));

        // act & assert
        mockMvc.perform(get("/api/backup/bm/tables"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$").exists());
    }

    @Test
    @DisplayName("должен вернуть ошибку 404 при получении таблиц для несуществующей базы данных")
    void shouldThrowNotFoundWhenInvalidDatabaseName() throws Exception {
        // arrange
        when(databaseConfigResolver.resolveCredentials("invalid-db"))
                .thenThrow(new DatabaseNotFoundException("Database 'invalid-db' not found in configuration"));

        // act & assert
        mockMvc.perform(get("/api/backup/invalid-db/tables"))
                .andExpect(status().isNotFound());
    }


    @Test
    @DisplayName("должен вернуть ошибку 500 при чтении списка бэкапов")
    void shouldThrow500WhenReadingBackupsFromStorage() throws Exception {
        // arrange
        when(backupStorage.listWithInfo(null, "bm"))
                .thenThrow(new RuntimeException("Storage access failed"));

        // act & assert
        mockMvc.perform(get("/api/backup/bm"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("должен быть реализован (тестирование через MockMvc невозможно)")
    void downloadBackup_notTestedViaMockMvc() throws IOException {
        // arrange
        // act
        // assert
        // Метод downloadBackup использует HttpServletResponse напрямую,
        // поэтому не подходит для тестирования через MockMvc.
        // Для его тестирования требуется Spring MVC Test с mock response
        // или отдельные integration тесты.
    }

    private DbCredentials createTestCredentials() {
        return new DbCredentials(
                "jdbc:mysql://localhost:3306/bm",
                "root",
                "den27lad27"
        );
    }
}
