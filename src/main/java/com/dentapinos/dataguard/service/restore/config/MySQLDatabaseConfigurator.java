package com.dentapinos.dataguard.service.restore.config;

import com.dentapinos.dataguard.entity.RestorePolicy;
import com.dentapinos.dataguard.enums.policy.ForeignKeyPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Конфигуратор базы данных MySQL для операций восстановления.
 * <p>
 * Обрабатывает параметры, специфичные для MySQL, включая проверку внешних ключей.
 * </p>
 * <p>
 * Реализация:
 * <ul>
 *   <li>{@link ForeignKeyPolicy#TEMP_DISABLE}: Отключает проверку внешних ключей
 *       ({@code SET FOREIGN_KEY_CHECKS = 0}) перед восстановлением и включает
 *       обратно ({@code SET FOREIGN_KEY_CHECKS = 1}) после завершения</li>
 *   <li>{@link ForeignKeyPolicy#ENFORCE_ALL}: Не выполняет дополнительной настройки
 *       (используется стандартное поведение MySQL)</li>
 *   <li>{@link ForeignKeyPolicy#SKIP_VIOLATIONS}: Временно отключает проверку внешних ключей
 *       для согласованности со стратегией SAFE_MERGE, полагаясь на SKIP_ON_CONFLICT
 *       для обработки конфликтов на уровне строк. <strong>Примечание:</strong> В текущей
 *       реализации MySQL для этой политики поведение совпадает с ENFORCE_ALL. В будущем
 *       возможна доработка при необходимости специфичной логики MySQL.</li>
 * </ul>
 */
@Component
@Slf4j
public class MySQLDatabaseConfigurator implements DatabaseConfigurator {

    @Override
    public void configureBeforeRestore(JdbcTemplate template, RestorePolicy policy) {
        ForeignKeyPolicy fkPolicy = policy.foreignKeyPolicy();
        
        switch (fkPolicy) {
            case TEMP_DISABLE -> {
                log.debug("[DB_CONFIG] Отключение проверки внешних ключей для MySQL");
                template.execute("SET FOREIGN_KEY_CHECKS = 0");
            }
            case ENFORCE_ALL -> // Стандартное поведение MySQL — ничего не делаем
                    log.debug("[DB_CONFIG] Проверка внешних ключей включена (стандартное поведение MySQL)");
            case SKIP_VIOLATIONS -> {
                // В MySQL нет нативного способа пропускать нарушения FK во время вставки
                // Для согласованности со стратегией SAFE_MERGE отключаем FK checks
                // и полагаемся на SKIP_ON_CONFLICT для обработки конфликтов на уровне строк
                log.debug("[DB_CONFIG] Политика SKIP_VIOLATIONS — временно отключаем проверку внешних ключей для MySQL");
                template.execute("SET FOREIGN_KEY_CHECKS = 0");
            }
            default -> log.warn("[DB_CONFIG] Неизвестная политика ForeignKeyPolicy: {}, используется стандартное поведение", fkPolicy);
        }
    }

    @Override
    public void configureAfterRestore(JdbcTemplate template, RestorePolicy policy) {
        ForeignKeyPolicy fkPolicy = policy.foreignKeyPolicy();
        
        switch (fkPolicy) {
            case TEMP_DISABLE, SKIP_VIOLATIONS -> {
                // Включаем проверку FK для обеих политик: TEMP_DISABLE и SKIP_VIOLATIONS
                log.debug("[DB_CONFIG] Включение проверки внешних ключей для MySQL");
                template.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
            case ENFORCE_ALL -> // Стандартное поведение MySQL — ничего не восстанавливаем
                    log.debug("[DB_CONFIG] Проверка внешних ключей остаётся включённой (стандартное поведение MySQL)");
            default -> log.warn("[DB_CONFIG] Неизвестная политика ForeignKeyPolicy после восстановления: {}", fkPolicy);
        }
    }
}
