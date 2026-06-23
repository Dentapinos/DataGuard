package com.dentapinos.dataguard.integration.controller;

import com.dentapinos.dataguard.controller.impl.RestoreController;
import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.dto.SchemaCompatibilityAnalysisDto;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.enums.RestoreMode;
import com.dentapinos.dataguard.enums.RestoreStatus;
import com.dentapinos.dataguard.exception.DatabaseCreationException;
import com.dentapinos.dataguard.exception.DatabaseNotFoundException;
import com.dentapinos.dataguard.report.RestoreReport;
import com.dentapinos.dataguard.report.RestoreSummary;
import com.dentapinos.dataguard.service.DatabaseSchemaCreator;
import com.dentapinos.dataguard.service.restore.RestoreService;
import com.dentapinos.dataguard.service.restore.SchemaCompatibilityService;
import com.dentapinos.dataguard.storage.BackupFileNamingService;
import com.dentapinos.dataguard.storage.BackupFileReader;
import com.dentapinos.dataguard.storage.BackupStorage;
import com.dentapinos.dataguard.utils.DatabaseConfigResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Веб‑тесты контроллера восстановления (RestoreController).
 * Проверяет REST‑эндпоинты для восстановления баз данных и анализа совместимости схем.
 */
@WebMvcTest(RestoreController.class)
@DisplayName("IT - REST-контроллера восстановления")
class RestoreControllerIT {
    
    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RestoreService restoreService;

    @MockBean
    private BackupStorage backupStorage;

    @MockBean
    private BackupFileNamingService fileNameParser;

    @MockBean
    private BackupFileReader backupFileReader;

    @MockBean
    private DatabaseConfigResolver databaseConfigResolver;

    @MockBean
    private DatabaseSchemaCreator databaseSchemaCreator;

    @MockBean
    private SchemaCompatibilityService schemaCompatibilityService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("должен успешно восстановить данные в существующую базу данных")
    void shouldRestoreToExistingDatabaseWhenRequestIsValid() throws Exception {
        // arrange
        String requestBody = "{\"backupName\":\"backup_test.zip\",\"targetDatabase\":\"bm\",\"mode\":\"STRICT\",\"tables\":[\"users\",\"orders\"]}";
        
        DbCredentials credentials = createTestCredentials();
        when(databaseConfigResolver.resolveCredentials("bm"))
                .thenReturn(credentials);
        
        RestoreReport report = new RestoreReport(
                Instant.now(),
                Instant.now().plusSeconds(100),
                "bm",
                RestoreMode.STRICT,
                RestoreStatus.SUCCESS,
                new RestoreSummary(2, 2, 0, 0, 100L, 50L, 0, null, null, null)
        );
        
        when(restoreService.restoreToExistingDatabase(any(), eq(BackupTier.DAILY), eq("backup_test.zip"), eq("bm"), eq(RestoreMode.STRICT), eq(List.of("users", "orders"))))
                .thenReturn(report);

        // act
        mockMvc.perform(post("/api/restore?tier=DAILY")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDatabase").value("bm"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        // assert
    }

    @Test
    @DisplayName("должен вернуть ошибку 404 при восстановлении в несуществующую базу данных")
    void shouldThrowDatabaseNotFoundWhenDatabaseDoesNotExist() throws Exception {
        // arrange
        String requestBody = "{\"backupName\":\"backup_test.zip\",\"targetDatabase\":\"unknown\",\"mode\":\"STRICT\",\"tables\":null}";
        when(databaseConfigResolver.resolveCredentials("unknown"))
                .thenThrow(new DatabaseNotFoundException("Database 'unknown' not found in configuration"));

        // act
        mockMvc.perform(post("/api/restore?tier=DAILY")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("База данных не найдена"));

        // assert
    }

    @Test
    @DisplayName("должен успешно проанализировать совместимость схемы")
    void shouldAnalyzeSchemaCompatibilityWhenRequestIsValid() throws Exception {
        // arrange
        String requestBody = "{\"backupName\":\"backup_test.zip\",\"targetDatabase\":\"target_db\"}";
        
        when(databaseConfigResolver.resolveCredentials("bm"))
                .thenReturn(createTestCredentials());
        
        BackupFile backupFile = new BackupFile(
                "bm",
                "mysql",
                null,
                null,
                null
        );
        
        when(backupStorage.load(any(), eq("bm"), eq("backup_test.zip")))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        when(backupFileReader.readBackupFile(any()))
                .thenReturn(backupFile);
        
        SchemaCompatibilityAnalysisDto analysis = new SchemaCompatibilityAnalysisDto(
                true,
                true,
                null,
                null,
                null
        );
        
        when(schemaCompatibilityService.analyzeCompatibility(any(), eq(backupFile), eq("target_db")))
                .thenReturn(analysis);

        // act
        mockMvc.perform(post("/api/restore/bm/analyze-schema?tier=DAILY")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.compatibleStrict").value(true));

        // assert
    }

    @Test
    @DisplayName("должен вернуть ошибку 404 при анализе совместимости для несуществующей базы данных")
    void shouldThrowNotFoundWhenDatabaseNotFoundDuringSchemaAnalysis() throws Exception {
        // arrange
        String requestBody = "{\"backupName\":\"backup_test.zip\",\"targetDatabase\":\"target_db\"}";
        when(databaseConfigResolver.resolveCredentials("unknown"))
                .thenThrow(new IllegalArgumentException("Database 'unknown' not found"));

        // act
        mockMvc.perform(post("/api/restore/unknown/analyze-schema?tier=DAILY")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isNotFound());

        // assert
    }

    @Test
    @DisplayName("должен вернуть ошибку 500 при ошибке чтения бэкапа при анализе совместимости c сообщением")
    void shouldThrow500WhenReadErrorDuringSchemaAnalysis() throws Exception {
        // arrange
        String requestBody = "{\"backupName\":\"backup_test.zip\",\"targetDatabase\":\"target_db\"}";
        
        when(databaseConfigResolver.resolveCredentials("bm"))
                .thenReturn(createTestCredentials());
        
        when(backupStorage.load(any(), eq("bm"), eq("backup_test.zip")))
                .thenThrow(new IOException("Failed to read backup file"));

        // act
        mockMvc.perform(post("/api/restore/bm/analyze-schema?tier=DAILY")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Внутренняя ошибка сервера"))
                .andExpect(jsonPath("$.details").value("Ошибка ввода-вывода при анализе файла backup_test.zip"));
        // assert
    }

    @Test
    @DisplayName("должен успешно восстановить данные в новую базу данных")
    void shouldRestoreToNewDatabaseWhenRequestIsValid() throws Exception {
        // arrange
        String requestBody = "{\"backupName\":\"backup_test.zip\",\"newDatabaseName\":\"restored_db\",\"newDatabaseCredentials\":{\"url\":\"jdbc:mysql://localhost:3306/restored_db\",\"username\":\"root\",\"password\":\"password\"}}";
        
        when(fileNameParser.parseDatabaseName("backup_test.zip"))
                .thenReturn("bm");
        
        BackupFile backupFile = new BackupFile(
                "bm",
                "mysql",
                null,
                null,
                null
        );
        
        when(backupStorage.load(any(), eq("bm"), eq("backup_test.zip")))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[0]));
        when(backupFileReader.readBackupFile(any()))
                .thenReturn(backupFile);
        
        RestoreReport report = new RestoreReport(
                Instant.now(),
                Instant.now().plusSeconds(100),
                "restored_db",
                RestoreMode.STRICT,
                RestoreStatus.SUCCESS,
                new RestoreSummary(2, 2, 0, 0, 100L, 50L, 0, null, null, null)
        );
        
        when(restoreService.restoreToExistingDatabase(any(), eq(BackupTier.DAILY), eq("backup_test.zip"), eq("restored_db"), eq(RestoreMode.STRICT), isNull()))
                .thenReturn(report);

        // act
        mockMvc.perform(post("/api/restore/new-database?tier=DAILY")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetDatabase").value("restored_db"));

        // assert
    }

    @Test
    @DisplayName("должен вернуть ошибку 409 при восстановлении в существующую базу данных (новая БД)")
    void shouldThrowConflictWhenDatabaseAlreadyExists() throws Exception {
        // arrange
        String requestBody = "{\"backupName\":\"backup_test.zip\",\"newDatabaseName\":\"existing_db\",\"newDatabaseCredentials\":{\"url\":\"jdbc:mysql://localhost:3306/existing_db\",\"username\":\"root\",\"password\":\"password\"}}";
        
        when(fileNameParser.parseDatabaseName("backup_test.zip"))
                .thenReturn("bm");
        
        doThrow(new DatabaseCreationException("Ошибка при создании базы данных", null))
                .when(databaseSchemaCreator).createDatabaseIfNotExistsElseException(any(), eq("existing_db"));

        // act
        mockMvc.perform(post("/api/restore/new-database?tier=DAILY")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.details").value("Ошибка при попытке восстановить данные в новую базу данных [existing_db], которая уже существует"));

        // assert
    }

    @Test
    @DisplayName("должен вернуть ошибку 500 при ошибке чтения бэкапа при восстановлении в новую базу данных")
    void shouldThrow500WhenReadErrorDuringRestoreToNewDatabase() throws Exception {
        // arrange
        String requestBody = "{\"backupName\":\"backup_test.zip\",\"newDatabaseName\":\"restored_db\",\"newDatabaseCredentials\":{\"url\":\"jdbc:mysql://localhost:3306/restored_db\",\"username\":\"root\",\"password\":\"password\"}}";
        
        when(fileNameParser.parseDatabaseName("backup_test.zip"))
                .thenReturn("bm");
        
        when(backupStorage.load(any(), eq("bm"), eq("backup_test.zip")))
                .thenThrow(new IOException("Failed to read backup file"));

        // act
        mockMvc.perform(post("/api/restore/new-database?tier=DAILY")
                        .contentType("application/json")
                        .content(requestBody))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$").value("Неожиданная ошибка при восстановлении в новую базу данных: Failed to read backup file"));

        // assert
    }

    private DbCredentials createTestCredentials() {
        return new DbCredentials(
                "jdbc:mysql://localhost:3306/bm",
                "root",
                "den27lad27"
        );
    }
}
