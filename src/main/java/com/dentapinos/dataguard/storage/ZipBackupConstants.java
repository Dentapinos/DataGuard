package com.dentapinos.dataguard.storage;

/**
 * Константы для ZIP-бэкапов.
 * <p>
 * Определяет имена записей внутри ZIP-архива бэкапа.
 * Эти константы используются как при записи (ZipBackupStorageService),
 * так и при чтении (BackupFileReader) ZIP-файлов.
 * <p>
 * Использование констант гарантирует согласованность имен записей
 * и предотвращает ошибки из-за опечаток.
 */
public class ZipBackupConstants {

    /** Имя записи с данными бэкапа (JSON) */
    public static final String BACKUP_JSON = "backup.json";

    /** Имя записи с отчётом о бэкапе (JSON) */
    public static final String REPORT_JSON = "report.json";

    private ZipBackupConstants() {
        // Закрытый конструктор для предотвращения инстанцирования
    }
}
