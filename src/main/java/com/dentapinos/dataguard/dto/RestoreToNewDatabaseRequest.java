package com.dentapinos.dataguard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Запрос на восстановление в новую базу данных.
 */
@Schema(description = "Запрос на восстановление данных из резервной копии в новую базу данных")
public record RestoreToNewDatabaseRequest(

        @Schema(
                description = "Имя (название) резервной копии для восстановления",
                example = "center_beer_2024-06-15_02-30-00"
        )
        String backupName,

        @Schema(
                description = "Имя новой базы данных для восстановления"
        )
        String newDatabaseName,

        @Schema(
                description = "Параметры подключения к новой базе данных"
        )
        DbCredentials newDatabaseCredentials
) {}
