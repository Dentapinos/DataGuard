package com.dentapinos.dataguard.dashboard;

import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.config.BackupProperties;
import com.dentapinos.dataguard.config.BackupRetentionProperties;
import com.dentapinos.dataguard.config.BackupScheduleProperties;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.storage.BackupStorage;
import com.dentapinos.dataguard.storage.DiskSpaceChecker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

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
    private final BackupRetentionProperties retentionProperties;
    private final DiskSpaceChecker diskSpaceChecker;

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
        boolean backupPaused = diskSpaceChecker.isDiskFull();

        return new DashboardResponseDto(databases, scheduleInfo, storageInfo, false, backupPaused);
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

                long maxCount = calculateMaxCountForTier(tier);
                String retentionPeriod = formatRetentionPeriod(tier);

                tiers.add(new TierInfo(tier, fileCount, totalSizeBytes, maxCount, retentionPeriod));
            } catch (IOException e) {
                log.warn("Failed to list files for tier={}, database={}: {}",
                        tier, databaseName, e.getMessage());
                tiers.add(new TierInfo(tier, 0, 0, 0, ""));
            }
        }

        return tiers;
    }

    /**
     * Рассчитывает максимальное количество файлов для тира.
     */
    private long calculateMaxCountForTier(BackupTier tier) {
        return switch (tier) {
            case DAILY -> retentionProperties.getDailyDays() * 2L;
            case WEEKLY -> retentionProperties.getWeeklyWeeks();
            case MONTHLY -> retentionProperties.getMonthlyMonths();
            case SEMI_ANNUAL -> retentionProperties.getSemiAnnualYears();
            case ANNUAL -> retentionProperties.getAnnualYears();
            default -> 0;
        };
    }

    /**
     * Форматирует период хранения в читаемый вид.
     */
    private String formatRetentionPeriod(BackupTier tier) {
        return switch (tier) {
            case DAILY -> retentionProperties.getDailyDays() + " дн.";
            case WEEKLY -> retentionProperties.getWeeklyWeeks() + " нед.";
            case MONTHLY -> retentionProperties.getMonthlyMonths() + " мес.";
            case SEMI_ANNUAL -> retentionProperties.getSemiAnnualYears() + " лет";
            case ANNUAL -> retentionProperties.getAnnualYears() + " лет";
            default -> "";
        };
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
     * Поддерживает / и * в полях.
     */
    private String extractTimeFromCron(String cron) {
        if (cron == null || cron.isEmpty()) {
            return "N/A";
        }
        String[] parts = cron.trim().split("\\s+");
        if (parts.length >= 3) {
            // parts[0] = секунда, parts[1] = минута, parts[2] = час
            String minute = extractCronField(parts[1]);
            String hour = extractCronField(parts[2]);

            // Если оба поля *, показываем "каждую минуту"
            if ("*".equals(hour) && "*".equals(minute)) {
                return "каждую минуту";
            }
            // Если час *, показываем "каждый час"
            if ("*".equals(hour)) {
                return "каждый час:" + minute;
            }
            // Если минута *, показываем "каждый час в :00"
            if ("*".equals(minute)) {
                return hour + ":00";
            }

            return hour + ":" + minute;
        }
        return "N/A";
    }

    /**
     * Извлекает значение из cron-поля, убирая спецсимволы (/ * , -).
     */
    private String extractCronField(String field) {
        if (field == null || field.equals("*")) {
            return "*";
        }
        // Убираем / и диапазоны: "0/5" → "0", "1-5" → "1"
        String clean = field.split("[/\\-]")[0];
        if (clean.isEmpty()) {
            return "*";
        }
        return clean.length() == 1 ? "0" + clean : clean;
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

        long currentBackupBytes = 0;
        long peakBackupAdditionBytes = 0;
        long peakTotalBytes = 0;
        double peakTotalPercent = 0.0;

        if (Files.exists(path) && Files.isDirectory(path)) {
            try {
                // 1. Общий размер диска
                FileStore fileStore = Files.getFileStore(path);
                maxSpaceBytes = fileStore.getTotalSpace();

                // 2. Сколько занято на диске (всеми файлами)
                usedSpaceBytes = maxSpaceBytes - fileStore.getUsableSpace();

                if (maxSpaceBytes > 0) {
                    usagePercent = (usedSpaceBytes * 100.0) / maxSpaceBytes;
                }

                // 3. Текущий размер бэкапов (сумма всех DAILY файлов)
                currentBackupBytes = calculateCurrentBackupSize();

                // 4. Расчёт пикового добавления бэкапов
                peakBackupAdditionBytes = calculatePeakBackupAddition(currentBackupBytes);

                // 5. Итого в пике: занято сейчас + будущие бэкапы
                peakTotalBytes = usedSpaceBytes + peakBackupAdditionBytes;

                // 6. Процент от общего диска
                if (maxSpaceBytes > 0) {
                    peakTotalPercent = (peakTotalBytes * 100.0) / maxSpaceBytes;
                }

            } catch (IOException e) {
                log.warn("Failed to calculate storage info for path={}: {}", basePath, e.getMessage());
            }
        }

        // Определяем статус по пиковому заполнению
        StorageStatus status;
        if (peakTotalPercent >= 90) {
            status = StorageStatus.CRITICAL;
        } else if (peakTotalPercent >= 80) {
            status = StorageStatus.WARNING;
        } else {
            status = StorageStatus.OK;
        }

        return new StorageInfo(
                basePath,
                usedSpaceBytes,
                maxSpaceBytes,
                Math.round(usagePercent * 100.0) / 100.0,
                currentBackupBytes,
                peakBackupAdditionBytes,
                peakTotalBytes,
                Math.round(peakTotalPercent * 100.0) / 100.0,
                status
        );
    }

    /**
     * Рассчитывает текущий размер всех бэкапов (сумма файлов из всех тиров).
     */
    private long calculateCurrentBackupSize() {
        long totalSize = 0;

        for (BackupDatabasesProperties.DatabaseConfig dbConfig : databaseProperties.getDatabases()) {
            String databaseName = dbConfig.getDatabaseName();
            
            // Суммируем все тиры: DAILY, WEEKLY, MONTHLY, SEMI_ANNUAL, ANNUAL
            for (BackupTier tier : BackupTier.values()) {
                try {
                    List<String> files = backupStorage.list(tier, databaseName);
                    for (String fileName : files) {
                        try {
                            totalSize += backupStorage.getFileSize(tier, databaseName, fileName);
                        } catch (IOException e) {
                            log.debug("Failed to get file size for tier={} file={}: {}", tier, fileName, e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    log.debug("Failed to list files for tier={} db={}: {}", tier, databaseName, e.getMessage());
                }
            }
        }

        return totalSize;
    }

    /**
     * Рассчитывает пиковый размер бэкапов.
     * <p>
     * Логика:
     * 1. Для каждой базы берём размер самого свежего бэкапа (DAILY)
     * 2. Считаем суммарное количество слотов по всем тирам (maxCount для каждого тира)
     * 3. peakForDb = latestBackupSize × totalSlots
     * 4. Суммируем peakForDb по всем базам
     * 5. peakBackupAddition = totalPeakBackupSize - currentBackupBytes
     */
    private long calculatePeakBackupAddition(long currentBackupBytes) {
        long totalPeakBackupBytes = 0;

        for (BackupDatabasesProperties.DatabaseConfig dbConfig : databaseProperties.getDatabases()) {
            String databaseName = dbConfig.getDatabaseName();

            // 1. Размер самого свежего бэкапа (DAILY)
            long latestBackupSize = 0;
            try {
                List<String> dailyFiles = backupStorage.list(BackupTier.DAILY, databaseName);
                if (!dailyFiles.isEmpty()) {
                    String latestFile = dailyFiles.stream()
                            .max(Comparator.comparing(fileName -> {
                                try {
                                    return backupStorage.getCreationTime(BackupTier.DAILY, databaseName, fileName);
                                } catch (IOException e) {
                                    return java.nio.file.attribute.FileTime.fromMillis(0);
                                }
                            }))
                            .orElse(null);

                    if (latestFile != null) {
                        latestBackupSize = backupStorage.getFileSize(BackupTier.DAILY, databaseName, latestFile);
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to get latest backup size for {}: {}", databaseName, e.getMessage());
            }

            if (latestBackupSize == 0) {
                continue;
            }

            // 2. Считаем суммарное количество слотов по всем тирам
            long totalSlots = 0;
            for (BackupTier tier : BackupTier.values()) {
                totalSlots += calculateMaxCountForTier(tier);
            }

            // 3. peakForDb = latestBackupSize × totalSlots
            long peakForDb = latestBackupSize * totalSlots;
            totalPeakBackupBytes += peakForDb;
        }

        // 4. Добавляем 10% погрешности
        totalPeakBackupBytes = (long) (totalPeakBackupBytes * 1.1);

        // 5. peakBackupAddition = пик - текущее (но не меньше 0)
        return Math.max(0, totalPeakBackupBytes - currentBackupBytes);
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
                        safeSizeBytes,
                        tier.maxCount(),
                        tier.retentionPeriod()
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
        boolean backupPaused = diskSpaceChecker.isDiskFull();

        return new DashboardResponseDto(databases, scheduleInfo, storageInfo, true, backupPaused);
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
                FileStore fileStore = Files.getFileStore(path);
                maxSpaceBytes = fileStore.getTotalSpace();
                usedSpaceBytes = maxSpaceBytes - fileStore.getUsableSpace();

                if (maxSpaceBytes > 0) {
                    usagePercent = (usedSpaceBytes * 100.0) / maxSpaceBytes;
                }
            } catch (IOException e) {
                log.warn("Failed to calculate storage info for path={}: {}", basePath, e.getMessage());
            }
        }

        // Для safe mode прогноз не показываем (данные обезличены)
        return new StorageInfo(
                "****", // Скрываем реальный путь
                safeSizeBytes(usedSpaceBytes),
                maxSpaceBytes > 0 ? safeSizeBytes(maxSpaceBytes) : 0,
                Math.round(usagePercent * 100.0) / 100.0,
                safeSizeBytes(usedSpaceBytes), // peak = current в safe mode
                0,
                safeSizeBytes(usedSpaceBytes),
                usagePercent,
                StorageStatus.OK
        );
    }
}
