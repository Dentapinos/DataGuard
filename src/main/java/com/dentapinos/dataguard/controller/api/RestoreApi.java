package com.dentapinos.dataguard.controller.api;


import com.dentapinos.dataguard.dto.AnalyzeSchemaRequest;
import com.dentapinos.dataguard.dto.MissingColumnsAnalysisDto;
import com.dentapinos.dataguard.dto.RestoreRequest;
import com.dentapinos.dataguard.dto.RestoreToNewDatabaseRequest;
import com.dentapinos.dataguard.dto.SchemaCompatibilityAnalysisDto;
import com.dentapinos.dataguard.enums.BackupTier;
import com.dentapinos.dataguard.report.RestoreReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(
        name = "Restore",
        description = "API для восстановления резервных копий MySQL. Восстановление в существующие и новые БД, анализ схемы, обработка недостающих колонок."
)
@RequestMapping("/api/restore")
public interface RestoreApi {

    @Operation(
            summary = "Восстановление в существующую базу данных",
            description = """
                    Загружает бэкап (ZIP-архив с JSON) и импортирует данные в указанную целевую базу данных.

                    Типичный рабочий процесс:
                    </br>1. Анализ схемы — сначала вызовите POST /{databaseName}/missing-columns-analysis
                    </br>2. Укажите дефолты — если есть недостающие колонки, заполните missingFields
                    </br>3. Восстановление — вызовите этот endpoint с выбранным режимом

                    Параметры:
                    </br>tier (query) — уровень хранения: DAILY, WEEKLY, MONTHLY
                    </br>targetDatabase (body) — логическое имя БД из конфигурации
                    </br>backupName (body) — имя ZIP-файла бэкапа
                    </br>mode (body) — режим восстановления (см. ниже)
                    </br>tables (body, опционально) — список таблиц для восстановления. Если null/пусто — все таблицы
                    </br>missingFields (body, опционально) — значения по умолчанию для недостающих колонок

                    Режимы восстановления:
                    </br><b>STRICT</b> — требует полного совпадения схемы. Бросает ошибку при любом расхождении.
                    </br><b>SAFE_MERGE</b> — мягкое слияние: пропускает дубликаты. Отключает внешние ключи.
                    </br><b>FORCE_REPLACE</b> — полная перезапись: обновляет существующие записи по первичному ключу.
                    </br><b>APPEND_ONLY</b> — только вставка новых строк. Существующие игнорируются.
                    </br><b>UPSERT_ALL</b> — объединяет вставку и обновление. Рекомендуется для большинства сценариев.
                    </br><b>DRY_RUN</b> — эмуляция без записи в БД. Используйте для тестирования.
                    </br><b>SAFE_SCHEMA_CHECK</b> — только проверка совместимости схемы. Данные не восстанавливаются.

                    Недостающие колонки: если в целевой БД есть колонки, которых нет в бэкапе, восстановление может не сработать для NOT NULL колонок без значений по умолчанию.

                    Решение:
                    </br>1. Вызовите POST /{databaseName}/missing-columns-analysis
                    </br>2. Скопируйте readyToUseMissingFields из ответа
                    </br>3. Заполните значения и передайте в missingFields
                    </br>4. Выполните восстановление

                    Функции-генераторы (начинаются с #):
                    </br><b>#uuid</b> — уникальный UUID v4 для каждой строки
                    </br><b>#uuid_nodash</b>  — UUID без дефисов
                    </br><b>#now</b>  — текущая дата и время (формат MySQL)
                    </br><b>#unix_timestamp</b>  — Unix timestamp
                    </br><b>#random_int</b>  — случайное целое число
                    </br><b>#random_long</b>  — случайное длинное число
                    </br><b>#random_bool</b>  — случайное boolean
                    </br><b>#random_pin_4</b>  — случайный PIN из 4 цифр
                    </br><b>#random_pin_6</b>  — случайный PIN из 6 цифр
                    </br><b>#random_string</b>  — случайная строка (8 символов)
                    </br><b>#random_string_16</b>  — случайная строка (16 символов)
                    </br><b>#random_string_32</b>  — случайная строка (32 символа)
                    </br><b>#random_email</b>  — случайный email адрес
                    </br><b>#random_name</b>  — случайное имя
                    </br><b>#random_ip</b>  — случайный IPv4 адрес
                    </br><b>#random_hash</b>  — случайный hex-хеш (32 символа)
                    </br><b>#random_hash_64</b>  — случайный hex-хеш (64 символа)

                    Возвращает: RestoreReport с деталями восстановления: статус, статистика по таблицам и строкам.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Восстановление успешно выполнено",
            content = @Content(schema = @Schema(implementation = RestoreReport.class))
    )
    @ApiResponse(responseCode = "404", description = "Логическая база данных не найдена")
    @ApiResponse(responseCode = "500", description = "Внутренняя ошибка при восстановлении")
    @PostMapping(consumes = "application/json", produces = "application/json")
    ResponseEntity<?> restoreToExistingDatabase(
            @Parameter(description = "Уровень хранения бэкапа (DAILY, WEEKLY, MONTHLY и т.п.)", example = "DAILY") @RequestParam BackupTier tier,
            @RequestBody RestoreRequest request
    );

    @Operation(
            summary = "Анализ совместимости схемы перед восстановлением",
            description = """
                    <p>Сравнивает схему бэкапа со схемой целевой БД. НЕ изменяет БД.</p>
                    <p><b>Когда использовать:</b> перед восстановлением для проверки совместимости, для отладки ошибок восстановления, для планирования синхронизации схем.</p>
                    <p><b>Возвращает:</b> <code>compatibleStrict</code> (true если схемы идентичны), <code>compatibleRelaxed</code> (true для RELAXED режима), <code>blockingIssuesStrict</code>, <code>warnings</code>.</p>
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Анализ успешно выполнен",
            content = @Content(schema = @Schema(implementation = SchemaCompatibilityAnalysisDto.class))
    )
    @ApiResponse(responseCode = "404", description = "Логическая база данных не найдена")
    @ApiResponse(responseCode = "500", description = "Ошибка при анализе схемы")
    @PostMapping(
            value = "/{databaseName}/analyze-schema",
            consumes = "application/json",
            produces = "application/json"
    )
    ResponseEntity<?> analyzeSchemaCompatibility(
            @Parameter(description = "Логическое имя базы данных из конфигурации", example = "production_db") @PathVariable String databaseName,
            @Parameter(description = "Уровень хранения бэкапа (DAILY, WEEKLY, MONTHLY и т.п.)", example = "DAILY") @RequestParam BackupTier tier,
            @RequestBody AnalyzeSchemaRequest request
    );

    @Operation(
            summary = "Восстановление в новую базу данных",
            description = """
                    Полностью пересоздаёт базу данных на основе бэкапа.

                    Что происходит:
                    </br>1. Старая база данных (если существует) полностью удаляется
                    </br>2. Создаётся чистая база данных
                    </br>3. Создаётся схема таблиц из бэкапа
                    </br>4. Восстанавливаются все данные из бэкапа

                    Важно:
                    </br>• База данных пересоздаётся с нуля — все текущие данные будут потеряны
                    </br>• Схема берётся из бэкапа — не требуется совпадения с текущей схемой
                    </br>• Восстанавливаются все таблицы из бэкапа (фильтрация недоступна)
                    </br>• Возвращает 409, если указанные учётные данные некорректны или нет прав на создание БД

                    Возвращает: RestoreReport с деталями восстановления.
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Восстановление успешно выполнено",
            content = @Content(schema = @Schema(implementation = RestoreReport.class))
    )
    @ApiResponse(responseCode = "409", description = "База данных уже существует")
    @ApiResponse(responseCode = "500", description = "Внутренняя ошибка при восстановлении")
    @PostMapping(
            value = "/new-database",
            consumes = "application/json",
            produces = "application/json"
    )
    ResponseEntity<?> restoreToNewDatabase(
            @Parameter(description = "Уровень хранения бэкапа (DAILY, WEEKLY, MONTHLY и т.п.)", example = "DAILY") @RequestParam(required = false) BackupTier tier,
            @RequestBody RestoreToNewDatabaseRequest request
    );

    @Operation(
            summary = "Анализ недостающих колонок и генерация дефолтов",
            description = """
                    <p>Находит колонки в целевой БД, которых нет в бэкапе. НЕ изменяет БД.</p>
                    <p><b>Зачем нужно:</b> если в таблицу БД добавились новые поля после создания бэкапа, восстановление может не сработать для NOT NULL колонок без значений по умолчанию.</p>
                    <p><b>Возвращает:</b></p>
                    <ul>
                      <li><code>missingColumns</code> — список недостающих колонок с подсказками</li>
                      <li><code>readyToUseMissingFields</code> — готовый шаблон для вставки в <code>missingFields</code> запроса восстановления</li>
                      <li><code>availableFunctions</code> — список функций-генераторов (#uuid, #now и т.д.)</li>
                      <li><code>userInstructions</code> — рекомендации по использованию</li>
                    </ul>
                    <p><b>Рабочий процесс:</b></p>
                    <ol>
                      <li>Вызовите этот endpoint с вашим бэкапом и целевой БД</li>
                      <li>Скопируйте <code>readyToUseMissingFields</code> из ответа</li>
                      <li>Вставьте в запрос восстановления как <code>missingFields</code></li>
                      <li>При необходимости замените плейсхолдеры (#uuid, #now) на свои значения</li>
                    </ol>
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Анализ выполнен успешно",
            content = @Content(schema = @Schema(implementation = MissingColumnsAnalysisDto.class))
    )
    @ApiResponse(responseCode = "404", description = "Логическая база данных не найдена")
    @ApiResponse(responseCode = "500", description = "Ошибка при анализе")
    @PostMapping(
            value = "/{databaseName}/missing-columns-analysis",
            consumes = "application/json",
            produces = "application/json"
    )
    ResponseEntity<?> analyzeMissingColumns(
            @Parameter(description = "Логическое имя базы данных из конфигурации", example = "production_db") @PathVariable String databaseName,
            @Parameter(description = "Уровень хранения бэкапа (DAILY, WEEKLY, MONTHLY и т.п.)", example = "DAILY") @RequestParam BackupTier tier,
            @RequestBody AnalyzeSchemaRequest request
    );
}
