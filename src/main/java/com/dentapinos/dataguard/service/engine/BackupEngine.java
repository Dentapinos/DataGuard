package com.dentapinos.dataguard.service.engine;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.report.BackupEnvelope;

import java.util.List;

/**
 * Интерфейс ядра резервного копирования баз данных.
 * <p>
 * Определяет контракт для выполнения операций резервного копирования
 * различных типов баз данных (MySQL, PostgreSQL и т.д.).
 * </p>
 *
 * @see DbCredentials Данные для подключения к базе данных
 * @see BackupEnvelope Результат операции резервного копирования (файл, метаданные)
 */
public interface BackupEngine {

    /**
     * Создаёт резервную копию указанной базы данных.
     * <p>
     * Метод выполняет экспорт данных из указанной базы данных с использованием
     * предоставленных учётных данных и сохраняет результат в виде файла резервной копии.
     * </p>
     *
     * @param credentials        Учётные данные для подключения к базе данных
     * @param database           Имя базы данных, которую необходимо скопировать
     * @param tablesToInclude    Список таблиц для включения в резервную копию.
     *                           Если {@code null} или пустой список — включаются все таблицы.
     * @return {@link BackupEnvelope} с информацией о созданной резервной копии:
     *         путь к файлу, время создания, размер и метаданные операции.
     * @throws IllegalStateException если произошла ошибка при создании резервной копии.
     *                               Подробная информация об ошибке может быть предоставлена
     *                               в текстовом сообщении исключения.
     */
    BackupEnvelope backup(DbCredentials credentials,
                          String database,
                          List<String> tablesToInclude);
}
