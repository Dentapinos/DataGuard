package com.dentapinos.dataguard.dto;

import com.dentapinos.dataguard.enums.RestoreMode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Запрос на восстановление в существующую базу данных.
 */
@Schema(name = "RestoreRequest - Запрос на восстановление данных в существующую базу",
        description = "Запрос на восстановление данных из резервной копии в существующую базу данных"
)
public record RestoreRequest(

        @Schema(
                description = "Имя (название) резервной копии для восстановления",
                example = "center_beer_2024-06-15_02-30-00"
        )
        String backupName,

        @Schema(
                description = "Имя целевой базы данных для восстановления",
                example = "center_beer_production"
        )
        String targetDatabase,

        @Schema(
                description = "Режим восстановления данных",
                example = "STRICT"
        )
        RestoreMode mode,

        @Schema(
                description = "Список имён таблиц для восстановления (если null или пустой — восстанавливаются все таблицы из бэкапа)",
                example = "[\"users\", \"orders\"]"
        )
        List<String> tables
) {}
