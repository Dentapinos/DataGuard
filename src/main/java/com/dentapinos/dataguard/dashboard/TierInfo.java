package com.dentapinos.dataguard.dashboard;

import com.dentapinos.dataguard.enums.BackupTier;

/**
 * Информация об уровне хранения бэкапов.
 */
public record TierInfo(
    BackupTier tier,
    long fileCount,
    long totalSizeBytes,
    long maxCount,
    String retentionPeriod
) {}