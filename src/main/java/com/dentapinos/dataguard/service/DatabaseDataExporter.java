package com.dentapinos.dataguard.service;

import com.dentapinos.dataguard.dto.DbCredentials;
import com.dentapinos.dataguard.entity.ExportStats;
import com.dentapinos.dataguard.entity.SchemaMeta;

import java.util.List;
import java.util.Map;

/**
 * Интерфейс для экспорта данных из базы данных.
 * <p>
 * Определяет контракт для извлечения данных из таблиц базы данных в виде
 * коллекции строк (записей), где каждая строка представлена как Map
 * (ключ — имя колонки, значение — значение колонки).
 * </p>
 *
 * @see DbCredentials Учётные данные для подключения к базе данных
 * @see SchemaMeta Метаданные схемы базы данных (структура таблиц)
 * @see ExportStats Статистика операции экспорта (количество строк, времени и т.д.)
 */
public interface DatabaseDataExporter {

    /**
     * Экспортирует данные из указанных таблиц базы данных.
     * <p>
     * Метод извлекает содержимое таблиц, перечисленных в {@code tableOrder}.
     * Порядок таблиц важен при импорте, чтобы избежать ошибок внешних ключей.
     * </p>
     *
     * @param credentials  Учётные данные для подключения к базе данных
     * @param schema       Метаданные схемы базы данных, содержащие информацию о таблицах
     * @param tableOrder   Список имен таблиц в порядке экспорта (важен для восстановления зависимостей)
     * @param stats        Объект статистики, который будет обновлён в процессе экспорта
     *                     (количество экспортированных строк, времени и других метрик)
     * @return {@link Map<> } карта, где ключ — имя таблицы,
     *         значение — список строк (записей), каждая из которых представлена как Map
     *         (ключ — имя колонки, значение — значение колонки).
     * @throws IllegalStateException если произошла ошибка при экспорте данных.
     *                               Подробная информация об ошибке может быть предоставлена
     *                               в текстовом сообщении исключения.
     */
    Map<String, List<Map<String, Object>>> exportData(
            DbCredentials credentials,
            SchemaMeta schema,
            List<String> tableOrder,
            ExportStats stats
    );
}
