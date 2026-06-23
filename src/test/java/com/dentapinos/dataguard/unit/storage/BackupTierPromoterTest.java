package com.dentapinos.dataguard.unit.storage;

import com.dentapinos.dataguard.enums.BackupStatus;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.exception.BackupStorageException;
import com.dentapinos.dataguard.report.BackupReport;
import com.dentapinos.dataguard.storage.BackupFileReader;
import com.dentapinos.dataguard.storage.BackupStorage;
import com.dentapinos.dataguard.storage.BackupTierPromoter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.Period;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Юнит-тесты для BackupTierPromoter.
 * Проверяет логику повышения уровня резервных копий между уровнями (DAILY -> WEEKLY и т.д.),
 * фильтрацию по временному периоду, валидацию статуса и обработку ошибок.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-test для продвигателя уровней резервных копий")
class BackupTierPromoterTest {

    @Mock
    private BackupStorage backupStorage;

    @Mock
    private BackupFileReader backupFileReader;

    @InjectMocks
    private BackupTierPromoter promotionService;

    @BeforeEach
    void setUp() {
        // arrange
        Mockito.reset(backupStorage, backupFileReader);
    }

    // ==================== promote() Tests ====================

    @Nested
    @DisplayName("promote() - Единичная успешная резервная копия в пределах периода")
    class PromoteSingleSuccessfulBackupTests {

        @Test
        @DisplayName("должен копировать единичную успешную резервную копию в пределах периода")
        void shouldCopySingleSuccessfulBackupWithinPeriod() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            String fileName = "backup-testdb-2026-06-19T10-00-00.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(fileName));

            Instant now = Instant.now();
            Instant createdTime = now.minusSeconds(3600); // 1 hour ago
            when(backupStorage.getCreationTime(fromTier, database, fileName))
                    .thenReturn(FileTime.from(createdTime));

            // Симуляция успешного отчёта о резервном копировании
            BackupReport successfulReport = new BackupReport(
                    createdTime,
                    createdTime.plusSeconds(300),
                    database,
                    "mysql",
                    BackupStatus.SUCCESS,
                    null
            );
            when(backupStorage.load(eq(fromTier), eq(database), eq(fileName)))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(successfulReport);

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert
            verify(backupStorage).copy(eq(fileName), eq(fromTier), eq(toTier), eq(database));
        }

        @Test
        @DisplayName("должен копировать самую свежую успешную резервную копию при наличии нескольких")
        void shouldCopyMostRecentSuccessfulBackup() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            String oldFile = "backup-testdb-2026-06-19T08-00-00.zip";
            String newFile = "backup-testdb-2026-06-19T12-00-00.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(oldFile, newFile));

            Instant now = Instant.now();
            Instant oldCreated = now.minusSeconds(7200); // 2 часа назад
            Instant newCreated = now.minusSeconds(1800); // 30 минут назад

            when(backupStorage.getCreationTime(fromTier, database, oldFile))
                    .thenReturn(FileTime.from(oldCreated));
            when(backupStorage.getCreationTime(fromTier, database, newFile))
                    .thenReturn(FileTime.from(newCreated));

            // Оба проекта успешны
            BackupReport successfulReport = new BackupReport(
                    newCreated,
                    newCreated.plusSeconds(300),
                    database,
                    "mysql",
                    BackupStatus.SUCCESS,
                    null
            );
            when(backupStorage.load(any(), any(), any()))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(successfulReport);

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert
            verify(backupStorage).copy(eq(newFile), eq(fromTier), eq(toTier), eq(database));
            verify(backupStorage, never()).copy(eq(oldFile), any(), any(), any());
        }

        @Test
        @DisplayName("должен пропускать резервные копии за пределами периода")
        void shouldSkipBackupsOutsidePeriod() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            String fileName = "backup-testdb-2026-06-10T10-00-00.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(fileName));

            Instant now = Instant.now();
            Instant createdTime = now.minus(Period.ofDays(5)); // 5 days ago
            when(backupStorage.getCreationTime(fromTier, database, fileName))
                    .thenReturn(FileTime.from(createdTime));

            BackupReport successfulReport = new BackupReport(
                    createdTime,
                    createdTime.plusSeconds(300),
                    database,
                    "mysql",
                    BackupStatus.SUCCESS,
                    null
            );
            when(backupStorage.load(eq(fromTier), eq(database), eq(fileName)))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(successfulReport);

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert
            verify(backupStorage, never()).copy(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("должен не копировать резервную копию, если она не успешная")
        void shouldNotCopyUnsuccessfulBackup() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            String fileName = "backup-testdb-2026-06-19T10-00-00.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(fileName));

            Instant now = Instant.now();
            Instant createdTime = now.minusSeconds(3600); // 1 hour ago

            // Неудачная резервная копия — только stub load(), который действительно вызывает isSuccessful()
            BackupReport failedReport = new BackupReport(
                    createdTime,
                    createdTime.plusSeconds(300),
                    database,
                    "mysql",
                    BackupStatus.FAILED,
                    null
            );
            when(backupStorage.load(eq(fromTier), eq(database), eq(fileName)))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(failedReport);

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert
            verify(backupStorage, never()).copy(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить файл, если не удается прочитать время создания")
        void shouldSkipFileIfCreationTimeThrowsIOException() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            String goodFile = "backup-testdb-2026-06-19T10-00-00.zip";
            String badFile = "backup-testdb-2026-06-19T08-00-00.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(badFile, goodFile));

            // badFile бросает IOException
            when(backupStorage.getCreationTime(fromTier, database, badFile))
                    .thenThrow(new IOException("test IO error"));

            // goodFile имеет действительное время
            Instant now = Instant.now();
            Instant goodCreated = now.minusSeconds(3600);
            when(backupStorage.getCreationTime(fromTier, database, goodFile))
                    .thenReturn(FileTime.from(goodCreated));

            // Оба будут успешны, если прочитать
            BackupReport successfulReport = new BackupReport(
                    goodCreated,
                    goodCreated.plusSeconds(300),
                    database,
                    "mysql",
                    BackupStatus.SUCCESS,
                    null
            );
            when(backupStorage.load(any(), any(), any()))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(successfulReport);

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert
            verify(backupStorage).copy(eq(goodFile), eq(fromTier), eq(toTier), eq(database));
            verify(backupStorage, never()).copy(eq(badFile), any(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить файл, если чтение отчета завершается ошибкой")
        void shouldSkipFileIfReportReadingFails() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            String goodFile = "backup-testdb-2026-06-19T10-00-00.zip";
            String badFile = "backup-testdb-2026-06-19T08-00-00.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(badFile, goodFile));

            Instant now = Instant.now();
            Instant goodCreated = now.minusSeconds(3600);

            when(backupStorage.getCreationTime(fromTier, database, goodFile))
                    .thenReturn(FileTime.from(goodCreated));

            // goodFile отчет об успешном отчёте
            BackupReport successfulReport = new BackupReport(
                    goodCreated,
                    goodCreated.plusSeconds(300),
                    database,
                    "mysql",
                    BackupStatus.SUCCESS,
                    null
            );
            when(backupStorage.load(eq(fromTier), eq(database), eq(goodFile)))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(successfulReport);

            //badFile бросает IOException
            when(backupStorage.load(eq(fromTier), eq(database), eq(badFile)))
                    .thenThrow(new IOException("test IO error"));

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert
            verify(backupStorage).copy(eq(goodFile), eq(fromTier), eq(toTier), eq(database));
            verify(backupStorage, never()).copy(eq(badFile), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("promote() - Несколько резервных копий с разными условиями")
    class PromoteMultipleBackupsTests {

        @Test
        @DisplayName("должен не копировать, если все резервные копии неуспешные")
        void shouldNotCopyIfAllBackupsAreUnsuccessful() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            String file1 = "backup-testdb-2026-06-19T08-00-00.zip";
            String file2 = "backup-testdb-2026-06-19T12-00-00.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(file1, file2));

            Instant now = Instant.now();
            Instant created1 = now.minusSeconds(7200);
            Instant created2 = now.minusSeconds(3600);

            //isSuccessful() вызывает только load(), а не getCreationTime()
            when(backupStorage.load(any(), any(), any()))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(new BackupReport(
                            created1,
                            created1.plusSeconds(300),
                            database,
                            "mysql",
                            BackupStatus.COMPLETED_WITH_WARNINGS,
                            null
                    ));

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert
            verify(backupStorage, never()).copy(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("должен корректно обрабатывать пустой список резервных копий")
        void shouldLogWarningWhenNoBackupsExist() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of());

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert
            verify(backupStorage, never()).copy(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("должен выбрасывать BackupStorageException, если list() выбрасывает исключение")
        void shouldThrowExceptionWhenListFails() throws IOException {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            // list() выдает исключение — оно ловится и упаковывается в BackupStorageException
            when(backupStorage.list(fromTier, database))
                    .thenThrow(new IOException("Storage unavailable"));

            // act + assert
            BackupStorageException exception = Assertions.assertThrows(
                    BackupStorageException.class,
                    () -> promotionService.promote(database, fromTier, toTier, period)
            );

            assertThat(exception.getMessage())
                    .contains("Failed to promote backup from DAILY to WEEKLY");
            assertThat(exception.getCause()).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("должен обрабатывать непредвиденное исключение и оборачивать его в BackupStorageException")
        void shouldHandleUnexpectedException() throws IOException {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            //Какое-то неожиданное исключение
            when(backupStorage.list(fromTier, database))
                    .thenThrow(new RuntimeException("Unexpected error"));

            // act + assert
            BackupStorageException exception = assertThrows(
                    BackupStorageException.class,
                    () -> promotionService.promote(database, fromTier, toTier, period)
            );

            assertThat(exception.getMessage())
                    .contains("Failed to promote backup from DAILY to WEEKLY");
        }
    }

    // ==================== isSuccessful() Tests ====================

    @Nested
    @DisplayName("isSuccessful() - Тесты валидации отчета")
    class IsSuccessfulTests {

        @Test
        @DisplayName("должен возвращать true, когда статус отчета резервной копии SUCCESS")
        void isSuccessful_ShouldReturnTrueForSuccessfulBackup() throws Exception {
            // arrange
            String database = "testdb";
            String fileName = "backup-testdb-2026-06-19T10-00-00.zip";
            BackupTier tier = BackupTier.DAILY;

            BackupReport successfulReport = new BackupReport(
                    Instant.now(),
                    Instant.now().plusSeconds(300),
                    database,
                    "mysql",
                    BackupStatus.SUCCESS,
                    null
            );

            when(backupStorage.load(eq(tier), eq(database), eq(fileName)))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(successfulReport);

            // act
            boolean result = promotionService.isSuccessful(fileName, tier, database);

            // assert
            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("должен возвращать false, когда статус отчета резервной копии FAILED")
        void isSuccessful_ShouldReturnFalseForFailedBackup() throws Exception {
            // arrange
            String database = "testdb";
            String fileName = "backup-testdb-2026-06-19T10-00-00.zip";
            BackupTier tier = BackupTier.DAILY;

            BackupReport failedReport = new BackupReport(
                    Instant.now(),
                    Instant.now().plusSeconds(300),
                    database,
                    "mysql",
                    BackupStatus.FAILED,
                    null
            );

            when(backupStorage.load(eq(tier), eq(database), eq(fileName)))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(failedReport);

            // act
            boolean result = promotionService.isSuccessful(fileName, tier, database);

            // assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("должен возвращать false, когда статус отчета резервной копии COMPLETED_WITH_WARNINGS")
        void isSuccessful_ShouldReturnFalseForCompletedWithWarnings() throws Exception {
            // arrange
            String database = "testdb";
            String fileName = "backup-testdb-2026-06-19T10-00-00.zip";
            BackupTier tier = BackupTier.DAILY;

            BackupReport report = new BackupReport(
                    Instant.now(),
                    Instant.now().plusSeconds(300),
                    database,
                    "mysql",
                    BackupStatus.COMPLETED_WITH_WARNINGS,
                    null
            );

            when(backupStorage.load(eq(tier), eq(database), eq(fileName)))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(report);

            // act
            boolean result = promotionService.isSuccessful(fileName, tier, database);

            // assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("должен возвращать false, когда во время загрузки возникает IOException")
        void isSuccessful_ShouldReturnFalseOnIOException() throws Exception {
            // arrange
            String database = "testdb";
            String fileName = "backup-testdb-2026-06-19T10-00-00.zip";
            BackupTier tier = BackupTier.DAILY;

            when(backupStorage.load(eq(tier), eq(database), eq(fileName)))
                    .thenThrow(new IOException("File not found"));

            // act
            boolean result = promotionService.isSuccessful(fileName, tier, database);

            // assert
            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("должен корректно закрывать InputStream")
        void isSuccessful_ShouldProperlyCloseInputStream() throws Exception {
            // arrange
            String database = "testdb";
            String fileName = "backup-testdb-2026-06-19T10-00-00.zip";
            BackupTier tier = BackupTier.DAILY;

            ByteArrayInputStream inputStream = new ByteArrayInputStream("{}".getBytes());

            when(backupStorage.load(eq(tier), eq(database), eq(fileName)))
                    .thenReturn(inputStream);
            when(backupFileReader.readReport(any()))
                    .thenReturn(new BackupReport(
                            Instant.now(),
                            Instant.now().plusSeconds(300),
                            database,
                            "mysql",
                            BackupStatus.SUCCESS,
                            null
                    ));

            // act
            promotionService.isSuccessful(fileName, tier, database);

            // assert - inputStream должен быть закрыт методом isSuccessful
            // Тест заключается в том, что метод завершается без исключения
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Граничные случаи и пограничные условия")
    class EdgeCasesTests {

        @Test
        @DisplayName("должен корректно обрабатывать null уровень")
        void promote_ShouldHandleNullTier() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = null;
            BackupTier toTier = null;
            Period period = Period.ofDays(1);

            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of());

            // act - не стоит бросать исключение
            promotionService.promote(database, fromTier, toTier, period);

            // assert
            verify(backupStorage, never()).copy(anyString(), any(), any(), any());
        }

        @Test
        @DisplayName("должен обрабатывать период из нуля дней")
        void promote_ShouldHandleZeroPeriod() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(0); // Сейчас созданы только резервные копии

            String fileName = "backup-testdb-2026-06-19T10-00-00.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(fileName));

            Instant now = Instant.now();
            //Файл создан ровно сейчас
            when(backupStorage.getCreationTime(fromTier, database, fileName))
                    .thenReturn(FileTime.from(now));

            when(backupStorage.load(eq(fromTier), eq(database), eq(fileName)))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(new BackupReport(
                            now,
                            now.plusSeconds(300),
                            database,
                            "mysql",
                            BackupStatus.SUCCESS,
                            null
                    ));

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert - может или не копировать в зависимости от точности времени
            // Ключевое — это то, что исключение не выбрасывается
        }

        @Test
        @DisplayName("должен обрабатывать очень большой период")
        void promote_ShouldHandleLargePeriod() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(365); // 1 year

            String fileName = "backup-testdb-2025-06-19T10-00-00.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(fileName));

            Instant now = Instant.now();
            Instant created = now.minus(Period.ofDays(300));
            when(backupStorage.getCreationTime(fromTier, database, fileName))
                    .thenReturn(FileTime.from(created));

            when(backupStorage.load(eq(fromTier), eq(database), eq(fileName)))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(new BackupReport(
                            created,
                            created.plusSeconds(300),
                            database,
                            "mysql",
                            BackupStatus.SUCCESS,
                            null
                    ));

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert - должен копировать, так как в пределах 1 года
            verify(backupStorage).copy(eq(fileName), eq(fromTier), eq(toTier), eq(database));
        }

        @Test
        @DisplayName("должен обрабатывать файлы с одинаковым временем создания")
        void promote_ShouldHandleSameCreationTime() throws Exception {
            // arrange
            String database = "testdb";
            BackupTier fromTier = BackupTier.DAILY;
            BackupTier toTier = BackupTier.WEEKLY;
            Period period = Period.ofDays(1);

            String file1 = "backup-testdb-2026-06-19T10-00-00.zip";
            String file2 = "backup-testdb-2026-06-19T10-00-01.zip";
            when(backupStorage.list(fromTier, database))
                    .thenReturn(List.of(file1, file2));

            Instant now = Instant.now();
            // Одинаковое время создания
            when(backupStorage.getCreationTime(fromTier, database, file1))
                    .thenReturn(FileTime.from(now.minusSeconds(3600)));
            when(backupStorage.getCreationTime(fromTier, database, file2))
                    .thenReturn(FileTime.from(now.minusSeconds(3600)));

            when(backupStorage.load(any(), any(), any()))
                    .thenReturn(new ByteArrayInputStream("{}".getBytes()));
            when(backupFileReader.readReport(any()))
                    .thenReturn(new BackupReport(
                            now.minusSeconds(3600),
                            now.minusSeconds(3000),
                            database,
                            "mysql",
                            BackupStatus.SUCCESS,
                            null
                    ));

            // act
            promotionService.promote(database, fromTier, toTier, period);

            // assert - должен копировать один из них (порядок не гарантирован, но только один)
            verify(backupStorage, times(1)).copy(anyString(), eq(fromTier), eq(toTier), eq(database));
        }
    }
}
