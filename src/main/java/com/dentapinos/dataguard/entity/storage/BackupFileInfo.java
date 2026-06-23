package com.dentapinos.dataguard.entity.storage;

import com.dentapinos.dataguard.enums.BackupTier;

import java.time.Instant;

/**
 * Модель информации о файле резервной копии.
 */
public record BackupFileInfo(String fileName, BackupTier tier, String database, Instant creationTime, long size) {


    @Override
    public String toString() {
        return "BackupFileInfo{" +
                "fileName='" + fileName + '\'' +
                ", tier=" + tier +
                ", database='" + database + '\'' +
                ", creationTime=" + creationTime +
                ", size=" + size + " byte" +
                '}';
    }
}
