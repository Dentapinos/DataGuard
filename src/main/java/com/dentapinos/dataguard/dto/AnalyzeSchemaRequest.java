package com.dentapinos.dataguard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO для запроса анализа совместимости схемы бэкапа и целевой БД.
 */
@Schema(description = "Запрос на анализ совместимости схемы резервной копии с целевой базой данных")
public record AnalyzeSchemaRequest(

        @Schema(
                description = "Имя (имя файла) резервной копии для анализа",
                example = "center_beer_2024-06-15_02-30-00.zip"
        )
        String backupName,

        @Schema(
                description = "Имя целевой базы данных для проверки совместимости",
                example = "center_beer_production"
        )
        String targetDatabase
) {}
