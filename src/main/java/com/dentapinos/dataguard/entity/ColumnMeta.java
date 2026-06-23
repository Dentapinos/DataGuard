package com.dentapinos.dataguard.entity;

/**
 * Метаданные одного столбца таблицы базы данных.
 * <p>
 * Используется для описания структуры таблиц при создании/чтении бэкапа:
 * хранит имя столбца, его тип и некоторые важные свойства (nullable, автоинкремент).
 *
 * @param name          имя столбца в таблице (например, "id", "username", "created_at").
 * @param type          строковое представление типа столбца так, как его возвращает СУБД
 *                      (например, "bigint(20)", "varchar(255)", "double").
 * @param nullable      признак того, может ли столбец содержать значение NULL
 *                      (true — допускается NULL, false — значение обязательно).
 * @param autoIncrement признак автоинкремента: true, если значения генерируются СУБД
 *                      автоматически (обычно для первичных ключей), иначе false.
 */
public record ColumnMeta(
        String name,
        String type,
        boolean nullable,
        boolean autoIncrement
) {}
