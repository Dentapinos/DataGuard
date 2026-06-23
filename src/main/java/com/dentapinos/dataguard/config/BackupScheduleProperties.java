package com.dentapinos.dataguard.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Конфигурация расписаний для задач бэкапа.
 * <p>
 * Связывается с настройками с префиксом {@code backup.schedule} в конфигурации
 * приложения (application.yml / application.properties).
 */
@Slf4j
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "backup.schedule")
@Validated
public class BackupScheduleProperties {

    /**
     * CRON‑выражение для запуска ежедневного бэкапа.
     */
    @NotBlank(message = "dailyBackupCron is required")
    private String dailyBackupCron;

    /**
     * CRON‑выражение для задачи, повышающей ежедневные бэкапы до недельных.
     */
    private String promoteDailyToWeeklyCron;

    /**
     * CRON‑выражение для задачи, повышающей недельные бэкапы до месячных.
     */
    private String promoteWeeklyToMonthlyCron;

    /**
     * CRON‑выражение для задачи, повышающей месячные бэкапы до полугодовых.
     */
    private String promoteMonthlyToSemiAnnualCron;

    /**
     * CRON‑выражение для задачи, повышающей полугодовые бэкапы до годовых.
     */
    private String promoteSemiAnnualToAnnualCron;

    /**
     * CRON‑выражение для задачи очистки старых бэкапов
     * согласно политике хранения (retention).
     */
    @NotBlank(message = "retentionCron is required")
    private String retentionCron;

    /**
     * Идентификатор часового пояса, в котором интерпретируются
     * все CRON‑выражения.
     * Значение по умолчанию: {@code Europe/Moscow}.
     */
    private String zoneId = "Europe/Moscow";

    /**
     * Логирование конфигурации после инициализации.
     */
    @PostConstruct
    public void logConfiguration() {
        log.info("Backup schedule configured:");
        log.info("  - Daily backup: {}", dailyBackupCron);
        log.info("  - Promote daily to weekly: {}", promoteDailyToWeeklyCron);
        log.info("  - Promote weekly to monthly: {}", promoteWeeklyToMonthlyCron);
        log.info("  - Promote monthly to semi-annual: {}", promoteMonthlyToSemiAnnualCron);
        log.info("  - Promote semi-annual to annual: {}", promoteSemiAnnualToAnnualCron);
        log.info("  - Retention: {}", retentionCron);
        log.info("  - Timezone: {}", zoneId);
    }
}