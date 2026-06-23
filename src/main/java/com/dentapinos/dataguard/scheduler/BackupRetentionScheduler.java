package com.dentapinos.dataguard.scheduler;


import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.storage.BackupRetentionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;


/**
 * Планировщик применения политики хранения (retention) для всех уровней бэкапов
 * по всем базам, перечисленным в настройках backup.databases.
 * <p>
 * Применяет политику хранения к каждому уровню (DAILY, WEEKLY, MONTHLY, SEMI_ANNUAL, ANNUAL)
 * для каждой базы данных. При ошибке для одной базы продолжает обработку остальных.
 * <p>
 * Логирует начало выполнения, процесс применения политики для каждой базы и уровня,
 * а также количество успешных и неудачных операций.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupRetentionScheduler {

    private final BackupRetentionManager backupRetentionManager;
    private final BackupDatabasesProperties backupDatabasesProperties;

    /**
     * Периодически применяет политику хранения ко всем уровням бэкапов
     * для каждой базы из backup.databases.
     * <p>
     * Для каждой базы и каждого уровня логирует попытку применения,
     * успешное завершение или ошибку. Ошибка по одной базе/уровню не прерывает
     * выполнение для остальных.
     * <p>
     * CRON-выражение и часовой пояс берутся из {@code BackupScheduleProperties}
     * через SpEL-ссылки в параметрах аннотации {@link Scheduled}.
     */
    @Scheduled(cron = "#{@backupScheduleProperties.retentionCron}",
            zone = "#{@backupScheduleProperties.zoneId}")
    public void applyRetentionForAllTiers() {
        log.info("[BACKUP_RETENTION_SCHEDULER] === Начало применения политики хранения ===");
        AtomicInteger totalOps = new AtomicInteger();
        AtomicInteger successOps = new AtomicInteger();
        AtomicInteger failedOps = new AtomicInteger();

        backupDatabasesProperties.getDatabases().forEach(db -> {
            String databaseName = db.getDatabaseName();

            for (BackupTier tier : BackupTier.values()) {
                totalOps.getAndIncrement();
                log.debug("[BACKUP_RETENTION_SCHEDULER] применяем политику хранения: db={}, tier={}",
                        databaseName, tier);
                try {
                    backupRetentionManager.applyRetention(tier, databaseName);
                    log.info("[BACKUP_RETENTION_SCHEDULER] политика хранения успешно применена: db={}, tier={}",
                            databaseName, tier);
                    successOps.getAndIncrement();
                } catch (Exception e) {
                    log.error("[BACKUP_RETENTION_SCHEDULER] ошибка при применении политики: db={}, tier={}",
                            databaseName, tier, e);
                    failedOps.getAndIncrement();
                }
            }
        });

        log.info("[BACKUP_RETENTION_SCHEDULER] === Завершение применения политики хранения ===");
        log.info("[BACKUP_RETENTION_SCHEDULER] Всего операций: {}, успешных: {}, ошибок: {}",
                totalOps, successOps, failedOps);
    }
}