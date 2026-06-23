package com.dentapinos.dataguard.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Настройки для хранения бэкапов в файловой системе.
 */
@Data
@ConfigurationProperties(prefix = "backup.file-system")
@Validated
public class FileSystemStorageProperties {
    /**
     * Базовый каталог для хранения бэкапов.
     * Например: /var/backups/centerbeer или ./backups
     */
    @NotBlank(message = "basePath must be specified")
    private String basePath;

    /**
     * Получить абсолютный путь к basePath.
     * 
     * @return абсолютный путь к каталогу хранения бэкапов
     */
    public String getAbsolutePath() {
        return basePath;
    }
}
