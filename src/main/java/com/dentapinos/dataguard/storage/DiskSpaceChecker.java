package com.dentapinos.dataguard.storage;

import com.dentapinos.dataguard.config.BackupProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.FileStore;
import java.nio.file.Path;

/**
 * Сервис проверки заполненности диска.
 * <p>
 * Определяет, нужно ли приостановить бэкапы из-за нехватки места.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiskSpaceChecker {

    private final BackupProperties backupProperties;

    /**
     * Проверяет, заполнен ли диск выше порога.
     *
     * @return true если диск заполнен больше порога — бэкапы нужно приостановить
     */
    public boolean isDiskFull() {
        String basePath = backupProperties.getFileSystem().getBasePath();
        Path path = Path.of(basePath);

        if (!Files.exists(path)) {
            log.warn("[DISK_SPACE] Путь не существует: {}, пропускаем проверку", basePath);
            return false;
        }

        try {
            FileStore fileStore = Files.getFileStore(path);
            long totalSpace = fileStore.getTotalSpace();
            long usableSpace = fileStore.getUsableSpace();
            long usedSpace = totalSpace - usableSpace;

            if (totalSpace == 0) {
                log.warn("[DISK_SPACE] Общий размер диска = 0, пропускаем проверку");
                return false;
            }

            int usagePercent = (int) ((usedSpace * 100) / totalSpace);
            int threshold = backupProperties.getDiskFullThresholdPercent();

            boolean isFull = usagePercent >= threshold;

            log.info("[DISK_SPACE] заполнено: {}%, порог: {}%, диск полон: {}",
                    usagePercent, threshold, isFull);

            return isFull;
        } catch (Exception e) {
            log.warn("[DISK_SPACE] ошибка проверки диска: {}", e.getMessage());
            return false;
        }
    }
}
