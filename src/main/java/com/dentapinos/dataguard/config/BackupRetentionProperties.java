package com.dentapinos.dataguard.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки политики хранения (retention) для разных уровней бэкапов.
 *
 * <p>Читает значения из конфигурации по префиксу "backup.retention".
 * Используется сервисом retention для решения, какие бэкапы считать
 * «просроченными» и удалять.</p>
 */
@Slf4j
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "backup.retention")
@Validated
public class BackupRetentionProperties {

    /** Сколько дней хранить ежедневные (DAILY) бэкапы. */
    @Min(value = 1, message = "dailyDays must be at least 1")
    private int dailyDays = 14;

    /** Сколько недель хранить еженедельные (WEEKLY) бэкапы. */
    @Min(value = 1, message = "weeklyWeeks must be at least 1")
    private int weeklyWeeks = 12;

    /** Сколько месяцев хранить ежемесячные (MONTHLY) бэкапы. */
    @Min(value = 1, message = "monthlyMonths must be at least 1")
    private int monthlyMonths = 24;

    /** Сколько лет хранить полугодовые (SEMI_ANNUAL) бэкапы. */
    @Min(value = 1, message = "semiAnnualYears must be at least 1")
    private int semiAnnualYears = 5;

    /** Сколько лет хранить годовые (ANNUAL) бэкапы. */
    @Min(value = 1, message = "annualYears must be at least 1")
    private int annualYears = 10;

    /**
     * Логирование конфигурации после инициализации.
     */
    @PostConstruct
    public void logConfiguration() {
        log.info("Backup retention policy configured:");
        log.info("  - Daily: {} days", dailyDays);
        log.info("  - Weekly: {} weeks", weeklyWeeks);
        log.info("  - Monthly: {} months", monthlyMonths);
        log.info("  - Semi-annual: {} years", semiAnnualYears);
        log.info("  - Annual: {} years", annualYears);
    }
}