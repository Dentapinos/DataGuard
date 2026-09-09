package com.dentapinos.dataguard.service;

import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.config.BackupProperties;
import com.dentapinos.dataguard.config.BackupScheduleProperties;
import com.dentapinos.dataguard.entity.dashboard.DatabaseDashboardDto;
import com.dentapinos.dataguard.entity.dashboard.DashboardResponseDto;
import com.dentapinos.dataguard.entity.dashboard.ScheduleInfo;
import com.dentapinos.dataguard.entity.dashboard.StorageInfo;
import com.dentapinos.dataguard.entity.dashboard.TierInfo;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.storage.BackupStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Сервис для сбора данных dashboard.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final BackupStorage backupStorage;
    private final BackupDatabasesProperties databaseProperties;
    private final BackupScheduleProperties scheduleProperties;
    private final BackupProperties backupProperties;

    /**
     * Получает полные данные для dashboard.
     */
    public DashboardResponseDto getDashboardData() {
        List<DatabaseDashboardDto> databases = new ArrayList<>();

        for (BackupDatabasesProperties.DatabaseConfig dbConfig : databaseProperties.getDatabases()) {
            String databaseName = dbConfig.getDatabaseName();
            String displayName = dbConfig.getDisplayName();

            List<TierInfo> tiers = getTierInfo(databaseName);
            long totalFileCount = tiers.stream().mapToLong(TierInfo::fileCount).sum();
            long totalSizeBytes = tiers.stream().mapToLong(TierInfo::totalSizeBytes).sum();

            databases.add(new DatabaseDashboardDto(
                    databaseName,
                    displayName,
                    tiers,
                    totalFileCount,
                    totalSizeBytes
            ));
        }

        ScheduleInfo scheduleInfo = getScheduleInfo();
        StorageInfo storageInfo = getStorageInfo();

        return new DashboardResponseDto(databases, scheduleInfo, storageInfo, false);
    }

    /**
     * Получает информацию по tier для базы данных.
     */
    private List<TierInfo> getTierInfo(String databaseName) {
        List<TierInfo> tiers = new ArrayList<>();

        for (BackupTier tier : BackupTier.values()) {
            try {
                List<String> files = backupStorage.list(tier, databaseName);
                long fileCount = files.size();
                long totalSizeBytes = 0;

                for (String fileName : files) {
                    try {
                        long fileSize = backupStorage.getFileSize(tier, databaseName, fileName);
                        totalSizeBytes += fileSize;
                    } catch (IOException e) {
                        log.warn("Failed to get file size for tier={}, database={}, file={}: {}",
                                tier, databaseName, fileName, e.getMessage());
                    }
                }

                tiers.add(new TierInfo(tier, fileCount, totalSizeBytes));
            } catch (IOException e) {
                log.warn("Failed to list files for tier={}, database={}: {}",
                        tier, databaseName, e.getMessage());
                tiers.add(new TierInfo(tier, 0, 0));
            }
        }

        return tiers;
    }

    /**
     * Получает информацию о расписании.
     */
    private ScheduleInfo getScheduleInfo() {
        String backupTime = extractTimeFromCron(scheduleProperties.getDailyBackupCron());
        String promotionTime = extractTimeFromCron(scheduleProperties.getPromoteDailyToWeeklyCron());
        String retentionTime = extractTimeFromCron(scheduleProperties.getRetentionCron());

        return new ScheduleInfo(backupTime, promotionTime, retentionTime);
    }

    /**
     * Извлекает время из cron выражения (Spring формат: секунда минута час день месяц день_недели).
     * Пример: "0 30 3 * * *" → "03:30" (каждый день в 03:30).
     */
    private String extractTimeFromCron(String cron) {
        if (cron == null || cron.isEmpty()) {
            return "N/A";
        }
        String[] parts = cron.trim().split("\\s+");
        if (parts.length >= 3) {
            // parts[0] = секунда, parts[1] = минута, parts[2] = час
            String hour = parts[2].length() == 1 ? "0" + parts[2] : parts[2];
            String minute = parts[1].length() == 1 ? "0" + parts[1] : parts[1];
            return hour + ":" + minute;
        }
        return "N/A";
    }

    /**
     * Получает информацию о хранилище.
     */
    private StorageInfo getStorageInfo() {
        String basePath = backupProperties.getFileSystem().getBasePath();
        Path path = Path.of(basePath);

        long usedSpaceBytes = 0;
        long maxSpaceBytes = 0;
        double usagePercent = 0.0;

        if (Files.exists(path) && Files.isDirectory(path)) {
            try {
                // Подсчёт использованного пространства
                usedSpaceBytes = calculateDirectorySize(path);

                // Получение информации о файловом хранилище
                FileStore fileStore = Files.getFileStore(path);
                maxSpaceBytes = fileStore.getTotalSpace();

                if (maxSpaceBytes > 0) {
                    usagePercent = (usedSpaceBytes * 100.0) / maxSpaceBytes;
                }
            } catch (IOException e) {
                log.warn("Failed to calculate storage info for path={}: {}", basePath, e.getMessage());
            }
        }

        return new StorageInfo(
                basePath,
                usedSpaceBytes,
                maxSpaceBytes,
                Math.round(usagePercent * 100.0) / 100.0
        );
    }

    /**
     * Рекурсивно подсчитывает размер директории.
     */
    private long calculateDirectorySize(Path directory) throws IOException {
        if (!Files.exists(directory) || !Files.isDirectory(directory)) {
            return 0;
        }

        try (var stream = Files.walk(directory)) {
            return stream.filter(Files::isRegularFile)
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        }
    }

    /**
     * Получает обезличенные данные для safe mode.
     */
    public DashboardResponseDto getSafeDashboardData() {
        List<DatabaseDashboardDto> databases = new ArrayList<>();

        for (int i = 0; i < databaseProperties.getDatabases().size(); i++) {
            BackupDatabasesProperties.DatabaseConfig dbConfig = databaseProperties.getDatabases().get(i);
            String databaseName = dbConfig.getDatabaseName();
            String safeName = "БД #" + (i + 1);

            List<TierInfo> tiers = getTierInfo(databaseName);
            long totalFileCount = tiers.stream().mapToLong(TierInfo::fileCount).sum();
            long totalSizeBytes = tiers.stream().mapToLong(TierInfo::totalSizeBytes).sum();

            // Обезличиваем данные
            long safeFileCount = safeFileCount(totalFileCount);
            long safeSizeBytes = safeSizeBytes(totalSizeBytes);

            List<TierInfo> safeTiers = new ArrayList<>();
            for (TierInfo tier : tiers) {
                safeTiers.add(new TierInfo(
                        tier.tier(),
                        safeFileCount,
                        safeSizeBytes
                ));
            }

            databases.add(new DatabaseDashboardDto(
                    safeName,
                    safeName,
                    safeTiers,
                    safeFileCount,
                    safeSizeBytes
            ));
        }

        ScheduleInfo scheduleInfo = getSafeScheduleInfo();
        StorageInfo storageInfo = getSafeStorageInfo();

        return new DashboardResponseDto(databases, scheduleInfo, storageInfo, true);
    }

    /**
     * Безопасное количество файлов (диапазон).
     */
    private long safeFileCount(long count) {
        if (count == 0) return 0;
        if (count <= 10) return 10;
        if (count <= 50) return 50;
        if (count <= 100) return 100;
        return count;
    }

    /**
     * Безопасный размер (округление до диапазона).
     */
    private long safeSizeBytes(long bytes) {
        if (bytes == 0) return 0;
        // Округляем до ближайшего "красивого" значения с погрешностью ~20%
        double magnitude = Math.pow(10, Math.floor(Math.log10(bytes)));
        double normalized = bytes / magnitude;
        double safeNormalized;
        
        if (normalized < 1.5) safeNormalized = 1;
        else if (normalized < 2.5) safeNormalized = 2;
        else if (normalized < 5) safeNormalized = 5;
        else safeNormalized = 10;
        
        return Math.round(safeNormalized * magnitude);
    }

    /**
     * Безопасная информация о расписании.
     */
    private ScheduleInfo getSafeScheduleInfo() {
        String backupTime = extractTimeFromCron(scheduleProperties.getDailyBackupCron());
        String promotionTime = extractTimeFromCron(scheduleProperties.getPromoteDailyToWeeklyCron());
        String retentionTime = extractTimeFromCron(scheduleProperties.getRetentionCron());

        return new ScheduleInfo(backupTime, promotionTime, retentionTime);
    }

    /**
     * Безопасная информация о хранилище.
     */
    private StorageInfo getSafeStorageInfo() {
        String basePath = backupProperties.getFileSystem().getBasePath();
        Path path = Path.of(basePath);

        long usedSpaceBytes = 0;
        long maxSpaceBytes = 0;
        double usagePercent = 0.0;

        if (Files.exists(path) && Files.isDirectory(path)) {
            try {
                usedSpaceBytes = calculateDirectorySize(path);
                FileStore fileStore = Files.getFileStore(path);
                maxSpaceBytes = fileStore.getTotalSpace();

                if (maxSpaceBytes > 0) {
                    usagePercent = (usedSpaceBytes * 100.0) / maxSpaceBytes;
                }
            } catch (IOException e) {
                log.warn("Failed to calculate storage info for path={}: {}", basePath, e.getMessage());
            }
        }

        return new StorageInfo(
                "****", // Скрываем реальный путь
                safeSizeBytes(usedSpaceBytes),
                maxSpaceBytes > 0 ? safeSizeBytes(maxSpaceBytes) : 0,
                Math.round(usagePercent * 100.0) / 100.0
        );
    }
}