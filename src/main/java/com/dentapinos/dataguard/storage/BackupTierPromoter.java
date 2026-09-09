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
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
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
     * Находит в {@code fromTier} самый новый бэкап за указанный период,
     * копирует его в {@code toTier} и удаляет из исходного tier.
     * <p>
     * Если {@code checkStatus} == true, проверяет report.json на SUCCESS
     * (используется для DAILY → WEEKLY). Для последующих promotion статус
     * не проверяется — файл считается проверенным.
     *
     * @param database     имя базы данных
     * @param fromTier     исходный уровень хранения (откуда брать бэкап)
     * @param toTier       целевой уровень хранения (куда копировать)
     * @param period       максимальная «давность» бэкапа
     * @param checkStatus  true — проверить SUCCESS по report.json (только для DAILY→WEEKLY)
     */
    public void promote(String database, BackupTier fromTier, BackupTier toTier, Period period, boolean checkStatus) {
        try {
            LocalDate now = LocalDate.now(ZoneId.of("UTC"));
            LocalDate fromLocalDate = now.minus(period);

            log.debug("[BACKUP_PROMOTION] Запуск promotion: from={} to={} period={} checkStatus={}",
                    fromTier, toTier, period, checkStatus);

            var files = backupStorage.list(fromTier, database);

            Optional<String> candidate = files.stream()
                    // 1. проверка статуса (только для DAILY → WEEKLY)
                    .filter(fileName -> !checkStatus || isSuccessful(fileName, fromTier, database))
                    // 2. только в окне [fromLocalDate, now]
                    .filter(fileName -> {
                        try {
                            LocalDate createdDate = backupStorage
                                    .getCreationTime(fromTier, database, fileName)
                                    .toInstant()
                                    .atZone(ZoneId.of("UTC"))
                                    .toLocalDate();
                            return !createdDate.isBefore(fromLocalDate) && !createdDate.isAfter(now);
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
                log.warn("[BACKUP_PROMOTION] Нет подходящих бэкапов для promotion из {} в {} за период {}",
                        fromTier, toTier, period);
                return;
            }

            String fileName = candidate.get();

            // Копируем файл
            backupStorage.copy(fileName, fromTier, toTier, database);
            log.info("[BACKUP_PROMOTION] Успешно скопирован бэкап {} из {} в {}",
                    fileName, fromTier, toTier);

            // Удаляем исходный файл (copy → delete)
            backupStorage.delete(fromTier, database, fileName);
            log.info("[BACKUP_PROMOTION] Удалён исходный бэкап {} из {} после успешного promotion",
                    fileName, fromTier);

        } catch (Exception e) {
            log.error("[BACKUP_PROMOTION] Необработанная ошибка promotion из {} в {}", fromTier, toTier, e);
            throw new BackupStorageException("Failed to promote backup from " + fromTier + " to " + toTier, e);
        }
    }

    /**
     * Обёртка для обратной совместимости: promotion с проверкой статуса.
     */
    public void promote(String database, BackupTier fromTier, BackupTier toTier, Period period) {
        promote(database, fromTier, toTier, period, true);
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