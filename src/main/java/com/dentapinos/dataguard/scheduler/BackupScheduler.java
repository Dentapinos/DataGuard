package com.dentapinos.dataguard.scheduler;

import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.service.BackupFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Планировщик регулярного бэкапа всех баз, перечисленных в backup.databases.
 * <p>
 * По расписанию, заданному в {@code BackupScheduleProperties.dailyBackupCron},
 * последовательно запускает бэкап для каждой базы. Ошибка по одной базе не прерывает
 * бэкап остальных. Логирует начало выполнения, прогресс для каждой базы и статистику
 * завершения операции.
 * */
@Slf4j
@Component
@RequiredArgsConstructor
public class BackupScheduler {

    private final BackupFacade backupFacade;
    private final BackupDatabasesProperties backupDatabasesProperties;

    /**
     * Ежедневный запуск бэкапа для всех баз.
     * <p>
     * Ошибка по одной базе не прерывает бэкап остальных.
     * Логирует начало выполнения, прогресс для каждой базы и статистику завершения.
     */
    @Scheduled(cron = "#{@backupScheduleProperties.dailyBackupCron}",
            zone = "#{@backupScheduleProperties.zoneId}")
    public void runBackup() {
        log.info("[BACKUP_PIPELINE] === Начало ежедневного бэкапа для всех баз ===");
        AtomicInteger total = new AtomicInteger();
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        backupDatabasesProperties.getDatabases().forEach(db -> {
            String dbName = db.getDatabaseName();
            DbCredentials credentials = new DbCredentials(
                    db.getUrl(),
                    db.getUsername(),
                    db.getPassword()
            );

            log.debug("[BACKUP_PIPELINE] Запуск бэкапа для db={}", dbName);
            total.getAndIncrement();
            try {
                String backupName = backupFacade.backupAndStore(dbName, credentials);
                log.info("[BACKUP_PIPELINE] Бэкап для db={} успешно завершён, файл={}", dbName, backupName);
                success.getAndIncrement();
            } catch (IOException e) {
                log.error("[BACKUP_PIPELINE] Ошибка ввода-вывода при бэкапе db={}", dbName, e);
                failed.getAndIncrement();
            } catch (Exception e) {
                log.error("[BACKUP_PIPELINE] Неизвестная ошибка при бэкапе db={}", dbName, e);
                failed.getAndIncrement();
            }
        });

        log.info("[BACKUP_PIPELINE] === Завершение ежедневного бэкапа для всех баз ===");
        log.info("[BACKUP_PIPELINE] Всего: {}, успешно: {}, ошибок: {}", total, success, failed);
    }
}