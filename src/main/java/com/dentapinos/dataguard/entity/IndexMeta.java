package com.dentapinos.dataguard.entity;

import java.util.List;

/**
 * Метаданные индекса таблицы базы данных.
 * <p>
 * Описывает один индекс: его имя, тип (уникальный или нет) и список столбцов,
 * по которым он построен. Используется при восстановлении/переносе схемы БД
 * из бэкапа.
 *
 * <p>Пример: уникальный индекс по полю {@code email} в таблице {@code users}:
 * <ul>
 *   <li>{@code name} — "idx_users_email"</li>
 *   <li>{@code unique} — true</li>
 *   <li>{@code columns} — ["email"]</li>
 * </ul>
 *
 * @param name    имя индекса в БД (например, "idx_users_email" или "PRIMARY"
 *                для первичного ключа в некоторых СУБД).
 * @param unique  признак уникальности индекса:
 *                {@code true} — индекс гарантирует уникальность значений
 *                по указанным столбцам; {@code false} — обычный (неуникальный) индекс.
 * @param columns список имён столбцов, входящих в индекс, в порядке,
 *                в котором они определены в БД (важно для составных индексов).
 */
public record IndexMeta(
        String name,
        boolean unique,
        List<String> columns
) {}

