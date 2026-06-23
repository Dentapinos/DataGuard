package com.dentapinos.dataguard.controller.api;


import com.dentapinos.dataguard.dto.AnalyzeSchemaRequest;
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
        description = "Восстановление резервных копий и анализ схемы"
)
@RequestMapping("/api/restore")
public interface RestoreApi {

    @Operation(
            summary = "🔄️ Восстановление в существующую базу данных",
            description = """
                    Запускает восстановление из указанного бэкапа в существующую целевую базу данных.
                    Логическое имя базы и физическое имя целевой базы передаются в теле запроса.
                    
                    <h3>Параметры запроса</h3>
                    <ul>
                      <li><strong>tier</strong> (query) — уровень хранения бэкапа (DAILY, WEEKLY, MONTHLY и т.п.)</li>
                    </ul>
                    
                    <h3>Параметры тела запроса (RestoreRequest)</h3>
                    <ul>
                      <li><strong>targetDatabase</strong> — логическое имя целевой базы из конфигурации</li>
                      <li><strong>backupName</strong> — имя файла бэкапа</li>
                      <li><strong>mode</strong> — режим восстановления (см. ниже)</li>
                      <li><strong>tables</strong> — список таблиц для восстановления (если null или пустой — все таблицы)</li>
                    </ul>
                    
                    <h3>Режимы восстановления</h3>
                    <table border="1" style="border-collapse: collapse; width: 100%;">
                      <thead>
                        <tr style="background-color: #f0f0f0;">
                          <th style="padding: 8px; text-align: left; white-space: nowrap;">Режим</th>
                          <th style="padding: 8px; text-align: left;">Тип</th>
                          <th style="padding: 8px; text-align: left;">Что делает</th>
                          <th style="padding: 8px; text-align: left;">Когда применять</th>
                        </tr>
                      </thead>
                      <tbody>
                        <tr>
                          <td style="padding: 8px; font-weight: bold;">STRICT</td>
                          <td style="padding: 8px;">⚡ Модификация</td>
                          <td style="padding: 8px;">Строгая проверка схемы и данных, ошибка на любом расхождении</td>
                          <td style="padding: 8px;">Полная гарантия совпадения схемы и данных</td>
                        </tr>
                        <tr>
                          <td style="padding: 8px; font-weight: bold;">SAFE_MERGE</td>
                          <td style="padding: 8px;">🌱 Модификация</td>
                          <td style="padding: 8px;">Мягкое слияние, пропуск дубликатов, FK отключаются</td>
                          <td style="padding: 8px;">Безопасное добавление данных без конфликтов</td>
                        </tr>
                        <tr>
                          <td style="padding: 8px; font-weight: bold;">FORCE_REPLACE</td>
                          <td style="padding: 8px;">💥 Модификация</td>
                          <td style="padding: 8px;">Агрессивная замена данных, дубликаты обновляются</td>
                          <td style="padding: 8px;">Полная перезапись данных из бэкапа</td>
                        </tr>
                        <tr>
                          <td style="padding: 8px; font-weight: bold;">APPEND_ONLY</td>
                          <td style="padding: 8px;">📝 Модификация</td>
                          <td style="padding: 8px;">Только добавление новых строк, существующие не трогаются</td>
                          <td style="padding: 8px;">Добавление новых данных без изменения существующих</td>
                        </tr>
                        <tr>
                          <td style="padding: 8px; font-weight: bold;">UPSERT_ALL</td>
                          <td style="padding: 8px;">🔄 Модификация</td>
                          <td style="padding: 8px;">Upsert-операции для всех строк, дубликаты обновляются</td>
                          <td style="padding: 8px;">Обновление существующих и добавление новых записей</td>
                        </tr>
                        <tr>
                          <td style="padding: 8px; font-weight: bold;">DRY_RUN</td>
                          <td style="padding: 8px;">👁️ Только просмотр</td>
                          <td style="padding: 8px;">Эмуляция восстановления без записи в БД</td>
                          <td style="padding: 8px;">Тестирование процесса без реальных изменений</td>
                        </tr>
                        <tr>
                          <td style="padding: 8px; font-weight: bold;">SAFE_SCHEMA_CHECK</td>
                          <td style="padding: 8px;">👁️ Только просмотр</td>
                          <td style="padding: 8px;">Только проверка совместимости схемы без импорта</td>
                          <td style="padding: 8px;">Проверка совместимости перед восстановлением</td>
                        </tr>
                      </tbody>
                    </table>
                    
                    <h3>Фильтрация таблиц</h3>
                    <p>Поле <code>tables</code> в запросе позволяет указать список таблиц для восстановления:
                    <ul>
                      <li><strong>Если <code>tables = null</code> или пустой список</strong> — восстанавливаются все таблицы из бэкапа</li>
                      <li><strong>Если указан список таблиц</strong> — восстанавливаются только указанные таблицы</li>
                    </ul></p>
                    
                    <h3>Пример запроса</h3>
                    <pre>
                    {
                      "targetDatabase": "main_db",
                      "backupName": "backup_20240614_120000.zip",
                      "mode": "SAFE_MERGE",
                      "tables": ["users", "orders"]
                    }
                    </pre>
                    
                    <h3>Возвращает</h3>
                    <ul>
                      <li>RestoreReport с деталями выполненного восстановления</li>
                    </ul>
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
            summary = "🧩🧩🔍 Анализ совместимости схемы перед восстановлением",
            description = """
                    Выполняет предварительный анализ совместимости схемы целевой базы данных
                    с выбранным бэкапом. Ничего в целевой БД не изменяет.
                    
                    <h3>Параметры запроса</h3>
                    <ul>
                      <li><strong>databaseName</strong> (path) — логическое имя целевой базы из конфигурации</li>
                      <li><strong>tier</strong> (query) — уровень хранения бэкапа (DAILY, WEEKLY, MONTHLY и т.п.)</li>
                    </ul>
                    
                    <h3>Параметры тела запроса (AnalyzeSchemaRequest)</h3>
                    <ul>
                      <li><strong>backupName</strong> — имя файла бэкапа для анализа</li>
                      <li><strong>targetDatabase</strong> — физическое имя целевой базы данных</li>
                    </ul>
                    
                    <h3>Пример запроса</h3>
                    <pre>
                    {
                      "backupName": "backup_20240614_120000.zip",
                      "targetDatabase": "production_db"
                    }
                    </pre>
                    
                    <h3>Возвращает</h3>
                    <ul>
                      <li>SchemaCompatibilityAnalysisDto с результатами анализа:</li>
                      <ul>
                        <li>Список отсутствующих таблиц</li>
                        <li>Список несовпадающих схем</li>
                        <li>Общую совместимость (COMPATIBLE/INCOMPATIBLE)</li>
                      </ul>
                    </ul>
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
            summary = "🌱🗄️ Восстановление в новую базу данных",
            description = """
                    Создаёт новую базу данных и восстанавливает в неё данные из бэкапа.
                    Требует передачи полных учетных данных для новой базы.
                    
                    <h3>Параметры запроса</h3>
                    <ul>
                      <li><strong>tier</strong> (query) — уровень хранения бэкапа (DAILY, WEEKLY, MONTHLY и т.п.)
                      (если не указан, уровень будет определен автоматически)</li>
                    </ul>
                    
                    <h3>Параметры тела запроса (RestoreToNewDatabaseRequest)</h3>
                    <ul>
                      <li><strong>backupName</strong> — имя файла бэкапа для восстановления</li>
                      <li><strong>newDatabaseName</strong> — имя создаваемой базы данных</li>
                      <li><strong>newDatabaseCredentials</strong> — учетные данные для новой базы:</li>
                      <ul>
                        <li><strong>url</strong> — JDBC URL новой базы</li>
                        <li><strong>username</strong> — имя пользователя</li>
                        <li><strong>password</strong> — пароль</li>
                      </ul>
                    </ul>
                    
                    <h3>Пример запроса</h3>
                    <pre>
                    {
                      "backupName": "backup_20240614_120000.zip",
                      "newDatabaseName": "restored_db",
                      "newDatabaseCredentials": {
                        "url": "jdbc:mysql://localhost:3306/restored_db",
                        "username": "root",
                        "password": "password123"
                      }
                    }
                    </pre>
                    
                    <h3>Возвращает</h3>
                    <ul>
                      <li>RestoreReport с деталями выполненного восстановления</li>
                    </ul>
                    
                    <h3>Важно</h3>
                    <ul>
                      <li>Если база с таким именем уже существует — будет ошибка</li>
                      <li>Восстановление выполняется в режиме STRICT (строго)</li>
                      <li>Восстанавливаются все таблицы из бэкапа</li>
                    </ul>
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
}
