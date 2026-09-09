package com.dentapinos.dataguard.dashboard;

/**
 * Статус заполнения хранилища.
 */
public enum StorageStatus {
    OK,           // < 80%
    WARNING,      // 80-89%
    CRITICAL      // >= 90%
}
