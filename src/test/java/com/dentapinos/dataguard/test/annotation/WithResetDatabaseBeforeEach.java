package com.dentapinos.dataguard.test.annotation;

import com.dentapinos.dataguard.test.config.TestDatabaseConfig;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.lang.annotation.*;

/**
 * Аннотация для тестов, которые требуют полной пересоздания базы данных перед каждым тестом.
 * 
 * После каждой очистки схемы БД происходит её полное восстановление через schema.sql.
 * Это обеспечивает идеальную изоляцию тестов и предотвращает проблемы с FK-ограничениями.
 * 
 * Используйте вместо @WithTestcontainersMySQL для тестов, которые модифицируют структуру БД
 * (например, добавляют/удаляют таблицы или колонки).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@ActiveProfiles("test")
@Import(TestDatabaseConfig.class)
public @interface WithResetDatabaseBeforeEach {
}
