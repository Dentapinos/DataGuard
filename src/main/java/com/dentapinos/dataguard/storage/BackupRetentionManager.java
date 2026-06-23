package com.dentapinos.dataguard.storage;


import com.dentapinos.dataguard.config.BackupRetentionProperties;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.exception.BackupStorageException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
     * удаляет все бэкапы, созданные ранее расчётной граничной даты.
     *
     * @param tier уровень хранения, к которому нужно применить retention
     */
    public void applyRetention(BackupTier tier, String database) {
        if (tier == null) {
            log.debug("[BACKUP_RETENTION] уровень=null, удаление не выполняется");
            return;
        }
        try {
            LocalDate now = LocalDate.now(ZoneOffset.UTC);
            LocalDate cutoffDate = calculateCutoffDateForTier(tier, now);
            if (cutoffDate == null) {
                log.debug("[BACKUP_RETENTION] для уровня {} полика хранения не задана, удаление не выполняется", tier);
                return;
            }

            Instant cutoffInstant = cutoffDate.atStartOfDay().toInstant(ZoneOffset.UTC);

            log.debug("[BACKUP_RETENTION] уровень={} граничная дата удаления={}", tier, cutoffDate);

            List<String> filesToDelete = backupStorage.list(tier, database).stream()
                    .filter(fileName -> {
                        try {
                            Instant created = backupStorage
                                    .getCreationTime(tier, database, fileName)
                                    .toInstant();
                            return !created.isAfter(cutoffInstant);
                        } catch (IOException e) {
                            log.warn("[BACKUP_RETENTION] не удалось обработать резервную копию: уровень={} файл={}",
                                    tier, fileName, e);
                            return false;
                        }
                    })
                    .toList();

            log.info("[BACKUP_RETENTION] найдено {} файлов для удаления на уровне {}", filesToDelete.size(), tier);

            for (String fileName : filesToDelete) {
                try {
                    backupStorage.delete(tier, database, fileName);
                    log.info("[BACKUP_RETENTION] удалена старая резервная копия: уровень={} файл={}", tier, fileName);
                } catch (IOException e) {
                    log.warn("[BACKUP_RETENTION] не удалось удалить резервную копию: уровень={} файл={}",
                            tier, fileName, e);
                }
            }

        } catch (IOException e) {
            log.error("[BACKUP_RETENTION] ошибка при применении политики хранения для уровня={}", tier, e);
            throw new BackupStorageException("Failed to apply retention for tier: " + tier, e);
        }
    }

    /**
     * Рассчитывает граничную дату для удаления бэкапов для заданного уровня,
     * используя настройки из {@link BackupRetentionProperties}.
     *
     * @param tier уровень хранения
     * @param now  «текущая» дата (для удобства тестирования передаётся параметром)
     * @return дата, раньше которой бэкапы считаются устаревшими
     */
    private LocalDate calculateCutoffDateForTier(BackupTier tier, LocalDate now) {
        return switch (tier) {
            case DAILY      -> now.minusDays(backupRetentionProperties.getDailyDays());
            case WEEKLY     -> now.minusWeeks(backupRetentionProperties.getWeeklyWeeks());
            case MONTHLY    -> now.minusMonths(backupRetentionProperties.getMonthlyMonths());
            case SEMI_ANNUAL ->
                    now.minusMonths(backupRetentionProperties.getSemiAnnualYears() * 6L);
            case ANNUAL     -> now.minusYears(backupRetentionProperties.getAnnualYears());
            default         -> {
                log.warn("[BACKUP_RETENTION] неизвестный уровень хранения: {}", tier);
                yield null;
            }
        };
    }
}