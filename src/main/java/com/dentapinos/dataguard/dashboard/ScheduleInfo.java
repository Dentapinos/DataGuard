package com.dentapinos.dataguard.dashboard;

/**
 * Информация о расписании бэкапов.
 */
public record ScheduleInfo(
    String backupTime,
    String promotionTime,
    String retentionTime
) {}