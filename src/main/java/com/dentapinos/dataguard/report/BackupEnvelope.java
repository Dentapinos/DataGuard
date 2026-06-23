package com.dentapinos.dataguard.report;

import com.dentapinos.dataguard.entity.storage.BackupFile;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Контейнер с отчетом о резервном копировании и информацией о файле резервной копии")
public record BackupEnvelope(
        @Schema(description = "Детали отчета о резервном копировании") BackupReport report,
        @Schema(description = "Сущность файла резервной копии") BackupFile backup
) {}
