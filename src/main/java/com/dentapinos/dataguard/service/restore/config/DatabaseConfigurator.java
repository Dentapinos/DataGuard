package com.dentapinos.dataguard.service.restore.config;

import com.dentapinos.dataguard.entity.RestorePolicy;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Интерфейс для настройки параметров базы данных до и после операций восстановления.
 * <p>
 * Различные реализации СУБД (MySQL, PostgreSQL и др.) могут предоставлять свою собственную
 * логику настройки параметров, таких как проверка внешних ключей, режимы транзакций и другие
 * специфичные для СУБД параметры, которые необходимо изменить во время восстановления.
 * </p>
 * @see RestorePolicy Политика восстановления, содержащая настройки конфигурации
 * @since 1.0
 */
public interface DatabaseConfigurator {

    /**
     * Настраивает параметры базы данных перед началом операции восстановления.
     * <p>
     * Типичные задачи:
     * <ul>
     *   <li>Отключение проверки внешних ключей (например, MySQL {@code SET FOREIGN_KEY_CHECKS = 0})</li>
     *   <li>Отключение триггеров</li>
     *   <li>Установка уровня изоляции транзакций</li>
     *   <li>Отключение проверки уникальности</li>
     * </ul>
     *
     * @param template JDBC-шаблон для выполнения SQL-запросов настройки
     * @param policy   политика восстановления, содержащая параметры конфигурации
     */
    void configureBeforeRestore(JdbcTemplate template, RestorePolicy policy);

    /**
     * Очищает и восстанавливает параметры базы данных после завершения операции восстановления.
     * <p>
     * Этот метод должен отменить все изменения, сделанные в методе
     * {@link #configureBeforeRestore(JdbcTemplate, RestorePolicy)}.
     * <p>
     * Типичные задачи:
     * <ul>
     *   <li>Включение проверки внешних ключей (например, MySQL {@code SET FOREIGN_KEY_CHECKS = 1})</li>
     *   <li>Включение триггеров</li>
     *   <li>Восстановление исходных настроек транзакций</li>
     *   <li>Включение проверки уникальности</li>
     * </ul>
     *
     * @param template JDBC-шаблон для выполнения SQL-запросов очистки
     * @param policy   политика восстановления, содержащая параметры конфигурации
     */
    void configureAfterRestore(JdbcTemplate template, RestorePolicy policy);
}
