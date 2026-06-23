package com.dentapinos.dataguard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO для информации о базе данных.
 */
@Schema(description = "Информация о базе данных (имя и отображаемое имя)")
public record DatabaseInfo(

        @Schema(
                description = "Имя базы данных в СУБД",
                example = "center_beer"
        )
        String databaseName,

        @Schema(
                description = "Отображаемое (человекочитаемое) имя базы данных",
                example = "Center Beer Production"
        )
        String displayName
) {}
