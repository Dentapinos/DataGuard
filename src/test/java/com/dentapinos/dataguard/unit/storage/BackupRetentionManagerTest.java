package com.dentapinos.dataguard.unit.storage;

import com.dentapinos.dataguard.config.BackupRetentionProperties;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.exception.BackupStorageException;
import com.dentapinos.dataguard.storage.BackupRetentionManager;
import com.dentapinos.dataguard.storage.BackupStorage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Юнит-тесты для BackupRetentionManager.
 * Проверяет применение политики хранения для всех уровней резервных копий (DAILY, WEEKLY, MONTHLY, SEMI_ANNUAL, ANNUAL),
 * включая граничные случаи, такие как отсутствие конфигурации, ошибки ввода-вывода и пустые списки файлов.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Unit-test для менеджера хранения резервных копий")
class BackupRetentionManagerTest {

    @Mock
    private BackupRetentionProperties backupRetentionProperties;

    @Mock
    private BackupStorage backupStorage;

    @InjectMocks
    private BackupRetentionManager retentionManager;

    @BeforeEach
    void setUp() {
        // arrange
        Mockito.reset(backupRetentionProperties, backupStorage);
    }

    // ==================== applyRetention(DAILY) Tests ====================

    @Nested
    @DisplayName("applyRetention(DAILY)")
    class DailyRetentionTests {

        @Test
        @DisplayName("должен удалять ежедневные резервные копии старше установленного срока хранения")
        void shouldDeleteDailyBackupsOlderThanRetentionPeriod() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getDailyDays()).thenReturn(3);

            String oldBackup = "backup-old.zip";
            String newBackup = "backup-new.zip";
            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenReturn(List.of(oldBackup, newBackup));

            // Рассчитайте порог: сейчас минус 3 дня
            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusDays(3);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            // oldBackup: на 10 дней старше отсечения
            Instant oldCreated = cutoffInstant.minusSeconds(10L * 24 * 3600);
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, oldBackup))
                    .thenReturn(FileTime.from(oldCreated));

            // newBackup: на 1 день позже границы
            Instant newCreated = cutoffInstant.plusSeconds(24 * 3600);
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, newBackup))
                    .thenReturn(FileTime.from(newCreated));

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(oldBackup));
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(newBackup));
        }

        @Test
        @DisplayName("должен ничего не делать, если список резервных копий пустой")
        void shouldDoNothingWhenBackupListIsEmpty() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getDailyDays()).thenReturn(7);
            when(backupStorage.list(BackupTier.DAILY, database)).thenReturn(List.of());

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert
            verify(backupStorage, never()).delete(any(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить файл, если getCreationTime выбрасывает IOException")
        void shouldSkipFileWhenGetCreationTimeFails() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getDailyDays()).thenReturn(7);

            String badFile = "bad.zip";
            String goodFile = "good.zip";
            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenReturn(List.of(badFile, goodFile));

            // badFile бросает IOException
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, badFile))
                    .thenThrow(new IOException("test IO"));

            // goodFile старше отсечения -> следует удалить
            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusDays(7);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant goodCreated = cutoffInstant.minusSeconds(3600);

            when(backupStorage.getCreationTime(BackupTier.DAILY, database, goodFile))
                    .thenReturn(FileTime.from(goodCreated));

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(badFile));
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(goodFile));
        }

        @Test
        @DisplayName("должен перехватывать IOException из list() и повторно выбрасывать как BackupStorageException")
        void shouldCatchIOExceptionFromList() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getDailyDays()).thenReturn(7);
            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenThrow(new IOException("list failed"));

            // act + assert
            BackupStorageException exception = Assertions.assertThrows(
                    BackupStorageException.class,
                    () -> retentionManager.applyRetention(BackupTier.DAILY, database)
            );

            assertThat(exception.getMessage())
                    .contains("Failed to apply retention for tier: DAILY");
            assertThat(exception.getCause()).isInstanceOf(IOException.class);
        }
    }

    // ==================== applyRetention(WEEKLY) Tests ====================

    @Nested
    @DisplayName("applyRetention(WEEKLY)")
    class WeeklyRetentionTests {

        @Test
        @DisplayName("должен удалять еженедельные резервные копии старше установленного срока хранения")
        void shouldDeleteWeeklyBackupsOlderThanRetentionPeriod() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getWeeklyWeeks()).thenReturn(2);

            String oldFile = "weekly-old.zip";
            String newFile = "weekly-new.zip";
            when(backupStorage.list(BackupTier.WEEKLY, database))
                    .thenReturn(List.of(oldFile, newFile));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusWeeks(2);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            Instant oldCreated = cutoffInstant.minusSeconds(3600); // before cutoff
            Instant newCreated = cutoffInstant.plusSeconds(24 * 3600); // after cutoff

            when(backupStorage.getCreationTime(BackupTier.WEEKLY, database, oldFile))
                    .thenReturn(FileTime.from(oldCreated));
            when(backupStorage.getCreationTime(BackupTier.WEEKLY, database, newFile))
                    .thenReturn(FileTime.from(newCreated));

            // act
            retentionManager.applyRetention(BackupTier.WEEKLY, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.WEEKLY), eq(database), eq(oldFile));
            verify(backupStorage, never()).delete(eq(BackupTier.WEEKLY), eq(database), eq(newFile));
        }

        @Test
        @DisplayName("должен обрабатывать пустой список еженедельных резервных копий")
        void shouldDoNothingWhenWeeklyBackupListIsEmpty() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getWeeklyWeeks()).thenReturn(4);
            when(backupStorage.list(BackupTier.WEEKLY, database)).thenReturn(List.of());

            // act
            retentionManager.applyRetention(BackupTier.WEEKLY, database);

            // assert
            verify(backupStorage, never()).delete(any(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить файл, если getCreationTime выбрасывает IOException для еженедельного уровня")
        void shouldSkipFileWhenGetCreationTimeFailsForWeekly() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getWeeklyWeeks()).thenReturn(2);

            String badFile = "bad.zip";
            String goodFile = "good.zip";
            when(backupStorage.list(BackupTier.WEEKLY, database))
                    .thenReturn(List.of(badFile, goodFile));

            when(backupStorage.getCreationTime(BackupTier.WEEKLY, database, badFile))
                    .thenThrow(new IOException("test IO"));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusWeeks(2);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant goodCreated = cutoffInstant.minusSeconds(7200);

            when(backupStorage.getCreationTime(BackupTier.WEEKLY, database, goodFile))
                    .thenReturn(FileTime.from(goodCreated));

            // act
            retentionManager.applyRetention(BackupTier.WEEKLY, database);

            // assert
            verify(backupStorage, never()).delete(eq(BackupTier.WEEKLY), eq(database), eq(badFile));
            verify(backupStorage).delete(eq(BackupTier.WEEKLY), eq(database), eq(goodFile));
        }
    }

    // ==================== applyRetention(MONTHLY) Tests ====================

    @Nested
    @DisplayName("applyRetention(MONTHLY)")
    class MonthlyRetentionTests {

        @Test
        @DisplayName("должен удалять ежемесячные резервные копии старше установленного срока хранения")
        void shouldDeleteMonthlyBackupsOlderThanRetentionPeriod() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getMonthlyMonths()).thenReturn(3);

            String oldFile = "monthly-old.zip";
            String newFile = "monthly-new.zip";
            when(backupStorage.list(BackupTier.MONTHLY, database))
                    .thenReturn(List.of(oldFile, newFile));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusMonths(3);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            Instant oldCreated = cutoffInstant.minusSeconds(3600);
            Instant newCreated = cutoffInstant.plusSeconds(24 * 3600);

            when(backupStorage.getCreationTime(BackupTier.MONTHLY, database, oldFile))
                    .thenReturn(FileTime.from(oldCreated));
            when(backupStorage.getCreationTime(BackupTier.MONTHLY, database, newFile))
                    .thenReturn(FileTime.from(newCreated));

            // act
            retentionManager.applyRetention(BackupTier.MONTHLY, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.MONTHLY), eq(database), eq(oldFile));
            verify(backupStorage, never()).delete(eq(BackupTier.MONTHLY), eq(database), eq(newFile));
        }

        @Test
        @DisplayName("должен обрабатывать пустой список ежемесячных резервных копий")
        void shouldDoNothingWhenMonthlyBackupListIsEmpty() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getMonthlyMonths()).thenReturn(6);
            when(backupStorage.list(BackupTier.MONTHLY, database)).thenReturn(List.of());

            // act
            retentionManager.applyRetention(BackupTier.MONTHLY, database);

            // assert
            verify(backupStorage, never()).delete(any(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить файл, если getCreationTime выбрасывает IOException для ежемесячного уровня")
        void shouldSkipFileWhenGetCreationTimeFailsForMonthly() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getMonthlyMonths()).thenReturn(3);

            String badFile = "bad.zip";
            String goodFile = "good.zip";
            when(backupStorage.list(BackupTier.MONTHLY, database))
                    .thenReturn(List.of(badFile, goodFile));

            when(backupStorage.getCreationTime(BackupTier.MONTHLY, database, badFile))
                    .thenThrow(new IOException("test IO"));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusMonths(3);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant goodCreated = cutoffInstant.minusSeconds(14400);

            when(backupStorage.getCreationTime(BackupTier.MONTHLY, database, goodFile))
                    .thenReturn(FileTime.from(goodCreated));

            // act
            retentionManager.applyRetention(BackupTier.MONTHLY, database);

            // assert
            verify(backupStorage, never()).delete(eq(BackupTier.MONTHLY), eq(database), eq(badFile));
            verify(backupStorage).delete(eq(BackupTier.MONTHLY), eq(database), eq(goodFile));
        }
    }

    // ==================== applyRetention(SEMI_ANNUAL) Tests ====================

    @Nested
    @DisplayName("applyRetention(SEMI_ANNUAL)")
    class SemiAnnualRetentionTests {

        @Test
        @DisplayName("должен удалять полугодовые резервные копии старше установленного срока хранения")
        void shouldDeleteSemiAnnualBackupsOlderThanRetentionPeriod() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getSemiAnnualYears()).thenReturn(1); // 6 months

            String oldFile = "semi-old.zip";
            String newFile = "semi-new.zip";
            when(backupStorage.list(BackupTier.SEMI_ANNUAL, database))
                    .thenReturn(List.of(oldFile, newFile));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusMonths(1 * 6L);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            Instant oldCreated = cutoffInstant.minusSeconds(3600);
            Instant newCreated = cutoffInstant.plusSeconds(24 * 3600);

            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, oldFile))
                    .thenReturn(FileTime.from(oldCreated));
            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, newFile))
                    .thenReturn(FileTime.from(newCreated));

            // act
            retentionManager.applyRetention(BackupTier.SEMI_ANNUAL, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(oldFile));
            verify(backupStorage, never()).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(newFile));
        }

        @Test
        @DisplayName("должен обрабатывать пустой список полугодовых резервных копий")
        void shouldDoNothingWhenSemiAnnualBackupListIsEmpty() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getSemiAnnualYears()).thenReturn(2); // 12 months
            when(backupStorage.list(BackupTier.SEMI_ANNUAL, database)).thenReturn(List.of());

            // act
            retentionManager.applyRetention(BackupTier.SEMI_ANNUAL, database);

            // assert
            verify(backupStorage, never()).delete(any(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить файл, если getCreationTime выбрасывает IOException для полугодового уровня")
        void shouldSkipFileWhenGetCreationTimeFailsForSemiAnnual() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getSemiAnnualYears()).thenReturn(1); // 6 months

            String badFile = "bad.zip";
            String goodFile = "good.zip";
            when(backupStorage.list(BackupTier.SEMI_ANNUAL, database))
                    .thenReturn(List.of(badFile, goodFile));

            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, badFile))
                    .thenThrow(new IOException("test IO"));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusMonths(6);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant goodCreated = cutoffInstant.minusSeconds(28800);

            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, goodFile))
                    .thenReturn(FileTime.from(goodCreated));

            // act
            retentionManager.applyRetention(BackupTier.SEMI_ANNUAL, database);

            // assert
            verify(backupStorage, never()).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(badFile));
            verify(backupStorage).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(goodFile));
        }

        @Test
        @DisplayName("должен использовать правильное вычисление: годы * 6 месяцев")
        void shouldUseYearsTimesSixMonthsCalculation() throws Exception {
            // Given - 2 years = 12 months retention
            String database = "testdb";
            when(backupRetentionProperties.getSemiAnnualYears()).thenReturn(2); // 12 months

            String oldFile = "semi-old.zip";
            String newFile = "semi-new.zip";
            when(backupStorage.list(BackupTier.SEMI_ANNUAL, database))
                    .thenReturn(List.of(oldFile, newFile));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusMonths(2 * 6L); // 12 months
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            Instant oldCreated = cutoffInstant.minusSeconds(3600);
            Instant newCreated = cutoffInstant.plusSeconds(24 * 3600);

            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, oldFile))
                    .thenReturn(FileTime.from(oldCreated));
            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, newFile))
                    .thenReturn(FileTime.from(newCreated));

            // act
            retentionManager.applyRetention(BackupTier.SEMI_ANNUAL, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(oldFile));
            verify(backupStorage, never()).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(newFile));
        }
    }

    // ==================== applyRetention(ANNUAL) Tests ====================

    @Nested
    @DisplayName("applyRetention(ANNUAL)")
    class AnnualRetentionTests {

        @Test
        @DisplayName("должен удалять ежегодные резервные копии старше установленного срока хранения")
        void shouldDeleteAnnualBackupsOlderThanRetentionPeriod() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getAnnualYears()).thenReturn(5);

            String oldFile = "annual-old.zip";
            String newFile = "annual-new.zip";
            when(backupStorage.list(BackupTier.ANNUAL, database))
                    .thenReturn(List.of(oldFile, newFile));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusYears(5);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            Instant oldCreated = cutoffInstant.minusSeconds(3600);
            Instant newCreated = cutoffInstant.plusSeconds(24 * 3600);

            when(backupStorage.getCreationTime(BackupTier.ANNUAL, database, oldFile))
                    .thenReturn(FileTime.from(oldCreated));
            when(backupStorage.getCreationTime(BackupTier.ANNUAL, database, newFile))
                    .thenReturn(FileTime.from(newCreated));

            // act
            retentionManager.applyRetention(BackupTier.ANNUAL, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.ANNUAL), eq(database), eq(oldFile));
            verify(backupStorage, never()).delete(eq(BackupTier.ANNUAL), eq(database), eq(newFile));
        }

        @Test
        @DisplayName("должен обрабатывать пустой список ежегодных резервных копий")
        void shouldDoNothingWhenAnnualBackupListIsEmpty() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getAnnualYears()).thenReturn(10);
            when(backupStorage.list(BackupTier.ANNUAL, database)).thenReturn(List.of());

            // act
            retentionManager.applyRetention(BackupTier.ANNUAL, database);

            // assert
            verify(backupStorage, never()).delete(any(), any(), any());
        }

        @Test
        @DisplayName("должен пропустить файл, если getCreationTime выбрасывает IOException для ежегодного уровня")
        void shouldSkipFileWhenGetCreationTimeFailsForAnnual() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getAnnualYears()).thenReturn(5);

            String badFile = "bad.zip";
            String goodFile = "good.zip";
            when(backupStorage.list(BackupTier.ANNUAL, database))
                    .thenReturn(List.of(badFile, goodFile));

            when(backupStorage.getCreationTime(BackupTier.ANNUAL, database, badFile))
                    .thenThrow(new IOException("test IO"));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusYears(5);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant goodCreated = cutoffInstant.minusSeconds(57600);

            when(backupStorage.getCreationTime(BackupTier.ANNUAL, database, goodFile))
                    .thenReturn(FileTime.from(goodCreated));

            // act
            retentionManager.applyRetention(BackupTier.ANNUAL, database);

            // assert
            verify(backupStorage, never()).delete(eq(BackupTier.ANNUAL), eq(database), eq(badFile));
            verify(backupStorage).delete(eq(BackupTier.ANNUAL), eq(database), eq(goodFile));
        }

        @Test
        @DisplayName("должен удалять резервную копию, когда она точно совпадает с датой отсечения (до или в момент отсечения)")
        void shouldDeleteBackupExactlyOnCutoffDate() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getAnnualYears()).thenReturn(5);

            String backupOnCutoff = "backup-on-cutoff.zip";
            when(backupStorage.list(BackupTier.ANNUAL, database))
                    .thenReturn(List.of(backupOnCutoff));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusYears(5);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            // Файл создан ровно в момент отсечения (следует удалить, потому что он был до или равен)
            when(backupStorage.getCreationTime(BackupTier.ANNUAL, database, backupOnCutoff))
                    .thenReturn(FileTime.from(cutoffInstant));

            // act
            retentionManager.applyRetention(BackupTier.ANNUAL, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.ANNUAL), eq(database), eq(backupOnCutoff));
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Граничные случаи и обработка ошибок")
    class EdgeCasesTests {

        @Test
        @DisplayName("должен выполнять отладочный журнал и возвращать результат, когда политика хранения не настроена для уровня")
        void shouldSkipWhenNoConfigurationForTier() throws Exception {
            // arrange -Мы не ставим заглушки свойств, поэтому все возвращают значения по умолчанию
            String database = "testdb";
            when(backupRetentionProperties.getDailyDays()).thenReturn(0);
            when(backupStorage.list(eq(BackupTier.DAILY), eq(database))).thenReturn(List.of());

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert - Ничего удалять
            verify(backupStorage, never()).delete(any(), any(), any());
        }

        @Test
        @DisplayName("должен обрабатывать IOException во время удаления и продолжать обработку остальных файлов")
        void shouldContinueAfterDeleteError() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getDailyDays()).thenReturn(3);

            String file1 = "backup1.zip";
            String file2 = "backup2.zip";
            String file3 = "backup3.zip";
            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenReturn(List.of(file1, file2, file3));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusDays(3);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            Instant created = cutoffInstant.minusSeconds(3600);

            //Все файлы достаточно стары, чтобы их можно было удалить
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, file1))
                    .thenReturn(FileTime.from(created));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, file2))
                    .thenReturn(FileTime.from(created));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, file3))
                    .thenReturn(FileTime.from(created));

            //file2 ставит IOException на удаление, другие успешно
            doThrow(new IOException("delete failed"))
                    .when(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file2));
            doAnswer(invocation -> null)
                    .when(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file1));
            doAnswer(invocation -> null)
                    .when(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file3));

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert - File1 и File3 были удалены, file2 провалился, но обработка не остановилась
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file1));
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file2));
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file3));
        }

        @Test
        @DisplayName("должен обрабатывать несколько файлов, где некоторые находятся в пределах срока хранения, а некоторые нет")
        void shouldOnlyDeleteOldBackups() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getDailyDays()).thenReturn(7);

            String veryOld = "very-old.zip";
            String slightlyOld = "slightly-old.zip";
            String recent = "recent.zip";
            String veryRecent = "very-recent.zip";
            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenReturn(List.of(veryOld, slightlyOld, recent, veryRecent));

            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = now.minusDays(7);
            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            // verOld: 20 дней назад -> должен удалить
            Instant veryOldCreated = cutoffInstant.minusSeconds(20 * 24 * 3600);
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, veryOld))
                    .thenReturn(FileTime.from(veryOldCreated));

            //littleOld: 10 дней назад -> должен удалить
            Instant slightlyOldCreated = cutoffInstant.minusSeconds(10 * 24 * 3600);
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, slightlyOld))
                    .thenReturn(FileTime.from(slightlyOldCreated));

            // Недавние: через 5 дней после отключения — > НЕ должен удалять
            Instant recentCreated = cutoffInstant.plusSeconds(5 * 24 * 3600);
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, recent))
                    .thenReturn(FileTime.from(recentCreated));

            // Очень недавно: через 1 день после отсека -> НЕ должен удалять
            Instant veryRecentCreated = cutoffInstant.plusSeconds(24 * 3600);
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, veryRecent))
                    .thenReturn(FileTime.from(veryRecentCreated));

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(veryOld));
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(slightlyOld));
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(recent));
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(veryRecent));
        }

        @Test
        @DisplayName("должен корректно обрабатывать null уровень хранения")
        void shouldHandleNullTier() throws Exception {
            // arrange
            String database = "testdb";

            // act - не бросать исключение
            retentionManager.applyRetention(null, database);

            // assert
            verify(backupStorage, never()).delete(any(), any(), any());
        }
    }
}
