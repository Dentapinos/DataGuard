package com.dentapinos.dataguard.unit.storage;

import com.dentapinos.dataguard.exception.BackupStorageException;
import com.dentapinos.dataguard.storage.BackupFileNamingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Юнит-тесты для BackupFileNamingService.
 * Проверяет генерацию, парсинг и валидацию имен резервных файлов.
 */
@DisplayName("Unit-test для сервиса именования резервных файлов")
class BackupFileNamingServiceTest {

    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC);

    private BackupFileNamingService service;

    @BeforeEach
    void setUp() {
        // arrange
        service = new BackupFileNamingService();
    }

    // ============================
    // generateBackupFileName tests
    // ============================

    @Test
    @DisplayName("должен генерировать имя файла с базовым форматом")
    void shouldGenerateBasicBackupFileName() {
        // arrange
        String database = "testdb";
        String extension = "zip";

        // act
        String filename = service.generateBackupFileName(database, extension);

        // assert
        assertThat(filename).startsWith("backup-" + database + "-");
        assertThat(filename).endsWith(".zip");
        assertValidTimestampInFilename(filename, database);
    }

    @Test
    @DisplayName("должен генерировать имя файла без расширения, если extension равен null")
    void shouldGenerateFileNameWithoutExtensionWhenExtensionIsNull() {
        // arrange
        String database = "testdb";
        String extension = null;

        // act
        String filename = service.generateBackupFileName(database, extension);

        // assert
        assertThat(filename).startsWith("backup-" + database + "-");
        assertThat(filename).doesNotEndWith(".");
        assertThat(filename).doesNotContain(".zip");
    }

    @Test
    @DisplayName("должен генерировать имя файла без расширения, если extension пустая")
    void shouldGenerateFileNameWithoutExtensionWhenExtensionIsEmpty() {
        // arrange
        String database = "testdb";
        String extension = "";

        // act
        String filename = service.generateBackupFileName(database, extension);

        // assert
        assertThat(filename).startsWith("backup-" + database + "-");
        assertThat(filename).doesNotEndWith(".");
    }

    @Test
    @DisplayName("должен генерировать имя файла без расширения, если extension состоит из пробелов")
    void shouldGenerateFileNameWithoutExtensionWhenExtensionIsBlank() {
        // arrange
        String database = "testdb";
        String extension = "   ";

        // act
        String filename = service.generateBackupFileName(database, extension);

        // assert
        assertThat(filename).startsWith("backup-" + database + "-");
        assertThat(filename).doesNotEndWith(".");
    }

    @Test
    @DisplayName("должен генерировать имя файла с пользовательским расширением")
    void shouldGenerateFileNameWithCustomExtension() {
        // arrange
        String database = "mydb";
        String extension = "tar.gz";

        // act
        String filename = service.generateBackupFileName(database, extension);

        // assert
        assertThat(filename).startsWith("backup-" + database + "-");
        assertThat(filename).endsWith(".tar.gz");
    }

    @Test
    @DisplayName("должен генерировать имя файла с именем базы, содержащим цифры и подчеркивания")
    void shouldGenerateFileNameWithDatabaseNameContainingNumbersAndUnderscores() {
        // arrange
        String database = "user_db_2024";
        String extension = "zip";

        // act
        String filename = service.generateBackupFileName(database, extension);

        // assert
        assertThat(filename).startsWith("backup-" + database + "-");
        assertThat(filename).endsWith(".zip");
        assertValidTimestampInFilename(filename, database);
    }

    @Test
    @DisplayName("должен выбрасывать BackupStorageException, если database равен null")
    void shouldThrowExceptionWhenDatabaseIsNull() {
        // arrange
        String database = null;
        String extension = "zip";

        // act + assert
        BackupStorageException ex = assertThrows(
                BackupStorageException.class,
                () -> service.generateBackupFileName(database, extension)
        );

        assertThat(ex.getMessage()).contains("не может быть пустым или null");
    }

    @Test
    @DisplayName("должен выбрасывать BackupStorageException, если database пустая")
    void shouldThrowExceptionWhenDatabaseIsEmpty() {
        // arrange
        String database = "";
        String extension = "zip";

        // act + assert
        BackupStorageException ex = assertThrows(
                BackupStorageException.class,
                () -> service.generateBackupFileName(database, extension)
        );

        assertThat(ex.getMessage()).contains("не может быть пустым или null");
    }

    @Test
    @DisplayName("должен выбрасывать BackupStorageException, если database состоит из пробелов")
    void shouldThrowExceptionWhenDatabaseIsBlank() {
        // arrange
        String database = "   ";
        String extension = "zip";

        // act + assert
        BackupStorageException ex = assertThrows(
                BackupStorageException.class,
                () -> service.generateBackupFileName(database, extension)
        );

        assertThat(ex.getMessage()).contains("не может быть пустым или null");
    }

    @Test
    @DisplayName("должен выбрасывать BackupStorageException, если database содержит недопустимые символы")
    void shouldThrowExceptionWhenDatabaseContainsInvalidCharacters() {
        // arrange
        String[] invalidDatabases = {
                "test-db",      // дефис
                "test.db",      // точка
                "test db",      // пробел
                "тест",         // кириллица
                "test@db",      // специальный символ
                "test!db",      // восклицательный знак
        };

        // act + assert
        for (String database : invalidDatabases) {
            BackupStorageException ex = assertThrows(
                    BackupStorageException.class,
                    () -> service.generateBackupFileName(database, "zip"),
                    "Expected exception for database: " + database
            );
            assertThat(ex.getMessage()).contains("недопустимые символы");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"testdb", "TESTDB", "TestDb123", "a", "a1", "a_b_c"})
    @DisplayName("должен принимать допустимые имена баз данных")
    void shouldAcceptValidDatabaseNames(String database) {
        // arrange
        String extension = "zip";

        // act
        String filename = service.generateBackupFileName(database, extension);

        // assert
        assertThat(filename).startsWith("backup-" + database + "-");
        assertThat(filename).endsWith(".zip");
    }

    // ============================
    // parseDatabaseName tests
    // ============================

    @Test
    @DisplayName("должен извлекать имя базы из валидного имени файла")
    void shouldParseDatabaseNameFromValidFileName() {
        // arrange
        String database = "mydatabase";
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
        String filename = "backup-" + database + "-" + timestamp + ".zip";

        // act
        String parsedDatabase = service.parseDatabaseName(filename);

        // assert
        assertThat(parsedDatabase).isEqualTo(database);
    }

    @Test
    @DisplayName("должен извлекать имя базы из имени файла без расширения (без строгой валидации)")
    void shouldParseDatabaseNameFromFileNameWithoutExtension() {
        // arrange
        String database = "mydatabase";
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
        String filename = "backup-" + database + "-" + timestamp;

        // act
        String parsedDatabase = service.parseDatabaseNameWithValidation(filename, false);

        // assert
        assertThat(parsedDatabase).isEqualTo(database);
    }

    @Test
    @DisplayName("должен выбрасывать BackupStorageException, если filename равен null")
    void shouldThrowExceptionWhenFileNameIsNull() {
        // arrange
        String filename = null;

        // act + assert
        BackupStorageException ex = assertThrows(
                BackupStorageException.class,
                () -> service.parseDatabaseName(filename)
        );

        assertThat(ex.getMessage()).contains("не может быть пустым или null");
    }

    @Test
    @DisplayName("должен выбрасывать BackupStorageException, если filename пустая")
    void shouldThrowExceptionWhenFileNameIsEmpty() {
        // arrange
        String filename = "";

        // act + assert
        BackupStorageException ex = assertThrows(
                BackupStorageException.class,
                () -> service.parseDatabaseName(filename)
        );

        assertThat(ex.getMessage()).contains("не может быть пустым или null");
    }

    @Test
    @DisplayName("должен выбрасывать BackupStorageException, если filename состоит из пробелов")
    void shouldThrowExceptionWhenFileNameIsBlank() {
        // arrange
        String filename = "   ";

        // act + assert
        BackupStorageException ex = assertThrows(
                BackupStorageException.class,
                () -> service.parseDatabaseName(filename)
        );

        assertThat(ex.getMessage()).contains("не может быть пустым или null");
    }

    @Test
    @DisplayName("должен выбрасывать BackupStorageException, если filename не соответствует формату (строгая валидация)")
    void shouldThrowExceptionWhenFileNameFormatIsInvalid() {
        // arrange
        String[] invalidFilenames = {
                "backup.zip",                   // нет имени базы
                "backup-testdb",                // нет временной метки
                "testdb-backup-2024-01-01T12-00-00.zip",  // неправильный порядок
                "backup-test db-2024-01-01T12-00-00.zip", // пробел в имени базы
                "backup-testdb-2024-01-01 12-00-00.zip",  // неправильный формат времени
                "testdb-backup-2024-01-01T12-00-00",      // нет расширения
        };

        // act + assert
        for (String filename : invalidFilenames) {
            BackupStorageException ex = assertThrows(
                    BackupStorageException.class,
                    () -> service.parseDatabaseName(filename),
                    "Expected exception for filename: " + filename
            );
            assertThat(ex.getMessage()).contains("не соответствует формату");
        }
    }

    @Test
    @DisplayName("должен извлекать имя базы без строгой валидации")
    void shouldParseDatabaseNameWithoutStrictValidation() {
        // arrange
        String database = "testdb";
        String[] filenames = {
                "backup-" + database,                    // без временной метки
                "backup-" + database + "-2024-01-01T12-00-00",  // без расширения
        };

        // act + assert
        for (String filename : filenames) {
            String parsed = service.parseDatabaseNameWithValidation(filename, false);
            assertThat(parsed).isEqualTo(database);
        }
    }

    // ============================
    // isValidFileName tests
    // ============================

    @Test
    @DisplayName("должен возвращать true для валидного имени файла")
    void shouldReturnTrueForValidFileName() {
        // arrange
        String database = "testdb";
        String timestamp = TIMESTAMP_FORMATTER.format(Instant.now());
        String filename = "backup-" + database + "-" + timestamp + ".zip";

        // act
        boolean result = service.isValidFileName(filename);

        // assert
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("должен возвращать false для невалидного имени файла")
    void shouldReturnFalseForInvalidFileName() {
        // arrange
        String[] invalidFilenames = {
                "backup.zip",
                "backup-testdb",
                "backup-test db-2024-01-01T12-00-00.zip",
                "testdb-backup-2024-01-01T12-00-00.zip",
        };

        // act + assert
        for (String filename : invalidFilenames) {
            boolean result = service.isValidFileName(filename);
            assertThat(result).isFalse();
        }
    }

    @Test
    @DisplayName("должен возвращать false для null")
    void shouldReturnFalseForNull() {
        // arrange
        String filename = null;

        // act
        boolean result = service.isValidFileName(filename);

        // assert
        assertThat(result).isFalse();
    }

    // ============================
    // removeFileExtension tests
    // ============================

    @Test
    @DisplayName("должен удалять расширение из имени файла")
    void shouldRemoveFileExtension() {
        // arrange
        String filename = "backup-testdb-2024-01-01T12-00-00.zip";

        // act
        String result = service.removeFileExtension(filename);

        // assert
        assertThat(result).isEqualTo("backup-testdb-2024-01-01T12-00-00");
    }

    @Test
    @DisplayName("должен возвращать имя файла без изменений, если нет расширения")
    void shouldReturnFileNameWithoutChangesWhenNoExtension() {
        // arrange
        String filename = "backup-testdb-2024-01-01T12-00-00";

        // act
        String result = service.removeFileExtension(filename);

        // assert
        assertThat(result).isEqualTo(filename);
    }

    @Test
    @DisplayName("должен возвращать имя файла без изменений, если точка в конце (без расширения)")
    void shouldReturnFileNameWithoutChangesWhenDotAtEnd() {
        // arrange
        String filename = "test.";

        // act
        String result = service.removeFileExtension(filename);

        // assert
        assertThat(result).isEqualTo(filename);
    }

    @Test
    @DisplayName("должен обрабатывать имя файла с несколькими точками")
    void shouldHandleFileNameWithMultipleDots() {
        // arrange
        String filename = "backup-testdb-2024-01-01T12-00-00.tar.gz";

        // act
        String result = service.removeFileExtension(filename);

        // assert
        assertThat(result).isEqualTo("backup-testdb-2024-01-01T12-00-00.tar");
    }

    // ============================
    // Helper methods
    // ============================

    private void assertValidTimestampInFilename(String filename, String expectedDatabase) {
        // arrange
        String pattern = "^backup-" + Pattern.quote(expectedDatabase) + "-(\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2})\\.zip$";
        Pattern p = Pattern.compile(pattern);
        var matcher = p.matcher(filename);

        // assert
        assertThat(matcher.matches())
                .as("Filename should match expected pattern: " + filename)
                .isTrue();
    }
}
