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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Юнит-тесты для BackupRetentionManager.
 * Проверяет count-based политику хранения: оставляет N newest файлов,
 * удаляет самые старые, выходящие за лимит.
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

    // Mocks создаются MockitoExtension автоматически, reset не нужен
    // каждый тест настраивает свои stubs

    // ==================== applyRetention(DAILY) Tests ====================

    @Nested
    @DisplayName("applyRetention(DAILY) - count-based")
    class DailyRetentionTests {

        @Test
        @DisplayName("должен удалять самые старые файлы, выходящие за лимит")
        void shouldDeleteOldestFilesBeyondLimit() throws Exception {
            // arrange
            String database = "testdb";
            int maxCount = 3;
            when(backupRetentionProperties.getDailyDays()).thenReturn(maxCount);

            String oldest = "backup-oldest.zip";
            String old = "backup-old.zip";
            String medium = "backup-medium.zip";
            String recent = "backup-recent.zip";
            String newest = "backup-newest.zip";
            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenReturn(List.of(oldest, old, medium, recent, newest));

            // Сортировка по времени создания (новые первыми при reversed)
            Instant now = Instant.now();
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, oldest))
                    .thenReturn(FileTime.from(now.minusSeconds(4 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, old))
                    .thenReturn(FileTime.from(now.minusSeconds(3 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, medium))
                    .thenReturn(FileTime.from(now.minusSeconds(2 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, recent))
                    .thenReturn(FileTime.from(now.minusSeconds(1 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, newest))
                    .thenReturn(FileTime.from(now));

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert: должны удалиться 2 самых старых (oldest, old), осталось 3 newest
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(oldest));
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(old));
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(medium));
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(recent));
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(newest));
        }

        @Test
        @DisplayName("должен ничего не делать, если файлов меньше или равно лимиту")
        void shouldDoNothingWhenFilesWithinLimit() throws Exception {
            // arrange
            String database = "testdb";
            int maxCount = 3;
            when(backupRetentionProperties.getDailyDays()).thenReturn(maxCount);

            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenReturn(List.of("backup1.zip", "backup2.zip"));

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert
            verify(backupStorage, never()).delete(any(), any(), any());
        }

        @Test
        @DisplayName("должен ничего не делать, если список пустой")
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
        @DisplayName("должен пропускать файл, если getCreationTime выбрасывает IOException")
        void shouldSkipFileWhenGetCreationTimeFails() throws Exception {
            // arrange
            String database = "testdb";
            int maxCount = 2;
            when(backupRetentionProperties.getDailyDays()).thenReturn(maxCount);

            String badFile = "bad.zip";
            String file1 = "backup1.zip";
            String file2 = "backup2.zip";
            String file3 = "backup3.zip";
            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenReturn(List.of(badFile, file1, file2, file3));

            // badFile бросает IOException — будет иметь EPOCH время (самый старый)
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, badFile))
                    .thenThrow(new IOException("test IO"));

            Instant now = Instant.now();
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, file1))
                    .thenReturn(FileTime.from(now.minusSeconds(3 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, file2))
                    .thenReturn(FileTime.from(now.minusSeconds(2 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, file3))
                    .thenReturn(FileTime.from(now.minusSeconds(1 * 3600)));

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert: badFile (EPOCH) удалится как самый старый, file1 тоже удалится
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(badFile));
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file1));
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(file2));
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(file3));
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
    @DisplayName("applyRetention(WEEKLY) - count-based")
    class WeeklyRetentionTests {

        @Test
        @DisplayName("должен удалять самые старые файлы, выходящие за лимит")
        void shouldDeleteOldestFilesBeyondLimit() throws Exception {
            // arrange
            String database = "testdb";
            int maxCount = 2;
            when(backupRetentionProperties.getWeeklyWeeks()).thenReturn(maxCount);

            String oldFile = "weekly-old.zip";
            String mediumFile = "weekly-medium.zip";
            String newFile = "weekly-new.zip";
            when(backupStorage.list(BackupTier.WEEKLY, database))
                    .thenReturn(List.of(oldFile, mediumFile, newFile));

            Instant now = Instant.now();
            when(backupStorage.getCreationTime(BackupTier.WEEKLY, database, oldFile))
                    .thenReturn(FileTime.from(now.minusSeconds(3 * 24 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.WEEKLY, database, mediumFile))
                    .thenReturn(FileTime.from(now.minusSeconds(2 * 24 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.WEEKLY, database, newFile))
                    .thenReturn(FileTime.from(now));

            // act
            retentionManager.applyRetention(BackupTier.WEEKLY, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.WEEKLY), eq(database), eq(oldFile));
            verify(backupStorage, never()).delete(eq(BackupTier.WEEKLY), eq(database), eq(mediumFile));
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
    }

    // ==================== applyRetention(MONTHLY) Tests ====================

    @Nested
    @DisplayName("applyRetention(MONTHLY) - count-based")
    class MonthlyRetentionTests {

        @Test
        @DisplayName("должен удалять самые старые файлы, выходящие за лимит")
        void shouldDeleteOldestFilesBeyondLimit() throws Exception {
            // arrange
            String database = "testdb";
            int maxCount = 2;
            when(backupRetentionProperties.getMonthlyMonths()).thenReturn(maxCount);

            String oldFile = "monthly-old.zip";
            String mediumFile = "monthly-medium.zip";
            String newFile = "monthly-new.zip";
            when(backupStorage.list(BackupTier.MONTHLY, database))
                    .thenReturn(List.of(oldFile, mediumFile, newFile));

            Instant now = Instant.now();
            when(backupStorage.getCreationTime(BackupTier.MONTHLY, database, oldFile))
                    .thenReturn(FileTime.from(now.minusSeconds(3 * 24 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.MONTHLY, database, mediumFile))
                    .thenReturn(FileTime.from(now.minusSeconds(2 * 24 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.MONTHLY, database, newFile))
                    .thenReturn(FileTime.from(now));

            // act
            retentionManager.applyRetention(BackupTier.MONTHLY, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.MONTHLY), eq(database), eq(oldFile));
            verify(backupStorage, never()).delete(eq(BackupTier.MONTHLY), eq(database), eq(mediumFile));
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
    }

    // ==================== applyRetention(SEMI_ANNUAL) Tests ====================

    @Nested
    @DisplayName("applyRetention(SEMI_ANNUAL) - count-based")
    class SemiAnnualRetentionTests {

        @Test
        @DisplayName("должен использовать semiAnnualYears * 2 как лимит")
        void shouldUseYearsTimesTwoCalculation() throws Exception {
            // arrange
            String database = "testdb";
            // 2 года = 4 полугодия
            when(backupRetentionProperties.getSemiAnnualYears()).thenReturn(2);

            String old1 = "semi-old1.zip";
            String old2 = "semi-old2.zip";
            String new1 = "semi-new1.zip";
            String new2 = "semi-new2.zip";
            String new3 = "semi-new3.zip";
            when(backupStorage.list(BackupTier.SEMI_ANNUAL, database))
                    .thenReturn(List.of(old1, old2, new1, new2, new3));

            Instant now = Instant.now();
            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, old1))
                    .thenReturn(FileTime.from(now.minusSeconds(5 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, old2))
                    .thenReturn(FileTime.from(now.minusSeconds(4 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, new1))
                    .thenReturn(FileTime.from(now.minusSeconds(3 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, new2))
                    .thenReturn(FileTime.from(now.minusSeconds(2 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.SEMI_ANNUAL, database, new3))
                    .thenReturn(FileTime.from(now));

            // act: maxCount = 2 * 2 = 4, удалить 1 старый
            retentionManager.applyRetention(BackupTier.SEMI_ANNUAL, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(old1));
            verify(backupStorage, never()).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(old2));
            verify(backupStorage, never()).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(new1));
            verify(backupStorage, never()).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(new2));
            verify(backupStorage, never()).delete(eq(BackupTier.SEMI_ANNUAL), eq(database), eq(new3));
        }

        @Test
        @DisplayName("должен обрабатывать пустой список полугодовых резервных копий")
        void shouldDoNothingWhenSemiAnnualBackupListIsEmpty() throws Exception {
            // arrange
            String database = "testdb";
            when(backupRetentionProperties.getSemiAnnualYears()).thenReturn(2);
            when(backupStorage.list(BackupTier.SEMI_ANNUAL, database)).thenReturn(List.of());

            // act
            retentionManager.applyRetention(BackupTier.SEMI_ANNUAL, database);

            // assert
            verify(backupStorage, never()).delete(any(), any(), any());
        }
    }

    // ==================== applyRetention(ANNUAL) Tests ====================

    @Nested
    @DisplayName("applyRetention(ANNUAL) - count-based")
    class AnnualRetentionTests {

        @Test
        @DisplayName("должен удалять самые старые файлы, выходящие за лимит")
        void shouldDeleteOldestFilesBeyondLimit() throws Exception {
            // arrange
            String database = "testdb";
            int maxCount = 2;
            when(backupRetentionProperties.getAnnualYears()).thenReturn(maxCount);

            String oldFile = "annual-old.zip";
            String mediumFile = "annual-medium.zip";
            String newFile = "annual-new.zip";
            when(backupStorage.list(BackupTier.ANNUAL, database))
                    .thenReturn(List.of(oldFile, mediumFile, newFile));

            Instant now = Instant.now();
            when(backupStorage.getCreationTime(BackupTier.ANNUAL, database, oldFile))
                    .thenReturn(FileTime.from(now.minusSeconds(3 * 24 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.ANNUAL, database, mediumFile))
                    .thenReturn(FileTime.from(now.minusSeconds(2 * 24 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.ANNUAL, database, newFile))
                    .thenReturn(FileTime.from(now));

            // act
            retentionManager.applyRetention(BackupTier.ANNUAL, database);

            // assert
            verify(backupStorage).delete(eq(BackupTier.ANNUAL), eq(database), eq(oldFile));
            verify(backupStorage, never()).delete(eq(BackupTier.ANNUAL), eq(database), eq(mediumFile));
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
    }

    // ==================== Edge Cases ====================

    @Nested
    @DisplayName("Граничные случаи и обработка ошибок")
    class EdgeCasesTests {

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

        @Test
        @DisplayName("должен обрабатывать IOException во время удаления и продолжать обработку остальных файлов")
        void shouldContinueAfterDeleteError() throws Exception {
            // arrange
            String database = "testdb";
            int maxCount = 1;
            when(backupRetentionProperties.getDailyDays()).thenReturn(maxCount);

            String file1 = "backup1.zip";
            String file2 = "backup2.zip";
            String file3 = "backup3.zip";
            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenReturn(List.of(file1, file2, file3));

            Instant now = Instant.now();
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, file1))
                    .thenReturn(FileTime.from(now.minusSeconds(3 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, file2))
                    .thenReturn(FileTime.from(now.minusSeconds(2 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, file3))
                    .thenReturn(FileTime.from(now.minusSeconds(1 * 3600)));

            // file2 бросает IOException при удалении
            doThrow(new IOException("delete failed"))
                    .when(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file2));
            doAnswer(invocation -> null)
                    .when(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file1));

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert: file1 удалён успешно, file2 провалился, но обработка продолжилась
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file1));
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq(file2));
            verify(backupStorage, never()).delete(eq(BackupTier.DAILY), eq(database), eq(file3));
        }

        @Test
        @DisplayName("должен удалить все файлы, если их больше лимита")
        void shouldDeleteAllWhenAllExceedLimit() throws Exception {
            // arrange
            String database = "testdb";
            int maxCount = 0;
            when(backupRetentionProperties.getDailyDays()).thenReturn(maxCount);

            when(backupStorage.list(BackupTier.DAILY, database))
                    .thenReturn(List.of("backup1.zip", "backup2.zip", "backup3.zip"));

            Instant now = Instant.now();
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, "backup1.zip"))
                    .thenReturn(FileTime.from(now.minusSeconds(3 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, "backup2.zip"))
                    .thenReturn(FileTime.from(now.minusSeconds(2 * 3600)));
            when(backupStorage.getCreationTime(BackupTier.DAILY, database, "backup3.zip"))
                    .thenReturn(FileTime.from(now));

            // act
            retentionManager.applyRetention(BackupTier.DAILY, database);

            // assert: все 3 файла удалены
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq("backup1.zip"));
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq("backup2.zip"));
            verify(backupStorage).delete(eq(BackupTier.DAILY), eq(database), eq("backup3.zip"));
        }
    }
}
