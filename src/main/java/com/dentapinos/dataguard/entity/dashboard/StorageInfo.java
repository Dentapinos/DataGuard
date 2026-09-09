package com.dentapinos.dataguard.entity.dashboard;

/**
 * Информация о хранилище.
 */
public record StorageInfo(
    String basePath,
    long usedSpaceBytes,
    long maxSpaceBytes,
    double usagePercent
) {}