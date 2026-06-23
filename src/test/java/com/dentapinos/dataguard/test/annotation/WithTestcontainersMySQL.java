package com.dentapinos.dataguard.test.annotation;

import com.dentapinos.dataguard.test.config.TestDatabaseConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Базовый класс для интеграционных тестов с Testcontainers MySQL.
 * <p>
 * Используйте эту аннотацию для любого теста, который должен работать
 * с реальной MySQL базой данных в Docker контейнере.
 * </p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseConfig.class)
public @interface WithTestcontainersMySQL {
}
