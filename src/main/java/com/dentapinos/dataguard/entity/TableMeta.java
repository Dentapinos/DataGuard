package com.dentapinos.dataguard.entity;

import java.util.List;

/**
 * Метаданные одной таблицы базы данных.
 * <p>
 * Описывает структуру таблицы: её имя, столбцы, первичный ключ,
 * внешние ключи и индексы. Служит основой для восстановления таблицы
 * при импорте бэкапа.
 *
 * <p>Основные составляющие:
 * <ul>
 *   <li>{@code name} — имя таблицы;</li>
 *   <li>{@code columns} — список столбцов с типами и атрибутами;</li>
 *   <li>{@code primaryKey} — список столбцов, образующих первичный ключ;</li>
 *   <li>{@code foreignKeys} — описания всех внешних ключей таблицы;</li>
 *   <li>{@code indexes} — индексы (уникальные и обычные) по столбцам таблицы.</li>
 * </ul>
 *
 * @param name        имя таблицы в базе данных (например, {@code "users"}, {@code "orders"}).
 * @param columns     список метаданных столбцов таблицы ({@link ColumnMeta}),
 *                    включая имена, типы, признак null/NOT NULL и автоинкремент.
 * @param primaryKey  список имён столбцов, входящих в первичный ключ.
 *                    Для простого ключа — один элемент, для составного — несколько.
 * @param foreignKeys список внешних ключей таблицы ({@link ForeignKeyMeta}),
 *                    описывающих связи с другими таблицами.
 * @param indexes     список индексов ({@link IndexMeta}), определённых для таблицы,
 *                    включая уникальные и обычные индексы.
 */
public record TableMeta(
        String name,
        List<ColumnMeta> columns,
        List<String> primaryKey,
        List<ForeignKeyMeta> foreignKeys,
        List<IndexMeta> indexes
) {}