package com.dentapinos.dataguard.storage;


import com.dentapinos.dataguard.enums.BackupStatus;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.exception.BackupStorageException;
import com.dentapinos.dataguard.report.BackupReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.Period;
import java.util.Comparator;
import java.util.Optional;

/**
 * Сервис «продвижения» (promotion) бэкапа между уровнями хранения.
 * <p>
 * Из заданного {@code fromTier} выбирает самый новый успешный бэкап,
 * созданный за указанный {@link Period}, и копирует его в {@code toTier}.
 * <p>
 * КОНТРАКТ IDEMPOTENCY:
 * Операция promotion является идемпотентной:
 * - если файл с таким именем уже существует в целевом tier, он будет перезаписан
 * - это позволяет безопасно повторять операции promotion
 * - FileSystemBackupStorage.copy использует ATOMIC_MOVE и REPLACE_EXISTING
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackupTierPromoter {

    private final BackupStorage backupStorage;
    private final BackupFileReader backupFileReader;


    /**
     * Находит в {@code fromTier} самый новый успешный бэкап за указанный период и копирует его в {@code toTier}.
     * Если подходящих бэкапов нет, просто пишет предупреждение в лог.
     *
     * @param database имя базы данных
     * @param fromTier исходный уровень хранения (откуда брать бэкап)
     * @param toTier   целевой уровень хранения (куда копировать)
     * @param period   максимальная «давность» бэкапа (например, {@code Period.ofDays(1)})
     */
    public void promote(String database, BackupTier fromTier, BackupTier toTier, Period period) {
        try {
            Instant now = Instant.now();
            Instant fromInstant = now.minus(period);

            log.info("[BACKUP_PROMOTION] Запуск promotion: from={} to={} period={}", fromTier, toTier, period);

            var files = backupStorage.list(fromTier, database);

            Optional<String> candidate = files.stream()
                    // 1. только успешные бэкапы
                    .filter(fileName -> isSuccessful(fileName, fromTier, database))
                    // 2. только в окне [fromInstant, now]
                    .filter(fileName -> {
                        try {
                            Instant created = backupStorage
                                    .getCreationTime(fromTier, database, fileName)
                                    .toInstant();
                            return !created.isBefore(fromInstant) && !created.isAfter(now);
                        } catch (IOException e) {
                            log.warn("[BACKUP_PROMOTION] Не удалось прочитать атрибуты файла {} (tier={}): {}",
                                    fileName, fromTier, e.getMessage(), e);
                            return false;
                        }
                    })
                    // 3. самый новый по дате создания
                    .max(Comparator.comparing(fileName -> {
                        try {
                            return backupStorage
                                    .getCreationTime(fromTier, database, fileName)
                                    .toInstant();
                        } catch (IOException e) {
                            log.warn("[BACKUP_PROMOTION] Ошибка при повторном чтении атрибутов файла {} (tier={}): {}",
                                    fileName, fromTier, e.getMessage(), e);
                            return Instant.EPOCH;
                        }
                    }));

            if (candidate.isEmpty()) {
                log.warn("[BACKUP_PROMOTION] Нет подходящих SUCCESSFUL бэкапов для promotion из {} в {} за период {}",
                        fromTier, toTier, period);
                return;
            }

            // Копируем файл (FileSystemBackupStorage.copy атомарно перезапишет, если существует)
            backupStorage.copy(candidate.get(), fromTier, toTier, database);
            log.info("[BACKUP_PROMOTION] Успешный promotion бэкапа {} из {} в {}", candidate.get(), fromTier, toTier);

        } catch (Exception e) {
            log.error("[BACKUP_PROMOTION] Необработанная ошибка promotion из {} в {}", fromTier, toTier, e);
            throw new BackupStorageException("Failed to promote backup from " + fromTier + " to " + toTier, e);
        }
    }

    /**
     * Проверяет по report.json, является ли бэкап успешным.
     *
     * @param fileName имя файла бэкапа
     * @param tier     уровень хранения
     * @param database имя базы данных
     * @return {@code true}, если бэкап помечен как успешный; иначе {@code false}
     */
    public boolean isSuccessful(String fileName, BackupTier tier, String database) {
        try (InputStream in = backupStorage.load(tier, database, fileName)) {
            BackupReport report = backupFileReader.readReport(in);
            BackupStatus status =  report.status();
            boolean success = status == BackupStatus.SUCCESS;

            if (!success) {
                log.debug("[BACKUP_PROMOTION] Бэкап {} на уровне {} не отмечен как SUCCESS", fileName, tier);
            }
            return success;
        } catch (IOException e) {
            log.warn("[BACKUP_PROMOTION] Не удалось прочитать отчёт для бэкапа {} на уровне {}: {}",
                    fileName, tier, e.getMessage(), e);
            return false;
        }
    }
}