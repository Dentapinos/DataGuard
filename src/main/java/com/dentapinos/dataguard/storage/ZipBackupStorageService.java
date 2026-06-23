package com.dentapinos.dataguard.storage;

import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.report.BackupEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class ZipBackupStorageService {

    private final BackupStorage backupStorage;
    private final ObjectMapper objectMapper;
    private final BackupFileReader backupFileReader;

    /**
     * Сохраняет бэкап в хранилище в формате ZIP с отчётом.
     * <p>
     * Использует BackupFileNamingService для генерации имени файла.
     * По умолчанию сохраняет в tier=DAILY (для автоматических бэкапов).
     * Для более гибкого использования см. {@link #storeBackupWithReport(String, BackupEnvelope, BackupTier)}
     *
     * @param database имя базы данных
     * @param envelope обёртка бэкапа (данные + отчёт)
     * @return имя сохранённого файла бэкапа
     * @throws IOException если операция сохранения не удалась
     */
    public String storeBackupWithReport(String database, BackupEnvelope envelope) throws IOException {
        return storeBackupWithReport(database, envelope, BackupTier.DAILY);
    }

    /**
     * Сохраняет бэкап в хранилище в формате ZIP с отчётом и явным указанием tier.
     * <p>
     * Использует BackupFileNamingService для генерации имени файла.
     * Это позволяет явно контролировать, в какой tier будет сохранён бэкап.
     *
     * @param database имя базы данных
     * @param envelope обёртка бэкапа (данные + отчёт)
     * @param tier     уровень хранения (DAILY, WEEKLY, MONTHLY и т.д.)
     * @return имя сохранённого файла бэкапа
     * @throws IOException если операция сохранения не удалась
     */
    public String storeBackupWithReport(String database, BackupEnvelope envelope, BackupTier tier) throws IOException {
        String backupName = backupFileReader.generateBackupFileName(database, "zip");

        Path tempFile = Files.createTempFile("backup-", ".zip");
        log.debug("[BACKUP_ZIP_SERVICE] Создан временный файл для ZIP: {}", tempFile);

        try {
            // 1. Пишем ZIP во временный файл
            try (OutputStream fileOut = Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING);
                 ZipOutputStream zipOut = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {

                writeJsonEntry(zipOut, ZipBackupConstants.BACKUP_JSON, envelope.backup());
                writeJsonEntry(zipOut, ZipBackupConstants.REPORT_JSON, envelope.report());
            }

            // 2. Передаём содержимое в BackupStorage
            try (InputStream in = Files.newInputStream(tempFile, StandardOpenOption.READ)) {
                backupStorage.save(tier, database, backupName, in);
            }

            log.info("[BACKUP_ZIP_SERVICE] Бэкап сохранён в zip: tier={}, database={}, file={}", tier, database, backupName);
        } finally {
            try {
                Files.deleteIfExists(tempFile);
                log.debug("[BACKUP_ZIP_SERVICE] Временный файл ZIP удалён: {}", tempFile);
            } catch (IOException e) {
                log.warn("[BACKUP_ZIP_SERVICE] Не удалось удалить временный файл ZIP: {}, error={}",
                        tempFile, e.getMessage(), e);
            }
        }
        return backupName;
    }

    private void writeJsonEntry(ZipOutputStream zipOut, String entryName, Object value) throws IOException {
        byte[] jsonBytes = objectMapper.writeValueAsBytes(value);

        ZipEntry entry = new ZipEntry(entryName);
        zipOut.putNextEntry(entry);
        zipOut.write(jsonBytes);
        zipOut.closeEntry();
    }
}