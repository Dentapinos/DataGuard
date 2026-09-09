package com.dentapinos.dataguard.storage;


import com.dentapinos.dataguard.config.BackupRetentionProperties;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.exception.BackupStorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * Сервис применения политики хранения (retention) для резервных копий.
 * <p>
 * Для заданного уровня {@link BackupTier} удаляет бэкапы,
 * старше настроенного срока хранения из {@link BackupRetentionProperties}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupRetentionManager {

    private final BackupRetentionProperties backupRetentionProperties;
    private final BackupStorage backupStorage;

    /**
     * Применяет политику хранения к указанному уровню:
     * оставляет только N newest бэкапов (N = лимит из настроек),
     * удаляет самые старые, выходящие за лимит.
     *
     * @param tier уровень хранения, к которому нужно применить retention
     */
    public void applyRetention(BackupTier tier, String database) {
        if (tier == null) {
            log.debug("[BACKUP_RETENTION] уровень=null, удаление не выполняется");
            return;
        }
        try {
            int maxCount = calculateMaxCountForTier(tier);
            List<String> allFiles = backupStorage.list(tier, database);

            if (allFiles.size() <= maxCount) {
                log.debug("[BACKUP_RETENTION] уровень={} файлов {} <= лимит {}, удаление не требуется", tier, allFiles.size(), maxCount);
                return;
            }

            // Сортируем: новые первыми
            List<String> sortedFiles = allFiles.stream()
                    .sorted(Comparator.comparing((String fileName) -> {
                        try {
                            return backupStorage
                                    .getCreationTime(tier, database, fileName)
                                    .toInstant();
                        } catch (IOException e) {
                            log.warn("[BACKUP_RETENTION] не удалось получить время создания файла {} (tier={}): {}",
                                    fileName, tier, e.getMessage(), e);
                            return Instant.EPOCH;
                        }
                    }).reversed())
                    .toList();

            // Удаляем самые старые (выходящие за лимит)
            int filesToDelete = sortedFiles.size() - maxCount;
            log.info("[BACKUP_RETENTION] уровень={} всего={} лимит={} будет удалено={}", tier, sortedFiles.size(), maxCount, filesToDelete);

            for (int i = maxCount; i < sortedFiles.size(); i++) {
                String fileName = sortedFiles.get(i);
                try {
                    backupStorage.delete(tier, database, fileName);
                    log.info("[BACKUP_RETENTION] удалена старая резервная копия: уровень={} файл={}", tier, fileName);
                } catch (IOException e) {
                    log.error("[BACKUP_RETENTION] не удалось удалить резервную копию: уровень={} файл={}",
                            tier, fileName, e);
                }
            }

        } catch (IOException e) {
            log.error("[BACKUP_RETENTION] ошибка при применении политики хранения для уровня={}", tier, e);
            throw new BackupStorageException("Failed to apply retention for tier: " + tier, e);
        }
    }

    /**
     * Рассчитывает максимальное количество бэкапов для заданного уровня,
     * используя настройки из {@link BackupRetentionProperties}.
     *
     * @param tier уровень хранения
     * @return максимальное количество файлов, которые должны храниться
     */
    private int calculateMaxCountForTier(BackupTier tier) {
        return switch (tier) {
            case DAILY      -> backupRetentionProperties.getDailyDays();
            case WEEKLY     -> backupRetentionProperties.getWeeklyWeeks();
            case MONTHLY    -> backupRetentionProperties.getMonthlyMonths();
            case SEMI_ANNUAL -> backupRetentionProperties.getSemiAnnualYears() * 2;
            case ANNUAL     -> backupRetentionProperties.getAnnualYears();
            default         -> {
                log.warn("[BACKUP_RETENTION] неизвестный уровень хранения: {}", tier);
                yield 0;
            }
        };
    }
}