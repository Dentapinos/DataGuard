package com.dentapinos.dataguard;


import com.dentapinos.dataguard.config.BackupDatabasesProperties;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.storage.BackupRetentionManager;
import com.dentapinos.dataguard.storage.DiskSpaceChecker;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Главный класс приложения.
 * <p>
 * Отключает автоматическую конфигурацию DataSource и JPA для production/local профилей.
 * Для работы с базой данных в тестах используется TestDatabaseConfig.
 * </p>
 */
@SpringBootApplication(
        exclude = {
                DataSourceAutoConfiguration.class
        }
)
@EnableScheduling
@ConfigurationPropertiesScan(basePackages = "com.dentapinos.dataguard.config")
@Slf4j
@RequiredArgsConstructor
public class DataGuardApplication {

    private final BackupRetentionManager backupRetentionManager;
    private final BackupDatabasesProperties backupDatabasesProperties;
    private final DiskSpaceChecker diskSpaceChecker;

    public static void main(String[] args) {
        SpringApplication.run(DataGuardApplication.class, args);
    }

    @PostConstruct
    void checkStartupDiskSpace() {
        if (diskSpaceChecker.isDiskFull()) {
            log.warn("⚠️ ПРЕДУПРЕЖДЕНИЕ: диск заполнен — бэкапы будут приостановлены. " +
                    "Необходимо увеличить место хранения или очистить старые бэкапы.");
        }
    }

    /**
     * Запускает чистку по политике хранения при старте приложения.
     */
    @Bean
    CommandLineRunner startupCleanup() {
        return args -> runStartupCleanup();
    }

    private void runStartupCleanup() {
        log.info("[STARTUP_CLEANUP] Применение политики хранения при запуске");

        int success = 0;
        int failed = 0;

        for (var db : backupDatabasesProperties.getDatabases()) {
            for (BackupTier tier : BackupTier.values()) {
                try {
                    backupRetentionManager.applyRetention(tier, db.getDatabaseName());
                    success++;
                } catch (Exception e) {
                    log.error("[STARTUP_CLEANUP] ошибка очистки: db={}, tier={}",
                            db.getDatabaseName(), tier, e);
                    failed++;
                }
            }
        }

        log.info("[STARTUP_CLEANUP] Завершено. успешно: {}, ошибок: {}", success, failed);
    }

}