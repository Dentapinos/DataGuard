package com.dentapinos.dataguard.entity.dashboard;

import com.dentapinos.dataguard.enums.BackupTier;

/**
 * Информация об уровне хранения бэкапов.
 */
public record TierInfo(
    BackupTier tier,
    long fileCount,
    long totalSizeBytes
) {}