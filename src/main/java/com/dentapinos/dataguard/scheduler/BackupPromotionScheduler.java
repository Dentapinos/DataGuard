package com.dentapinos.dataguard.scheduler;

import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.storage.BackupTierPromoter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Period;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Планировщик задач продвижения бэкапов между уровнями хранения для всех баз,
 * перечисленных в {@link BackupDatabasesProperties}.
 * <p>
 * Отвечает за регулярное копирование (promotion) наиболее подходящих бэкапов
 * в более «старшие» уровни:
 * <ul>
 *     <li>{@link BackupTier#DAILY} → {@link BackupTier#WEEKLY}</li>
 *     <li>{@link BackupTier#WEEKLY} → {@link BackupTier#MONTHLY}</li>
 *     <li>{@link BackupTier#MONTHLY} → {@link BackupTier#SEMI_ANNUAL}</li>
 *     <li>{@link BackupTier#SEMI_ANNUAL} → {@link BackupTier#ANNUAL}</li>
 * </ul>
 * Для каждой базы из настроек {@code backup.databases} выполняются одинаковые сценарии.
 * CRON-расписания и часовой пояс задаются в {@code BackupScheduleProperties}
 * и подставляются через SpEL-выражения в аннотациях {@link Scheduled}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupPromotionScheduler {

    private final BackupTierPromoter backupTierPromoter;
    private final BackupDatabasesProperties backupDatabasesProperties;

    /**
     * Периодически пробует промотировать бэкапы с уровня DAILY на уровень WEEKLY
     * за последние 7 дней для всех баз из {@code backup.databases}.
     * <p>
     * Метод идемпотентен: если подходящих бэкапов нет или они уже промотированы,
     * повторный запуск просто ничего не сделает.
     * <p>
     * Логирует начало выполнения, процесс для каждой базы и количество обработанных баз.
     */
    @Scheduled(cron = "#{@backupScheduleProperties.promoteDailyToWeeklyCron}",
            zone = "#{@backupScheduleProperties.zoneId}")
    public void promoteDailyToWeekly() {
        log.info("[BACKUP_PROMOTION_SCHEDULER] === Начало promotion DAILY → WEEKLY ===");
        AtomicInteger processed = new AtomicInteger();
        backupDatabasesProperties.getDatabases().forEach(db -> {
            String dbName = db.getDatabaseName();
            log.debug("[BACKUP_PROMOTION_SCHEDULER] DAILY → WEEKLY для db={}", dbName);
            try {
                backupTierPromoter.promote(
                        dbName,
                        BackupTier.DAILY,
                        BackupTier.WEEKLY,
                        Period.ofDays(7)
                );
                log.info("[BACKUP_PROMOTION_SCHEDULER] DAILY → WEEKLY успешно завершен для db={}", dbName);
                processed.getAndIncrement();
            } catch (Exception e) {
                log.error("[BACKUP_PROMOTION_SCHEDULER] Ошибка при promotion DAILY → WEEKLY для db={}", dbName, e);
            }
        });
        log.info("[BACKUP_PROMOTION_SCHEDULER] === Завершение promotion DAILY → WEEKLY: обработано {} баз ===", processed);
    }

    /**
     * Периодически пробует промотировать бэкапы с уровня WEEKLY на уровень MONTHLY
     * за последние 31 день для всех баз.
     * <p>
     * Можно было бы запускать только в определённые дни (например, в начале месяца),
     * но при ежедневном запуске сервис сам выбирает «самый новый успешный» бэкап
     * в заданном периоде и не создаёт дубликатов.
     * <p>
     * Логирует начало выполнения, процесс для каждой базы и количество обработанных баз.
     */
    @Scheduled(cron = "#{@backupScheduleProperties.promoteWeeklyToMonthlyCron}",
            zone = "#{@backupScheduleProperties.zoneId}") // ежедневно в 02:05 UTC
    public void promoteWeeklyToMonthly() {
        log.info("[BACKUP_PROMOTION_SCHEDULER] === Начало promotion WEEKLY → MONTHLY ===");
        AtomicInteger processed = new AtomicInteger();
        backupDatabasesProperties.getDatabases().forEach(db -> {
            String dbName = db.getDatabaseName();
            log.debug("[BACKUP_PROMOTION_SCHEDULER] WEEKLY → MONTHLY для db={}", dbName);
            try {
                backupTierPromoter.promote(
                        dbName,
                        BackupTier.WEEKLY,
                        BackupTier.MONTHLY,
                        Period.ofDays(31)
                );
                log.info("[BACKUP_PROMOTION_SCHEDULER] WEEKLY → MONTHLY успешно завершен для db={}", dbName);
                processed.getAndIncrement();
            } catch (Exception e) {
                log.error("[BACKUP_PROMOTION_SCHEDULER] Ошибка при promotion WEEKLY → MONTHLY для db={}", dbName, e);
            }
        });
        log.info("[BACKUP_PROMOTION_SCHEDULER] === Завершение promotion WEEKLY → MONTHLY: обработано {} баз ===", processed);
    }

    /**
     * Периодически пробует промотировать бэкапы с уровня MONTHLY на уровень SEMI_ANNUAL
     * за последние 6 месяцев для всех баз.
     * <p>
     * Логирует начало выполнения, процесс для каждой базы и количество обработанных баз.
     */
    @Scheduled(cron = "#{@backupScheduleProperties.promoteMonthlyToSemiAnnualCron}",
            zone = "#{@backupScheduleProperties.zoneId}")
    public void promoteMonthlyToSemiAnnual() {
        log.info("[BACKUP_PROMOTION_SCHEDULER] === Начало promotion MONTHLY → SEMI_ANNUAL ===");
        AtomicInteger processed = new AtomicInteger();
        backupDatabasesProperties.getDatabases().forEach(db -> {
            String dbName = db.getDatabaseName();
            log.debug("[BACKUP_PROMOTION_SCHEDULER] MONTHLY → SEMI_ANNUAL для db={}", dbName);
            try {
                backupTierPromoter.promote(
                        dbName,
                        BackupTier.MONTHLY,
                        BackupTier.SEMI_ANNUAL,
                        Period.ofMonths(6)
                );
                log.info("[BACKUP_PROMOTION_SCHEDULER] MONTHLY → SEMI_ANNUAL успешно завершен для db={}", dbName);
                processed.getAndIncrement();
            } catch (Exception e) {
                log.error("[BACKUP_PROMOTION_SCHEDULER] Ошибка при promotion MONTHLY → SEMI_ANNUAL для db={}", dbName, e);
            }
        });
        log.info("[BACKUP_PROMOTION_SCHEDULER] === Завершение promotion MONTHLY → SEMI_ANNUAL: обработано {} баз ===", processed);
    }

    /**
     * Периодически пробует промотировать бэкапы с уровня SEMI_ANNUAL на уровень ANNUAL
     * за последние 12 месяцев для всех баз.
     * <p>
     * Логирует начало выполнения, процесс для каждой базы и количество обработанных баз.
     */
    @Scheduled(cron = "#{@backupScheduleProperties.promoteSemiAnnualToAnnualCron}",
            zone = "#{@backupScheduleProperties.zoneId}")
    public void promoteSemiAnnualToAnnual() {
        log.info("[BACKUP_PROMOTION_SCHEDULER] === Начало promotion SEMI_ANNUAL → ANNUAL ===");
        AtomicInteger processed = new AtomicInteger();
        backupDatabasesProperties.getDatabases().forEach(db -> {
            String dbName = db.getDatabaseName();
            log.debug("[BACKUP_PROMOTION_SCHEDULER] SEMI_ANNUAL → ANNUAL для db={}", dbName);
            try {
                backupTierPromoter.promote(
                        dbName,
                        BackupTier.SEMI_ANNUAL,
                        BackupTier.ANNUAL,
                        Period.ofYears(1)
                );
                log.info("[BACKUP_PROMOTION_SCHEDULER] SEMI_ANNUAL → ANNUAL успешно завершен для db={}", dbName);
                processed.getAndIncrement();
            } catch (Exception e) {
                log.error("[BACKUP_PROMOTION_SCHEDULER] Ошибка при promotion SEMI_ANNUAL → ANNUAL для db={}", dbName, e);
            }
        });
        log.info("[BACKUP_PROMOTION_SCHEDULER] === Завершение promotion SEMI_ANNUAL → ANNUAL: обработано {} баз ===", processed);
    }
}
