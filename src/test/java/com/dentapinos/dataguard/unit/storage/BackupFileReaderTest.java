package com.dentapinos.dataguard.unit.storage;

import com.dentapinos.dataguard.entity.ColumnMeta;
import com.dentapinos.dataguard.entity.SchemaMeta;
import com.dentapinos.dataguard.entity.TableMeta;
import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.enums.BackupStatus;
import com.dentapinos.dataguard.exception.RestoreZipException;
import com.dentapinos.dataguard.report.BackupReport;
import com.dentapinos.dataguard.report.BackupSummary;
import com.dentapinos.dataguard.storage.BackupFileNamingService;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Юнит-тесты для BackupFileReader.
 * Проверяет чтение и десериализацию backup.json и report.json из ZIP-архивов,
 * обработку ошибок для поврежденных архивов, отсутствующих записей и некорректного JSON.
 */
@DisplayName("Unit-test для сервиса чтения резервных файлов")
class BackupFileReaderTest {

    @TempDir
    java.io.File tempDir;

    private BackupFileReader reader;
    private BackupFileNamingService namingService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        // arrange
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        namingService = Mockito.mock(BackupFileNamingService.class);
        reader = new BackupFileReader(objectMapper, namingService);
    }

    // ============================
    // readReport tests
    // ============================

    @Nested
    @DisplayName("readReport")
    class ReadReportTests {

        @Test
        @DisplayName("должен читать и десериализовать BackupReport из ZIP-архива")
        void shouldReadBackupReportFromZipArchive() throws IOException {
            // arrange
            BackupReport expectedReport = new BackupReport(
                    Instant.parse("2026-06-14T10:00:00Z"),
                    Instant.parse("2026-06-14T10:05:30Z"),
                    "testdb",
                    "mysql",
                    BackupStatus.SUCCESS,
                    new BackupSummary(10, 10, 0, 0, 1000, Map.of("users", 1000L))
            );

            String json = objectMapper.writeValueAsString(expectedReport);
            byte[] zipBytes = createZipWithEntry(ZipBackupConstants.REPORT_JSON, json.getBytes(StandardCharsets.UTF_8));

            // act
            BackupReport result = reader.readReport(new ByteArrayInputStream(zipBytes));

            // assert
            assertThat(result).isEqualTo(expectedReport);
        }

        @Test
        @DisplayName("должен выбрасывать RestoreZipException, если запись report.json не найдена в ZIP-архиве")
        void shouldThrowRestoreZipExceptionWhenReportNotFound() {
            // arrange
            byte[] zipBytes = createZipWithEntry("other.json", "some data".getBytes(StandardCharsets.UTF_8));

            // act + assert
            RestoreZipException ex = assertThrows(RestoreZipException.class,
                    () -> reader.readReport(new ByteArrayInputStream(zipBytes)));

            assertThat(ex.getMessage()).contains("Запись не найдена в ZIP-архиве: " + ZipBackupConstants.REPORT_JSON);
        }

        @Test
        @DisplayName("должен выбрасывать RestoreZipException, если JSON поврежден")
        void shouldThrowRestoreZipExceptionWhenJsonIsCorrupted() {
            // arrange
            String invalidJson = "not valid json at all";
            byte[] zipBytes = createZipWithEntry(ZipBackupConstants.REPORT_JSON, invalidJson.getBytes(StandardCharsets.UTF_8));

            // act + assert
            RestoreZipException ex = assertThrows(RestoreZipException.class,
                    () -> reader.readReport(new ByteArrayInputStream(zipBytes)));

            assertThat(ex.getMessage()).contains("Ошибка десериализации JSON");
        }

        @Test
        @DisplayName("должен закрывать InputStream после чтения")
        void shouldCloseInputStream() throws IOException {
            // arrange
            BackupReport report = new BackupReport(
                    Instant.now(), Instant.now(), "db", "mysql", BackupStatus.SUCCESS,
                    new BackupSummary(1, 1, 0, 0, 1, Map.of())
            );
            String json = objectMapper.writeValueAsString(report);
            byte[] zipBytes = createZipWithEntry(ZipBackupConstants.REPORT_JSON, json.getBytes(StandardCharsets.UTF_8));

            // act + assert
            try (InputStream stream = new ByteArrayInputStream(zipBytes)) {
                reader.readReport(stream);
            }
            // Если исключение не выброшено, тест прошел успешно ( InputStream был закрыт правильно )
        }
    }

    // ============================
    // readBackupFile tests
    // ============================

    @Nested
    @DisplayName("readBackupFile")
    class ReadBackupFileTests {

        @Test
        @DisplayName("должен читать и десериализовать BackupFile из ZIP-архива")
        void shouldReadBackupFileFromZipArchive() throws IOException {
            // arrange
            BackupFile expectedBackup = new BackupFile(
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

            String json = objectMapper.writeValueAsString(expectedBackup);
            byte[] zipBytes = createZipWithEntry(ZipBackupConstants.BACKUP_JSON, json.getBytes(StandardCharsets.UTF_8));

            // act
            BackupFile result = reader.readBackupFile(new ByteArrayInputStream(zipBytes));

            // assert
            assertThat(result).isEqualTo(expectedBackup);
        }

        @Test
        @DisplayName("должен выбрасывать RestoreZipException, если запись backup.json не найдена в ZIP-архиве")
        void shouldThrowRestoreZipExceptionWhenBackupNotFound() {
            // arrange
            byte[] zipBytes = createZipWithEntry("other.json", "some data".getBytes(StandardCharsets.UTF_8));

            // act + assert
            RestoreZipException ex = assertThrows(RestoreZipException.class,
                    () -> reader.readBackupFile(new ByteArrayInputStream(zipBytes)));

            assertThat(ex.getMessage()).contains("Запись не найдена в ZIP-архиве: " + ZipBackupConstants.BACKUP_JSON);
        }

        @Test
        @DisplayName("должен выбрасывать RestoreZipException, если JSON поврежден")
        void shouldThrowRestoreZipExceptionWhenJsonIsCorrupted() {
            // arrange
            String invalidJson = "not valid json at all";
            byte[] zipBytes = createZipWithEntry(ZipBackupConstants.BACKUP_JSON, invalidJson.getBytes(StandardCharsets.UTF_8));

            // act + assert
            RestoreZipException ex = assertThrows(RestoreZipException.class,
                    () -> reader.readBackupFile(new ByteArrayInputStream(zipBytes)));

            assertThat(ex.getMessage()).contains("Ошибка десериализации JSON");
        }

        @Test
        @DisplayName("должен закрывать InputStream после чтения")
        void shouldCloseInputStream() throws IOException {
            // arrange
            BackupFile backup = new BackupFile(
                    "db", "mysql", new SchemaMeta("db", List.of()),
                    Map.of(), List.of()
            );
            String json = objectMapper.writeValueAsString(backup);
            byte[] zipBytes = createZipWithEntry(ZipBackupConstants.BACKUP_JSON, json.getBytes(StandardCharsets.UTF_8));

            // act + assert
            try (InputStream stream = new ByteArrayInputStream(zipBytes)) {
                reader.readBackupFile(stream);
            }
            // Если исключение не выброшено, тест прошел успешно ( InputStream был закрыт правильно )
        }
    }

    // ============================
    // readFromZip tests
    // ============================

    @Nested
    @DisplayName("readFromZip")
    class ReadFromZipTests {

        @Test
        @DisplayName("должен читать и десериализовать произвольный JSON из ZIP-архива")
        void shouldReadCustomJsonFromZipArchive() throws IOException {
            // arrange
            String expectedValue = "test value";
            byte[] jsonBytes = objectMapper.writeValueAsBytes(expectedValue);
            byte[] zipBytes = createZipWithEntry("custom.json", jsonBytes);

            // act
            String result = reader.readFromZip(new ByteArrayInputStream(zipBytes), "custom.json", String.class);

            // assert
            assertThat(result).isEqualTo(expectedValue);
        }

        @Test
        @DisplayName("должен выбрасывать RestoreZipException, если запись не найдена в ZIP-архиве")
        void shouldThrowRestoreZipExceptionWhenEntryNotFound() {
            // arrange
            byte[] zipBytes = createZipWithEntry("other.json", "data".getBytes(StandardCharsets.UTF_8));

            // act + assert
            RestoreZipException ex = assertThrows(RestoreZipException.class,
                    () -> reader.readFromZip(new ByteArrayInputStream(zipBytes), "missing.json", String.class));

            assertThat(ex.getMessage()).contains("Запись не найдена в ZIP-архиве: missing.json");
        }

        @Test
        @DisplayName("должен выбрасывать RestoreZipException при ошибке десериализации JSON")
        void shouldThrowRestoreZipExceptionOnDeserializeError() {
            // arrange
            String invalidJson = "not valid json at all";
            byte[] zipBytes = createZipWithEntry("custom.json", invalidJson.getBytes(StandardCharsets.UTF_8));

            // act + assert
            RestoreZipException ex = assertThrows(RestoreZipException.class,
                    () -> reader.readFromZip(new ByteArrayInputStream(zipBytes), "custom.json", String.class));

            assertThat(ex.getMessage()).contains("Ошибка десериализации JSON");
        }

        @Test
        @DisplayName("должен поддерживать чтение комплексных объектов из ZIP-архива")
        void shouldSupportReadingComplexObjectsFromZipArchive() throws IOException {
            // arrange
            BackupReport report = new BackupReport(
                    Instant.now(), Instant.now(), "db", "mysql", BackupStatus.FAILED,
                    new BackupSummary(5, 3, 1, 1, 150, Map.of("t1", 50L, "t2", 100L))
            );
            String json = objectMapper.writeValueAsString(report);
            byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
            byte[] zipBytes = createZipWithEntry("report.json", jsonBytes);

            // act
            BackupReport result = reader.readFromZip(new ByteArrayInputStream(zipBytes), "report.json", BackupReport.class);

            // assert
            assertThat(result).isEqualTo(report);
        }
    }

    // ============================
    // generateBackupFileName tests
    // ============================

    @Nested
    @DisplayName("generateBackupFileName")
    class GenerateBackupFileNameTests {

        @Test
        @DisplayName("должен делегировать генерацию имени файла в BackupFileNamingService")
        void shouldDelegateToNamingService() {
            // arrange
            String database = "testdb";
            String extension = "zip";
            String expectedName = "backup-testdb-2026-06-22T12-00-00.zip";

            Mockito.when(namingService.generateBackupFileName(database, extension))
                    .thenReturn(expectedName);

            // act
            String result = reader.generateBackupFileName(database, extension);

            // assert
            assertThat(result).isEqualTo(expectedName);
            Mockito.verify(namingService).generateBackupFileName(database, extension);
        }
    }

    // ============================
    // Helper methods
    // ============================

    /**
     * Создает ZIP-архив с одной записью.
     *
     * @param entryName имя записи внутри ZIP
     * @param content   содержимое записи
     * @return массив байт ZIP-архива
     */
    private byte[] createZipWithEntry(String entryName, byte[] content) {
        // arrange
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos)) {

            ZipEntry entry = new ZipEntry(entryName);
            zos.putNextEntry(entry);
            zos.write(content);
            zos.closeEntry();
            zos.finish();

            // assert
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to create test ZIP", e);
        }
    }
}
