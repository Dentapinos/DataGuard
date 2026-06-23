package com.dentapinos.dataguard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * DTO для запроса бэкапа выбранных таблиц.
 */
@Schema(description = "Запрос на создание резервной копии указанных таблиц базы данных")
public record BackupTablesRequest(

        @Schema(
                description = "Список имён таблиц для резервного копирования",
                example = "[\"users\", \"orders\", \"products\"]"
        )
        List<String> tables
) {}
