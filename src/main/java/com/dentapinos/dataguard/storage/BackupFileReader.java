package com.dentapinos.dataguard.storage;


import com.dentapinos.dataguard.entity.storage.BackupFile;
import com.dentapinos.dataguard.exception.RestoreZipException;
import com.dentapinos.dataguard.report.BackupReport;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class BackupFileReader {

    private final ObjectMapper objectMapper;
    private final BackupFileNamingService backupFileNamingService;
    private static final String BACKUP_ENTRY_NAME = ZipBackupConstants.BACKUP_JSON;
    private static final String REPORT_ENTRY_NAME = ZipBackupConstants.REPORT_JSON;

    /**
     * Читает и десериализует JSON-отчёт (report.json) из ZIP-архива.
     * <p>
     * ВАЖНО: метод закрывает переданный InputStream через ZipInputStream.
     */
    public BackupReport readReport(InputStream zipStream) throws IOException {
        return readFromZip(zipStream, REPORT_ENTRY_NAME, BackupReport.class);
    }

    /**
     * Читает и десериализует основную сущность бэкапа (backup.json) из ZIP-архива.
     * <p>
     * ВАЖНО: метод закрывает переданный InputStream через ZipInputStream.
     */
    public BackupFile readBackupFile(InputStream zipStream) throws IOException {
        return readFromZip(zipStream, BACKUP_ENTRY_NAME, BackupFile.class);
    }

    /**
     * Читает и десериализует указанный JSON-файл внутри ZIP-архива в объект заданного типа.
     * <p>
     * ВАЖНО: метод закрывает переданный InputStream через ZipInputStream.
     *
     * @param zipStream поток ZIP-архива
     * @param entryName имя JSON-файла внутри ZIP (например, "backup.json")
     * @param type      класс целевого объекта
     * @param <T>       тип целевого объекта
     * @return десериализованный объект
     */
    public <T> T readFromZip(InputStream zipStream, String entryName, Class<T> type) {
        try (ZipInputStream zipIn = new ZipInputStream(zipStream, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    try {
                        return objectMapper.readValue(zipIn, type);
                    } catch (Exception e) {
                        String msg = "Ошибка десериализации JSON из записи ZIP-архива: " + entryName;
                        log.error(msg, e);
                        throw new RestoreZipException(msg, e);
                    }
                }
            }
            String msg = "Запись не найдена в ZIP-архиве: " + entryName;
            log.error(msg);
            throw new RestoreZipException(msg);
        } catch (IOException e) {
            String msg = "Ошибка чтения ZIP-архива";
            log.error(msg, e);
            throw new RestoreZipException(msg, e);
        }
    }

    /**
     * Генерирует имя файла бэкапа через BackupFileNamingService.
     * <p>
     * Использует формат: backup-{database}-{timestamp}.zip
     * где timestamp = yyyy-MM-dd'T'HH-mm-ss (UTC).
     */
    public String generateBackupFileName(String database, String extension) {
        return backupFileNamingService.generateBackupFileName(database, extension);
    }
}