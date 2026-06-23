package com.dentapinos.dataguard.controller.api;


import com.dentapinos.dataguard.dto.BackupTablesRequest;
import com.dentapinos.dataguard.dto.DatabaseInfo;
import com.dentapinos.dataguard.dto.ExportResponse;
import com.dentapinos.dataguard.dto.TablesResponse;
import com.dentapinos.dataguard.entity.storage.BackupFileInfo;
import com.dentapinos.dataguard.enums.BackupTier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@Tag(
        name = "Backup",
        description = "Управление резервными копиями баз данных"
)
@RequestMapping("/api/backup")
public interface BackupApi {

    /**
     * <p> >>>POST<<< /api/backup/{databaseName}/tables</p>
     */
    @Operation(
            summary = "➡️💾 Создать резервную копию выбранных таблиц",
            description = """
                    Запускает разовый бэкап выбранных таблиц для указанной логической базы данных.
                    
                    <h3>Параметры запроса</h3>
                    <ul>
                      <li><strong>databaseName</strong> — логическое имя базы из конфигурации</li>
                      <li><strong>tables</strong> — список таблиц для бэкапа (если пустой — все таблицы)</li>
                    </ul>
                    
                    <h3>Пример запроса</h3>
                    <pre>
                    {
                      "tables": ["users", "orders", "products"]
                    }
                    </pre>
                    
                    <h3>Возвращает</h3>
                    <ul>
                      <li>Имя файла бэкапа</li>
                      <li>Логическая база данных</li>
                      <li>Уровень хранения (DAILY)</li>
                      <li>Детальный отчет о бэкапе</li>
                    </ul>
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Бэкап успешно создан",
            content = @Content(schema = @Schema(implementation = ExportResponse.class))
    )
    @ApiResponse(responseCode = "404", description = "Логическая база данных не найдена")
    @ApiResponse(responseCode = "500", description = "Внутренняя ошибка при создании бэкапа")
    @PostMapping(
            value = "/{databaseName}/tables",
            consumes = "application/json",
            produces = "application/json"
    )
    ResponseEntity<?> backupSelectedTables(
            @Parameter(description = "Логическое имя базы данных из конфигурации", example = "main_db")
            @PathVariable String databaseName,
            @RequestBody BackupTablesRequest request
    );

    /**
     * <p> >>>GET<<< /api/backup/{databaseName}/tables</p>
     */
    @Operation(
            summary = "🗄️ Получить список таблиц базы данных",
            description = """
                    Возвращает список всех таблиц для указанной логической базы данных.
                    
                    <h3>Параметры запроса</h3>
                    <ul>
                      <li><strong>databaseName</strong> — логическое имя базы из конфигурации</li>
                    </ul>
                    
                    <h3>Возвращает</h3>
                    <ul>
                      <li>Список имен таблиц базы данных</li>
                    </ul>
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Список таблиц получен",
            content = @Content(schema = @Schema(implementation = TablesResponse.class))
    )
    @ApiResponse(responseCode = "404", description = "Логическая база данных не найдена")
    @ApiResponse(responseCode = "500", description = "Внутренняя ошибка при получении списка")
    @GetMapping(
            value = "/{databaseName}/tables",
            produces = "application/json"
    )
    ResponseEntity<?> listTables(
            @Parameter(description = "Логическое имя базы данных из конфигурации", example = "main_db")
            @PathVariable String databaseName
    );

    /**
     * <p> >>>GET<<< /api/backup/databases</p>
     */
    @Operation(
            summary = "🗄️🗄️🗄️ Получить список сконфигурированных баз данных",
            description = """
                    Возвращает список логических баз данных, доступных для бэкапа.
                    Данные берутся из конфигурации application.yml.
                    
                    <h3>Возвращает</h3>
                    <ul>
                      <li>Список баз данных с именами и отображаемыми названиями</li>
                    </ul>
                    
                    <h3>Пример ответа</h3>
                    <pre>
                    [
                      {
                        "databaseName": "main_db",
                        "displayName": "Основная база данных"
                      },
                      {
                        "databaseName": "analytics_db",
                        "displayName": "База аналитики"
                      }
                    ]
                    </pre>
                    """
    )
    @ApiResponse(responseCode = "200", description = "Список баз данных получен")
    @GetMapping(
            value = "/databases",
            produces = "application/json"
    )
    ResponseEntity<List<DatabaseInfo>> listConfiguredDatabases();

    /**
     * <p> >>>GET<<< /api/backup/backups/{database}/{tier}/{name}</p>
     */
    @Operation(
            summary = "📦⬇️ Скачать файл бэкапа",
            description = """
                    Отдаёт архив с резервной копией по имени бэкапа и уровню хранения.
                    Файл возвращается как attachment для скачивания.
                    
                    <h3>Параметры запроса</h3>
                    <ul>
                      <li><strong>database</strong> — логическое имя базы данных</li>
                      <li><strong>name</strong> — имя файла бэкапа</li>
                      <li><strong>tier</strong> — тип/уровень бэкапа (DAILY, WEEKLY, MONTHLY и т.д.)</li>
                    </ul>
                    
                    <h3>Возвращает</h3>
                    <ul>
                      <li>ZIP-архив с резервной копией (Content-Type: application/zip)</li>
                    </ul>
                    """
    )
    @ApiResponse(responseCode = "200", description = "Файл успешно отдан")
    @ApiResponse(responseCode = "404", description = "Файл бэкапа не найден")
    @GetMapping(
            value = "/backups/{database}/{tier}/{name}",
            produces = "application/zip"
    )
    void downloadBackup(
            @Parameter(description = "Логическое имя базы данных из конфигурации", example = "main_db") @PathVariable String database,
            @Parameter(description = "Имя файла бэкапа", example = "backup_20240614_120000.zip") @PathVariable String name,
            @Parameter(description = "Тип/уровень бэкапа (DAILY, WEEKLY, MONTHLY и т.д.)", example = "DAILY") @PathVariable BackupTier tier,
            HttpServletResponse response) throws IOException;

    /**
     * <p> >>>GET<<< /api/backup/{databaseName}</p>
     */
    @Operation(
            summary = "📋 Получить список резервных копий для указанной базы данных",
            description = """
                    Возвращает детализированную информацию о всех резервных копиях для указанной базы данных.
                    Включает дату создания, размер, уровень хранения и статус.
                    
                    <h3>Параметры запроса</h3>
                    <ul>
                      <li><strong>databaseName</strong> — логическое имя базы данных</li>
                    </ul>
                    
                    <h3>Возвращает</h3>
                    <ul>
                      <li>Список BackupFileInfo с деталями каждой резервной копии</li>
                    </ul>
                    
                    <h3>Пример ответа</h3>
                    <pre>
                    [
                      {
                        "name": "backup_20240614_120000.zip",
                        "tier": "DAILY",
                        "createdAt": "2024-06-14T12:00:00Z",
                        "size": 1048576,
                        "status": "SUCCESS"
                      }
                    ]
                    </pre>
                    """
    )
    @ApiResponse(
            responseCode = "200",
            description = "Успешно получен список резервных копий",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = BackupFileInfo.class))
            ))
    @ApiResponse(responseCode = "404", description = "База данных не найдена")
    @ApiResponse(responseCode = "500", description = "Внутренняя ошибка сервера")
    @GetMapping(value = "/{databaseName}", produces = "application/json")
    ResponseEntity<List<BackupFileInfo>> listBackups(
            @Parameter(description = "Имя базы данных", example = "main_db") @PathVariable String databaseName
    ) throws IOException;

}
