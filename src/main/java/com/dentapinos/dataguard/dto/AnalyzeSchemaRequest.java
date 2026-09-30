package com.dentapinos.dataguard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Запрос на анализ совместимости схемы бэкапа.
 */
@Schema(description = "Запрос на анализ совместимости схемы резервной копии. Используется эндпоинтами /analyze-schema и /missing-columns-analysis.")
public record AnalyzeSchemaRequest(

        @Schema(
                description = "Имя ZIP-файла бэкапа в хранилище (например, backup-2024-06-15.zip)",
                example = "center_beer_2024-06-15_02-30-00.zip"
        )
        String backupName,

        @Schema(
                description = "Физическое имя целевой базы данных для проверки совместимости",
                example = "center_beer_production"
        )
        String targetDatabase
) {}
