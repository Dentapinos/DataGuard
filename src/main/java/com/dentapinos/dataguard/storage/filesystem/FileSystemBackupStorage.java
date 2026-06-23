package com.dentapinos.dataguard.storage.filesystem;


import com.dentapinos.dataguard.config.BackupProperties;
import com.dentapinos.dataguard.config.FileSystemStorageProperties;
import com.dentapinos.dataguard.entity.storage.BackupFileInfo;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.exception.BackupFileNotFoundException;
import com.dentapinos.dataguard.storage.BackupStorage;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class FileSystemBackupStorage implements BackupStorage {

    private final BackupProperties backupProperties;

    @Override
    public void save(@Nullable BackupTier tier, String database, String fileName, InputStream content) throws IOException {
        Path filePath = resolvePath(tier, database, fileName);
        Path parentDir = filePath.getParent();
        Files.createDirectories(parentDir);

        // Создаем временный файл для атомарной записи
        Path tempFile = Files.createTempFile(parentDir, fileName + ".", ".tmp");

        try {
            // Пишем во временный файл
            Files.copy(content, tempFile, StandardCopyOption.REPLACE_EXISTING);

            // Атомарно перемещаем во временный файл (переименовывает)
            Files.move(tempFile, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            log.info("[BACKUP_STORAGE] Успешно сохранён бэкап: tier={}, database={}, file={}",
                    tier, database, fileName);
        } catch (IOException e) {
            // Удаляем временный файл в случае ошибки
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException deleteException) {
                log.warn("[BACKUP_STORAGE] Не удалось удалить временный файл: {}", tempFile, deleteException);
            }
            log.error("[BACKUP_STORAGE] Ошибка сохранения бэкапа: tier={}, database={}, file={}, error={}",
                    tier, database, fileName, e.getMessage(), e);
            throw e;
        }
        // НЕ закрываем content — это ответственность вызывающего
    }

    @Override
    public InputStream load(@Nullable BackupTier tier, String database, String backupName) throws IOException {
        Path filePath = resolvePath(tier, database, backupName);
        try {
            InputStream in = Files.newInputStream(filePath);
            log.info("[BACKUP_STORAGE] Успешно загружен бэкап: tier={}, database={}, file={}",
                    tier, database, backupName);
            return in;
        } catch (NoSuchFileException e) {
            log.warn("[BACKUP_STORAGE] Бэкап не найден: tier={}, database={}, file={}",
                    tier, database, backupName);
            throw new BackupFileNotFoundException("Бэкап "+ backupName +" не найден");
        } catch (IOException e) {
            log.error("[BACKUP_STORAGE] Ошибка загрузки бэкапа: tier={}, database={}, file={}, error={}",
                    tier, database, backupName, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void delete(@Nullable BackupTier tier, String database, String backupName) throws IOException {
        Path filePath = resolvePath(tier, database, backupName);
        try {
            Files.deleteIfExists(filePath);
            log.info("[BACKUP_STORAGE] Бэкап удалён: tier={}, database={}, file={}",
                    tier, database, backupName);
        } catch (IOException e) {
            log.error("[BACKUP_STORAGE] Ошибка удаления бэкапа: tier={}, database={}, file={}, error={}",
                    tier, database, backupName, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public List<String> list(@Nullable BackupTier tier, String database) throws IOException {
        Path dbPath = resolvePath(tier, database);
        if (!Files.exists(dbPath) || !Files.isDirectory(dbPath)) {
            log.info("[BACKUP_STORAGE] Папка бэкапов не найдена или не является директорией: {} tier={}, database={}",
                    dbPath, tier, database);
            return List.of();
        }

        List<String> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dbPath)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path)) {
                    files.add(path.getFileName().toString());
                }
            }
        } catch (IOException e) {
            log.error("[BACKUP_STORAGE] Ошибка при перечислении файлов бэкапов: tier={}, database={}, error={}",
                    tier, database, e.getMessage(), e);
            throw e;
        }
        return files;
    }

    @Override
    public FileTime getCreationTime(@Nullable BackupTier tier, String database, String backupName) throws IOException {
        Path filePath = resolvePath(tier, database, backupName);
        try {
            FileTime creationTime = (FileTime) Files.getAttribute(filePath, "creationTime");
            log.debug("[BACKUP_STORAGE] Время создания бэкапа: tier={}, database={}, file={}, creationTime={}",
                    tier, database, backupName, creationTime.toInstant());
            return creationTime;
        } catch (IOException e) {
            log.error("[BACKUP_STORAGE] Ошибка получения времени создания бэкапа: tier={}, database={}, file={}, error={}",
                    tier, database, backupName, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public long getFileSize(@Nullable BackupTier tier, String database, String backupName) throws IOException {
        Path filePath = resolvePath(tier, database, backupName);
        try {
            return Files.size(filePath);
        } catch (IOException e) {
            log.error("[BACKUP_STORAGE] Ошибка получения размера файла бэкапа: tier={}, database={}, file={}, error={}",
                    tier, database, backupName, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public List<BackupFileInfo> listWithInfo(@Nullable BackupTier tier, String database) throws IOException {
        Path basePath = Path.of(backupProperties.getFileSystem().getBasePath())
                .resolve(database); // .../backups/bm

        List<BackupFileInfo> files = new ArrayList<>();

        BackupTier[] tiersToScan = (tier != null)
                ? new BackupTier[]{tier}
                : BackupTier.values();

        for (BackupTier currentTier : tiersToScan) {
            // .../backups/bm/DAILY
            Path tierPath = basePath.resolve(currentTier.name());

            if (!Files.exists(tierPath) || !Files.isDirectory(tierPath)) {
                log.warn("[BACKUP_STORAGE] Директория бэкапов не найдена или не является директорией: path={}, tier={}, database={}",
                        tierPath, currentTier, database);
                continue;
            }

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(tierPath)) {
                for (Path filePath : stream) {
                    if (!Files.isRegularFile(filePath)) {
                        continue;
                    }

                    String fileName = filePath.getFileName().toString();

                    try {
                        FileTime creationTime = (FileTime) Files.getAttribute(filePath, "creationTime");
                        long size = Files.size(filePath);

                        files.add(new BackupFileInfo(
                                fileName,
                                currentTier,
                                database,
                                creationTime.toInstant(),
                                size
                        ));
                    } catch (IOException e) {
                        log.warn("[BACKUP_STORAGE] Ошибка при получении информации о бэкапе: tier={}, database={}, file={}, error={}",
                                currentTier, database, fileName, e.getMessage(), e);
                    }
                }
            } catch (IOException e) {
                log.error("[BACKUP_STORAGE] Ошибка при перечислении файлов бэкапов: tier={}, database={}, path={}, error={}",
                        currentTier, database, tierPath, e.getMessage(), e);
                throw e;
            }
        }

        return files;
    }

    private Path resolvePath(@Nullable BackupTier tier, String database) {
        Path basePath = Path.of(backupProperties.getFileSystem().getBasePath());
        // сначала имя базы
        basePath = basePath.resolve(database);
        // потом tier (если есть)
        if (tier != null) {
            basePath = basePath.resolve(tier.name());
        }
        return basePath;
    }

    private Path resolvePath(@Nullable BackupTier tier, String database, String fileName) {
        return resolvePath(tier, database).resolve(fileName);
    }

    public BackupFileInfo getFileInfo(@Nullable BackupTier tier, String database, String fileName) throws IOException {
        Path filePath = resolvePath(tier, database, fileName);
        var attrs = Files.readAttributes(filePath, BasicFileAttributes.class);
        Instant creationTime = attrs.creationTime().toInstant();
        long size = attrs.size();
        return new BackupFileInfo(fileName, tier, database, creationTime, size);
    }

    public String copy(String fileName, BackupTier fromTier, BackupTier toTier, String database) throws IOException {
        // Формируем исходный путь: database/tier/fileName
        String sourcePath = String.format("%s/%s", fromTier, fileName);

        // Формируем целевой путь: database/tier/fileName
        String targetPath = String.format("%s/%s", toTier, fileName);

        // Выполняем копирование через сервис хранения
        processCopy(sourcePath, targetPath);

        log.info("Файл {} скопирован из {} в {} для базы данных {}",
                fileName, fromTier, toTier, database);

        return targetPath;
    }

    private void processCopy(String sourcePath, String targetPath) throws IOException {
        FileSystemStorageProperties fileSystem1 = backupProperties.getFileSystem();
        Path basePath = Path.of(fileSystem1.getBasePath());

        Path source = basePath.resolve(sourcePath);
        Path target = basePath.resolve(targetPath);

        // Создаём родительские директории для целевого пути
        Files.createDirectories(target.getParent());

        // Создаем временный файл для атомарной операции
        Path tempFile = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");

        try {
            // Копируем файл во временный файл
            Files.copy(source, tempFile, StandardCopyOption.REPLACE_EXISTING);

            // Атомарно перемещаем во временный файл (переименовывает)
            Files.move(tempFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

            log.debug("Файл скопирован (атомарно): {} -> {}", source, target);
        } catch (IOException e) {
            // Удаляем временный файл в случае ошибки
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException deleteException) {
                log.warn("Не удалось удалить временный файл при копировании: {}", tempFile, deleteException);
            }
            log.error("Ошибка копирования файла: {} -> {}, error={}", source, target, e.getMessage(), e);
            throw e;
        }
    }
}