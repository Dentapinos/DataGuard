package com.dentapinos.dataguard.storage;

import com.dentapinos.dataguard.exception.BackupStorageException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

@Service
public class BackupFileNamingService {

    private static final Pattern BACKUP_FILENAME_PATTERN =
            Pattern.compile("^backup-([a-zA-Z0-9_]+)-\\d{4}-\\d{2}-\\d{2}T\\d{2}-\\d{2}-\\d{2}\\.zip$");
    
    private static final DateTimeFormatter FILENAME_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss").withZone(ZoneOffset.UTC);

    /**
     * Генерирует имя файла бэкапа вида:
     * backup-{database}-{timestamp}.zip
     * где timestamp = yyyy-MM-dd'T'HH-mm-ss (UTC).
     * 
     * <p>Правила формирования имени файла:</p>
     * <ul>
     *   <li>Префикс: "backup-"</li>
     *   <li>Имя базы: любые латинские буквы, цифры, подчеркивание</li>
     *   <li>Разделитель: "-"</li>
     *   <li>Временная метка: yyyy-MM-dd'T'HH-mm-ss в формате UTC</li>
     *   <li>Расширение: .zip</li>
     * </ul>
     * 
     * @param database имя базы данных (без пробелов и специальных символов)
     * @param extension расширение файла (по умолчанию "zip")
     * @return имя файла бэкапа
     * @throws BackupStorageException если имя базы данных пустое или содержит недопустимые символы
     */
    public String generateBackupFileName(String database, String extension) {
        if (database == null || database.trim().isEmpty()) {
            throw new BackupStorageException("Имя базы данных не может быть пустым или null");
        }
        
        // Проверка на допустимые символы в имени базы (только латиница, цифры, подчеркивание)
        if (!database.matches("^[a-zA-Z0-9_]+$")) {
            throw new BackupStorageException(
                    "Имя базы данных '" + database + "' содержит недопустимые символы. " +
                    "Допускаются только латинские буквы, цифры и подчеркивание");
        }
        
        String ts = FILENAME_TIMESTAMP_FORMATTER.format(Instant.now());
        String base = "backup-" + database + "-" + ts;
        if (extension != null && !extension.isBlank()) {
            return base + "." + extension;
        }
        return base;
    }

    /**
     * Парсит имя файла бэкапа и извлекает имя базы данных.
     *
     * @param filename имя файла бэкапа
     * @return имя базы данных
     * @throws BackupStorageException если имя файла не соответствует формату
     */
    public String parseDatabaseName(String filename) {
        return parseDatabaseNameWithValidation(filename, true);
    }

    /**
     * Парсинг с возможностью отключения строгой валидации.
     *
     * @param filename имя файла бэкапа
     * @param strictValidation флаг строгой валидации
     * @return имя базы данных
     * @throws BackupStorageException если имя файла не соответствует формату (при strictValidation=true)
     */
    public String parseDatabaseNameWithValidation(String filename, boolean strictValidation) {
        if (filename == null || filename.trim().isEmpty()) {
            throw new BackupStorageException("Имя файла не может быть пустым или null");
        }

        if (strictValidation && !isValidFileName(filename)) {
            throw new BackupStorageException(
                    "Имя файла '" + filename + "' не соответствует формату бэкапа");
        }

        String nameWithoutExtension = removeFileExtension(filename);
        String[] parts = nameWithoutExtension.split("-");

        if (parts.length < 2) {
            throw new BackupStorageException(
                    "Недостаточно частей в имени файла: " + filename);
        }

        return parts[1];
    }

    /**
     * Проверяет, соответствует ли имя файла формату бэкапа.
     *
     * @param filename имя файла
     * @return true если имя файла соответствует формату
     */
    public boolean isValidFileName(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }
        return BACKUP_FILENAME_PATTERN.matcher(filename).matches();
    }

    /**
     * Удаляет расширение файла (всё после последней точки).
     * Если точка находится в конце строки без расширения, она сохраняется.
     *
     * @param filename имя файла
     * @return имя файла без расширения
     */
    public String removeFileExtension(String filename) {
        if (filename == null || filename.isEmpty()) {
            return filename;
        }
        
        int lastDotIndex = filename.lastIndexOf('.');
        // Если точка в конце строки или нет точки, возвращаем как есть
        if (lastDotIndex < 0 || lastDotIndex == filename.length() - 1) {
            return filename;
        }
        return filename.substring(0, lastDotIndex);
    }
}
