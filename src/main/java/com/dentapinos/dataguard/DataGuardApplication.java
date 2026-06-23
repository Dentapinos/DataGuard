package com.dentapinos.dataguard;


import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
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
public class DataGuardApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataGuardApplication.class, args);
    }

}