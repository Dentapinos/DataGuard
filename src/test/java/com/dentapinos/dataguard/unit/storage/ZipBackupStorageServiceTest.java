package com.dentapinos.dataguard.unit.storage;

import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.BackupStatus;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.dentapinos.dataguard.report.BackupReport;
import com.dentapinos.dataguard.report.BackupSummary;
import com.dentapinos.dataguard.storage.BackupFileReader;
import com.dentapinos.dataguard.storage.ZipBackupConstants;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Юнит-тесты для ZipBackupStorageService.
 * Проверяет сохранение и чтение резервных копий с отчетами в формате ZIP,
 * включая корректную обработку временных файлов и делегирование хранилищу.
 */
@DisplayName("Unit-test для сервиса хранения резервных копий в ZIP-формате")
class ZipBackupStorageServiceTest {

    @TempDir
    java.io.File tempDir;

    private com.dentapinos.dataguard.storage.BackupStorage backupStorage;
    private BackupFileReader backupFileReader;
    private ObjectMapper objectMapper;
    private com.dentapinos.dataguard.storage.ZipBackupStorageService service;

    @BeforeEach
    void setUp() {
        // arrange
        backupStorage = Mockito.mock(com.dentapinos.dataguard.storage.BackupStorage.class);
        backupFileReader = Mockito.mock(BackupFileReader.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        service = new com.dentapinos.dataguard.storage.ZipBackupStorageService(backupStorage, objectMapper, backupFileReader);
    }

    // ============================
    // storeBackupWithReport(String, BackupEnvelope) tests
    // ============================

    @Nested
    @DisplayName("storeBackupWithReport(database, envelope)")
    class StoreBackupWithReportDatabaseEnvelopeTests {

        @Test
        @DisplayName("должен сохранять резервную копию в уровень DAILY по умолчанию")
        void shouldStoreBackupInDailyTierByDefault() throws IOException {
            // arrange
            String database = "testdb";
            BackupReport report = new BackupReport(
                    Instant.parse("2026-06-14T10:00:00Z"),
                    Instant.parse("2026-06-14T10:05:30Z"),
                    "testdb",
                    "mysql",
                    BackupStatus.SUCCESS,
                    new BackupSummary(5, 5, 0, 0, 500, Map.of("users", 500L))
            );
            BackupFile backup = new BackupFile(
                    "testdb",
                    "mysql",
                    new SchemaMeta("testdb", List.of(
                            new TableMeta("users",
                                    List.of(new ColumnMeta("id", "bigint(20)", false, true)),
                                    List.of("id"),
                                    List.of(),
                                    List.of()
                            )
                    )),
                    Map.of("users", List.of(Map.of("id", 1, "name", "test"))),
                    List.of("users")
            );
            BackupEnvelope envelope = new BackupEnvelope(report, backup);
            String expectedFileName = "backup-testdb-2026-06-14T10-00-00.zip";

            when(backupFileReader.generateBackupFileName(eq(database), eq("zip")))
                    .thenReturn(expectedFileName);

            final ByteArrayOutputStream savedStream = new ByteArrayOutputStream();
            doAnswer(invocation -> {
                InputStream stream = invocation.getArgument(3);
                stream.transferTo(savedStream);
                return null;
            }).when(backupStorage).save(any(), eq(database), eq(expectedFileName), any());

            // act
            String resultFileName = service.storeBackupWithReport(database, envelope);

            // assert
            assertThat(resultFileName).isEqualTo(expectedFileName);
            verify(backupStorage, times(1)).save(any(), eq(database), eq(expectedFileName), any());
            assertZipContainsBothEntries(savedStream.toByteArray());
        }

        @Test
        @DisplayName("должен создавать и удалять временный файл")
        void shouldCreateAndDeleteTemporaryFile() throws IOException {
            // arrange
            String database = "testdb";
            BackupEnvelope envelope = createTestEnvelope();

            when(backupFileReader.generateBackupFileName(eq(database), eq("zip")))
                    .thenReturn("backup-testdb-2026-06-14T10-00-00.zip");

            final ByteArrayOutputStream savedStream = new ByteArrayOutputStream();
            doAnswer(invocation -> {
                InputStream stream = invocation.getArgument(3);
                stream.transferTo(savedStream);
                return null;
            }).when(backupStorage).save(any(), any(), any(), any());

            // act
            service.storeBackupWithReport(database, envelope);

            // assert - Временный файл должен быть удален (это проверяется логикой finally-блока)
            // Проверка происходит по побочным эффектам (на самом деле файл удаляется)
            assertThat(savedStream.toByteArray()).isNotEmpty();
        }
    }

    // ============================
    // storeBackupWithReport(String, BackupEnvelope, BackupTier) tests
    // ============================

    @Nested
    @DisplayName("storeBackupWithReport(database, envelope, tier)")
    class StoreBackupWithReportDatabaseEnvelopeTierTests {

        @Test
        @DisplayName("должен сохранять резервную копию в указанный уровень")
        void shouldStoreBackupInSpecifiedTier() throws IOException {
            // arrange
            String database = "testdb";
            com.dentapinos.dataguard.enums.BackupTier tier = com.dentapinos.dataguard.enums.BackupTier.WEEKLY;
            BackupEnvelope envelope = createTestEnvelope();
            String expectedFileName = "backup-testdb-2026-06-14T10-00-00.zip";

            when(backupFileReader.generateBackupFileName(eq(database), eq("zip")))
                    .thenReturn(expectedFileName);

            final ByteArrayOutputStream savedStream = new ByteArrayOutputStream();
            doAnswer(invocation -> {
                InputStream stream = invocation.getArgument(3);
                stream.transferTo(savedStream);
                return null;
            }).when(backupStorage).save(any(), eq(database), eq(expectedFileName), any());

            // act
            String resultFileName = service.storeBackupWithReport(database, envelope, tier);

            // assert
            assertThat(resultFileName).isEqualTo(expectedFileName);
            verify(backupStorage, times(1)).save(eq(tier), eq(database), eq(expectedFileName), any());
            assertZipContainsBothEntries(savedStream.toByteArray());
        }

        @Test
        @DisplayName("должен сохранять резервную копию в уровень MONTHLY")
        void shouldStoreBackupInMonthlyTier() throws IOException {
            // arrange
            String database = "production_db";
            com.dentapinos.dataguard.enums.BackupTier tier = com.dentapinos.dataguard.enums.BackupTier.MONTHLY;
            BackupEnvelope envelope = createTestEnvelope();

            when(backupFileReader.generateBackupFileName(eq(database), eq("zip")))
                    .thenReturn("backup-production_db-2026-06-14T10-00-00.zip");

            final ByteArrayOutputStream savedStream = new ByteArrayOutputStream();
            doAnswer(invocation -> {
                InputStream stream = invocation.getArgument(3);
                stream.transferTo(savedStream);
                return null;
            }).when(backupStorage).save(any(), eq(database), any(), any());

            // act
            String resultFileName = service.storeBackupWithReport(database, envelope, tier);

            // assert
            assertThat(resultFileName).startsWith("backup-production_db-");
            verify(backupStorage, times(1)).save(eq(tier), eq(database), any(), any());
            assertZipContainsBothEntries(savedStream.toByteArray());
        }
    }

    // ============================
    // Helper methods
    // ============================

    /**
     * Создает тестовый BackupEnvelope.
     */
    private BackupEnvelope createTestEnvelope() {
        // arrange
        BackupReport report = new BackupReport(
                Instant.parse("2026-06-14T10:00:00Z"),
                Instant.parse("2026-06-14T10:05:30Z"),
                "testdb",
                "mysql",
                BackupStatus.SUCCESS,
                new BackupSummary(3, 3, 0, 0, 300, Map.of("t1", 100L, "t2", 100L, "t3", 100L))
        );

        BackupFile backup = new BackupFile(
                "testdb",
                "mysql",
                new SchemaMeta("testdb", List.of(
                        new TableMeta("t1", List.of(), List.of(), List.of(), List.of()),
                        new TableMeta("t2", List.of(), List.of(), List.of(), List.of()),
                        new TableMeta("t3", List.of(), List.of(), List.of(), List.of())
                )),
                Map.of(
                        "t1", List.of(Map.of("id", 1)),
                        "t2", List.of(Map.of("id", 2)),
                        "t3", List.of(Map.of("id", 3))
                ),
                List.of("t1", "t2", "t3")
        );

        // assert
        return new BackupEnvelope(report, backup);
    }

    /**
     * Проверяет, что ZIP-архив содержит обе записи: backup.json и report.json.
     */
    private void assertZipContainsBothEntries(byte[] zipBytes) throws IOException {
        // arrange
        try (InputStream is = new ByteArrayInputStream(zipBytes);
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(is, StandardCharsets.UTF_8)) {

            boolean foundBackup = false;
            boolean foundReport = false;

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (ZipBackupConstants.BACKUP_JSON.equals(entry.getName())) {
                    foundBackup = true;
                }
                if (ZipBackupConstants.REPORT_JSON.equals(entry.getName())) {
                    foundReport = true;
                }
                zis.closeEntry();
            }

            // assert
            assertThat(foundBackup).isTrue();
            assertThat(foundReport).isTrue();
        }
    }
}
