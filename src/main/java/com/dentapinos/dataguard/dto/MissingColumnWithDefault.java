package com.dentapinos.dataguard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO для информации о недостающей колонке и подсказки для дефолтного значения.
 * Возвращается в ответе на <code>POST /{databaseName}/missing-columns-analysis</code>.
 */
@Schema(description = "Информация о недостающей колонке с подсказкой для дефолтного значения")
public record MissingColumnWithDefault(

        @Schema(
                description = "Имя таблицы, в которой отсутствует колонка",
                example = "users"
        )
        String tableName,

        @Schema(
                description = "Имя отсутствующей колонки в таблице",
                example = "uuid"
        )
        String columnName,

        @Schema(
                description = "Тип колонки в целевой БД (например, varchar(255), datetime(6), enum('ACTIVATED','EXPIRED'))",
                example = "varchar(255)"
        )
        String columnType,

        @Schema(
                description = "Может ли колонка содержать NULL: true — можно указать NULL, false — необходимо значение",
                example = "false"
        )
        boolean nullable,

        @Schema(
                description = "Подсказка для пользователя — какое значение по умолчанию указать. Рекомендуется использовать функции-генераторы (#uuid, #now) для NOT NULL колонок.",
                example = "Колонка 'uuid' типа varchar(255), NOT NULL. Необходимо указать значение по умолчанию для этой колонки. (таблица: users)"
        )
        String defaultValueHint
) {}
