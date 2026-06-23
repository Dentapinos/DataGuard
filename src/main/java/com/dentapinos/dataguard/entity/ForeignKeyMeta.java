package com.dentapinos.dataguard.entity;


import java.util.List;

/**
 * Метаданные внешнего ключа таблицы базы данных.
 * <p>
 * Описывает связь между текущей таблицей и другой таблицей (родительской):
 * какие столбцы текущей таблицы ссылаются на какие столбцы в другой таблице.
 *
 * <p>Пример: внешний ключ из таблицы {@code orders} на таблицу {@code users}:
 * <ul>
 *   <li>{@code name} — "fk_orders_user_id"</li>
 *   <li>{@code columnNames} — ["user_id"]</li>
 *   <li>{@code referencedTable} — "users"</li>
 *   <li>{@code referencedColumnNames} — ["id"]</li>
 * </ul>
 *
 * @param name                   имя внешнего ключа (как оно задано в БД),
 *                               например "fk_orders_user_id".
 * @param columnNames            список имён столбцов в текущей таблице,
 *                               которые участвуют во внешнем ключе
 *                               (для составного ключа — несколько имён).
 * @param referencedTable        имя таблицы, на которую ссылается внешний ключ
 *                               (родительская/целевой объект ссылки).
 * @param referencedColumnNames  список имён столбцов в ссылочной таблице,
 *                               с которыми связаны {@code columnNames};
 *                               порядок должен соответствовать {@code columnNames}.
 */
public record ForeignKeyMeta(
        String name,
        List<String> columnNames,
        String referencedTable,
        List<String> referencedColumnNames
) {}
