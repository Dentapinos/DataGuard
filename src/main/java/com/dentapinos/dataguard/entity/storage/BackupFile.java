package com.dentapinos.dataguard.entity.storage;

import com.dentapinos.dataguard.entity.SchemaMeta;

import java.util.List;
import java.util.Map;

/**
 * Представляет собой полный бэкап базы данных:
 * схема (описание таблиц) + данные таблиц.
 *
 * <p>Используется как внутренняя модель для:
 * <ul>
 *   <li>экспорта — заполнение полей из живой БД и сохранение в файл/JSON;</li>
 *   <li>импорта — чтение из файла/JSON и восстановление структуры и данных.</li>
 * </ul>
 *
 * @param database    имя базы данных, для которой создан бэкап (например, "center_beer")
 * @param engine      тип/движок СУБД или формат бэкапа (например, "mysql", "mysql-8.0")
 * @param schema      метаданные схемы (таблицы, столбцы, типы, ключи и т.п.)
 * @param data        данные таблиц: ключ — имя таблицы,
 *                    значение — список строк; каждая строка — мапа "имя столбца" → "значение"
 * @param tableOrder  порядок таблиц для восстановления (важен при наличии внешних ключей)
 */
public record BackupFile(
        String database,
        String engine,
        SchemaMeta schema,
        Map<String, List<Map<String, Object>>> data,
        List<String> tableOrder
) {}
