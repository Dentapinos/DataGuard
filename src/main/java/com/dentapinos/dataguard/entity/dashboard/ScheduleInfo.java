package com.dentapinos.dataguard.entity.dashboard;

/**
 * Информация о расписании бэкапов.
 */
public record ScheduleInfo(
    String backupTime,
    String promotionTime,
    String retentionTime
) {}