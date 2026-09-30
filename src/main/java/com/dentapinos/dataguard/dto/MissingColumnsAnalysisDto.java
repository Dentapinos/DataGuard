package com.dentapinos.dataguard.dto;

import com.dentapinos.dataguard.service.restore.DefaultFunctionResolver;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Результат анализа недостающих колонок с подсказками для дефолтных значений.
 * <p>
 * Используется для предварительного анализа перед восстановлением, когда в целевой БД
 * есть колонки, которых нет в бэкапе. Помогает подготовить значения для поля <code>missingFields</code>.
 * </p>
 * <p>
 * <b>Типичный рабочий процесс:</b>
 * <ol>
 *   <li>Вызовите <code>POST /{databaseName}/missing-columns-analysis</code></li>
 *   <li>Скопируйте <code>readyToUseMissingFields</code> из ответа</li>
 *   <li>Вставьте в запрос восстановления как <code>missingFields</code></li>
 * </ol>
 * </p>
 */
@Schema(description = "Результат анализа недостающих колонок с подсказками и готовым шаблоном для восстановления")
public record MissingColumnsAnalysisDto(

        @Schema(
                description = "Список недостающих колонок по таблицам. Каждая колонка содержит тип, признак nullable и подсказку.",
                example = "[{\"tableName\": \"users\", \"columnName\": \"new_field\", \"columnType\": \"varchar(255)\", \"nullable\": false, \"defaultValueHint\": \"...\"}]"
        )
        List<MissingColumnWithDefault> missingColumns,

        @Schema(
                description = "Количество таблиц с недостающими колонками",
                example = "3"
        )
        int tableCount,

        @Schema(
                description = "Общее количество недостающих колонок",
                example = "5"
        )
        int totalMissingColumns,

        @Schema(
                description = "Инструкция для пользователя — как заполнить missingFields перед восстановлением",
                example = "Укажите значения по умолчанию для каждой колонки. Используйте #uuid, #now и другие функции-генераторы."
        )
        String userInstructions,

        @Schema(
                description = "<b>Готовый шаблон missingFields.</b> Скопируйте этот объект и вставьте в <code>missingFields</code> запроса восстановления. Замените пустые значения на свои. Используйте функции-генераторы (#uuid, #now) для автоматической генерации.",
                example = "{\"_user\": {\"uuid\": \"#uuid\", \"last_code_sent_at\": \"#now\", \"status\": \"ACTIVATED\"}}"
        )
        Map<String, Map<String, Object>> readyToUseMissingFields,

        @Schema(
                description = "Список доступных функций-генераторов для автоматического заполнения. Используйте синтаксис <code>#function_name</code> в значениях полей.",
                example = "[{\"name\": \"uuid\", \"description\": \"Генерирует UUID v4\", \"exampleValue\": \"550e8400-e29b-...\", \"returnType\": \"string\"}]"
        )
        List<DefaultFunctionResolver.DefaultFunctionInfo> availableFunctions
) {}
