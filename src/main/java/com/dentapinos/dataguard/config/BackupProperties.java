package com.dentapinos.dataguard.config;

import com.dentapinos.dataguard.enums.StorageType;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Общие настройки механизма бэкапов.
 * <p>Читает значения из конфигурации по префиксу "backup".</p>
 * 
 * @see <a href="https://docs.spring.io/spring-boot/docs/current/reference/html/features.html#features.external-config.typesafe-configuration-properties">Spring Boot Configuration Properties</a>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "backup")
@Validated
public class BackupProperties {

    /** Тип хранилища бэкапов (локальная ФС, S3 и т.п.) */
    private StorageType storageType = StorageType.FILE_SYSTEM;

    /** Настройки для хранения бэкапов в файловой системе. */
    @Valid
    private FileSystemStorageProperties fileSystem = new FileSystemStorageProperties();

    /**
     * Нужно ли писать JSON с отступами (pretty-print).
     */
    private boolean formattedJson = true;
}