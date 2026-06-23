package com.dentapinos.dataguard.dto;

import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.report.BackupReport;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * DTO для ответа на запрос экспорта/бэкапа.
 */
@Schema(description = "Ответ на запрос создания резервной копии")
public record ExportResponse(

        @Schema(
                description = "Имя созданной резервной копии",
                example = "center_beer_2024-06-15_02-30-00"
        )
        String backupName,

        @Schema(
                description = "Имя базы данных, для которой создана копия",
                example = "center_beer"
        )
        String database,

        @Schema(
                description = "Уровень копии (ежедневная, еженедельная и т.д.)",
                example = "DAILY"
        )
        BackupTier tier,

        @Schema(
                description = "Отчёт о выполнении операции экспорта"
        )
        BackupReport report
) {}
