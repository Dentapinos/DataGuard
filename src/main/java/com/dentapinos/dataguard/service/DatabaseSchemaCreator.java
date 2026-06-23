package com.dentapinos.dataguard.service;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.SchemaMeta;

/**
 * Интерфейс для создания баз данных и таблиц.
 * <p>
 * Предоставляет методы для:
 * <ul>
 *   <li>Создания базы данных, если она не существует (с или без исключения при наличии)</li>
 *   <li>Создания таблиц на основе метаданных схемы</li>
 * </ul>
 * </p>
 * @see DbCredentials Учётные данные для подключения к базе данных
 * @see SchemaMeta Метаданные схемы базы данных (структура таблиц)
 */
public interface DatabaseSchemaCreator {

    /**
     * Создаёт базу данных, если она не существует.
     * <p>
     * Если база данных уже существует, выбрасывает исключение {@link IllegalStateException}.
     * </p>
     *
     * @param credentials Учётные данные для подключения к серверу баз данных
     * @param dbName      Имя создаваемой базы данных
     * @throws IllegalStateException если база данных с указанным именем уже существует
     */
    void createDatabaseIfNotExistsElseException(DbCredentials credentials, String dbName);

    /**
     * Создаёт базу данных, если она не существует (без исключения, если уже существует).
     * <p>
     * Если база данных уже существует, метод завершается успешно без действий и без выброса исключения.
     * </p>
     *
     * @param credentials Учётные данные для подключения к серверу баз данных
     * @param dbName      Имя создаваемой базы данных
     */
    void createDatabaseIfNotExists(DbCredentials credentials, String dbName);

    /**
     * Создаёт таблицы в указанной базе данных на основе метаданных схемы.
     * <p>
     * Метод создаёт таблицы, индексы и ограничения, определённые в объекте {@link SchemaMeta}.
     * Таблицы создаются в правильном порядке с учётом внешних ключей, чтобы избежать ошибок.
     * </p>
     *
     * @param credentials Учётные данные для подключения к серверу баз данных
     * @param dbName      Имя целевой базы данных для создания таблиц
     * @param schema      Метаданные схемы, содержащие информацию о структуре таблиц
     * @throws IllegalStateException если произошла ошибка при создании таблиц (например, конфликт имён,
     *                               недостаточные привилегии и т.д.)
     */
    void createTables(DbCredentials credentials, String dbName, SchemaMeta schema);
}
