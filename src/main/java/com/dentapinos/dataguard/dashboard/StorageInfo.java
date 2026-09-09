package com.dentapinos.dataguard.dashboard;

/**
 * Информация о хранилище.
 */
public record StorageInfo(
    String basePath,
    long usedSpaceBytes,
    long maxSpaceBytes,
    double usagePercent,
    long currentBackupBytes,
    long peakBackupAdditionBytes,
    long peakTotalBytes,
    double peakTotalPercent,
    StorageStatus status
) {}