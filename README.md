# Data Guard — Сервис резервного копирования и восстановления баз данных

![Center Beer Beckup](src/main/resources/docs/dg.jpg)

> **Project**: Data Guard  
> **Package**: `com.dentapinos.dataguard`  
> **Version**: 0.0.1-SNAPSHOT  
> **Author**: Dentapinos  
> **Last Updated**: 2026-06-23

---

## 📋 Содержание

- [Обзор проекта](#-обзор-проекта)
- [Архитектура](#-архитектура)
- [Структура пакетов](#-структура-пакетов)
- [Ключевые компоненты](#-ключевые-компоненты)
- [Использование API](#-использование-api)
- [Расширение функциональности](#-расширение-функциональности)
- [Тестирование](#-тестирование)
- [Лучшие практики](#-лучшие-практики)

---

## 🎯 Обзор проекта

**Data Guard** — это фреймворк резервного копирования и восстановления баз данных, реализованный как Spring Boot приложение. Проект предоставляет:

- ✅ **Автоматическое резервное копирование** по расписанию
- ✅ **Поддержка нескольких уровней хранения**: DAILY, WEEKLY, MONTHLY, SEMI_ANNUAL, ANNUAL
- ✅ **7 стратегий восстановления** для разных сценариев
- ✅ **Выборочное восстановление** таблиц с учётом внешних ключей
- ✅ **REST API** с Swagger UI документацией
- ✅ **Многоуровневая архитектура** с чётким разделением ответственности
- ✅ **Модульная система стратегий** (Strategy pattern)
- ✅ **Комплексная обработка ошибок**

### Технологический стек

| Компонент | Технология | Версия |
|-----------|-----------|--------|
| Java | JDK | 17+ |
| Framework | Spring Boot | 3.2.6 |
| База данных | MySQL | 5.7+ / 8.0+ |
| JSON | Jackson | latest |
| Документация | OpenAPI/Swagger | 2.3.0 |
| Тестирование | Testcontainers | 1.19.7 |

---

## 🏗️ Архитектура

```
┌─────────────────────────────────────────────────────────────────────┐
│                        REST API Layer                               │
│  ┌─────────────────┐     ┌─────────────────┐     ┌───────────────┐ │
│  │BackupController │────▶│RestoreController│────▶│ OpenAPI/Swagger││
│  └─────────────────┘     └─────────────────┘     └───────────────┘ │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────────┐
│                      Service Layer (Facade)                         │
│  ┌─────────────────┐     ┌─────────────────┐     ┌───────────────┐ │
│  │  BackupFacade   │────▶│  RestoreService │────▶│  Factory      │ │
│  └─────────────────┘     └─────────────────┘     └───────────────┘ │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────────┐
│                      Engine & Strategy Layer                        │
│  ┌─────────────────┐     ┌─────────────────┐     ┌───────────────┐ │
│  │BackupEngine     │────▶│RestoreStrategy  │────▶│MetadataReader │ │
│  │(MySQL/Postgres) │     │(7 Strategies)   │     │             │ │
│  └─────────────────┘     └─────────────────┘     └───────────────┘ │
└───────────────────────────────┬─────────────────────────────────────┘
                                │
┌───────────────────────────────▼─────────────────────────────────────┐
│                      Storage Layer                                  │
│  ┌─────────────────┐     ┌─────────────────┐     ┌───────────────┐ │
│  │FileSystemStorage│────▶│ZipBackupStorage │────▶│RetentionMngr  │ │
│  └─────────────────┘     └─────────────────┘     └───────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

### Принципы проектирования

1. **Layered Architecture**: Чёткое разделение на слои (Controller → Service → Engine/Strategy → Storage)
2. **Strategy Pattern**: Использование стратегий для различных режимов восстановления
3. **Facade Pattern**: BackupFacade как центральная точка входа для операций резервного копирования
4. **Factory Pattern**: RestoreStrategyFactory для создания стратегий восстановления
5. **Dependency Injection**: Вся инфраструктура построена на Spring DI
6. **Open/Closed Principle**: Легко добавлять новые движки (PostgreSQL) и стратегии

---

## 📂 Структура пакетов

```
com.dentapinos.dataguard
├── DataGuardApplication.java          # Главный класс Spring Boot приложения
├── controller                         # REST API контроллеры
│   ├── api                            # API контракты (интерфейсы)
│   │   ├── BackupApi.java           # Контракт для операций резервного копирования
│   │   └── RestoreApi.java          # Контракт для операций восстановления
│   └── impl                           # Реализации контроллеров
│       ├── BackupController.java    # REST эндпоинты для бэкапов
│       └── RestoreController.java   # REST эндпоинты для восстановления
│
├── service                            # Бизнес-логика
│   ├── BackupFacade.java            # Фасад для операций резервного копирования
│   ├── RestoreService.java          # Основной сервис восстановления
│   ├── engine                         # Движки резервного копирования
│   │   ├── BackupEngine.java        # Интерфейс движка резервного копирования
│   │   ├── MySqlBackupEngine.java   # Реализация для MySQL
│   │   └── PostgresBackupEngine.java # FUTURE: Реализация для PostgreSQL
│   ├── metadata                       # Работа с метаданными БД
│   │   ├── DatabaseMetadataReader.java  # Интерфейс чтения метаданных
│   │   ├── MySqlMetadataReader.java     # Реализация для MySQL
│   │   └── PostgresMetadataReader.java  # FUTURE: Реализация для PostgreSQL
│   ├── factory                        # Фабрики
│   │   └── JdbcTemplateFactory.java # Фабрика JdbcTemplate
│   └── restore                        # Модуль восстановления
│       ├── RestoreService.java              # Основной сервис восстановления
│       ├── RestorePolicyService.java        # Определение политики восстановления
│       ├── SchemaCompatibilityService.java  # Проверка совместимости схем
│       ├── TableFilter.java                 # Фильтрация таблиц
│       ├── BatchImporter.java               # Пакетный импорт данных
│       ├── SqlBuilder.java                  # Генератор SQL
│       ├── order                        # Определение порядка восстановления
│       │   └── RestoreOrderService.java   # Служба определения порядка
│       ├── strategy                     # Стратегии восстановления
│       │   ├── RestoreStrategy.java             # Интерфейс стратегии
│       │   ├── RestoreStrategyFactory.java      # Фабрика стратегий
│       │   ├── AbstractRestoreStrategy.java     # Абстрактная базовая стратегия
│       │   ├── StrictRestoreStrategy.java       # STRICT режим
│       │   ├── SafeMergeRestoreStrategy.java    # SAFE_MERGE режим
│       │   ├── ForceReplaceRestoreStrategy.java # FORCE_REPLACE режим
│       │   ├── AppendOnlyRestoreStrategy.java   # APPEND_ONLY режим
│       │   ├── UpsertAllRestoreStrategy.java    # UPSERT_ALL режим
│       │   ├── DryRunRestoreStrategy.java       # DRY_RUN режим
│       │   └── SafeSchemaCheckRestoreStrategy.java # SAFE_SCHEMA_CHECK режим
│       └── sqlbuilder                   # SQL строители для разных стратегий
│           ├── SqlBuilderFactory.java
│           ├── FailOnConflictSqlBuilder.java
│           ├── SkipOnConflictSqlBuilder.java
│           └── OverwriteOnConflictSqlBuilder.java
│
├── storage                            # Слой хранилища
│   ├── BackupStorage.java               # Интерфейс хранилища
│   ├── ZipBackupStorageService.java     # Высокоуровневый сервис для ZIP операций
│   ├── BackupRetentionManager.java      # Управление политиками хранения
│   ├── BackupTierPromoter.java          # Повышение tier бэкапов
│   ├── BackupFileNamingService.java     # Генерация имен файлов
│   ├── BackupFileReader.java            # Чтение файлов бэкапов
│   ├── filesystem                       # Реализации хранилищ
│   │   └── FileSystemBackupStorage.java   # Файловая система
│   └── model                            # Модели данных
│       ├── BackupData.java              # Данные бэкапа
│       ├── BackupFile.java              # Обёртка над загруженным бэкапом
│       └── BackupFileInfo.java          # Метаданные файла бэкапа
│
├── report                             # Отчёты и статистика
│   ├── BackupEnvelope.java          # "Конверт" бэкапа (данные + отчёт)
│   ├── BackupReport.java            # Отчёт о резервном копировании
│   ├── BackupSummary.java           # Сводка операции резервного копирования
│   ├── RestoreReport.java           # Отчёт о восстановлении
│   └── RestoreSummary.java          # Сводка операции восстановления
│
├── entity                             # Доменные сущности
│   ├── TableMeta.java               # Метаданные таблицы
│   ├── SchemaMeta.java              # Метаданные схемы
│   ├── ColumnMeta.java              # Метаданные столбца
│   ├── ForeignKeyMeta.java          # Метаданные внешнего ключа
│   ├── IndexMeta.java               # Метаданные индекса
│   ├── ExportStats.java             # Статистика экспорта
│   ├── RestorePolicy.java           # Политика восстановления
│   └── RestoreStats.java            # Статистика восстановления
│
├── enums                              # Перечисления
│   ├── BackupTier.java              # Тип бэкапа (DAILY, WEEKLY, MONTHLY, SEMI_ANNUAL, ANNUAL)
│   ├── BackupStatus.java            # Состояние бэкапа (PENDING, RUNNING, SUCCESS, FAILED)
│   ├── RestoreMode.java             # Режим восстановления (7 стратегий)
│   ├── RestoreStatus.java           # Состояние восстановления
│   ├── SchemaCompatibilityMode.java # Режим совместимости схемы
│   ├── StorageType.java             # Тип хранилища
│   └── policy                         # Политики восстановления
│       ├── ErrorPolicy.java         # Политика обработки ошибок
│       ├── ForeignKeyPolicy.java    # Политика внешних ключей
│       ├── RowConflictPolicy.java   # Политика конфликтов строк
│       └── SchemaPolicy.java        # Политика схемы
│
├── scheduler                          # Планировщики задач
│   ├── BackupScheduler.java             # Создание бэкапов по расписанию
│   ├── BackupPromotionScheduler.java    # Повышение tier бэкапов
│   └── BackupRetentionScheduler.java    # Очистка старых бэкапов
│
├── config                             # Конфигурация
│   ├── BackupProperties.java              # Основные свойства резервного копирования
│   ├── BackupDatabasesProperties.java     # Список БД для бэкапа
│   ├── BackupScheduleProperties.java      # Настройки расписания (cron)
│   ├── BackupRetentionProperties.java     # Политика хранения (retention)
│   ├── FileSystemStorageProperties.java   # Настройки файлового хранилища
│   ├── JacksonConfig.java                 # Конфигурация Jackson
│   ├── OpenApiConfig.java                 # Конфигурация OpenAPI/Swagger
│   └── validation                         # Пользовательские валидации
│       └── ValidJdbcUrl.java              # Аннотация валидации JDBC URL
│
├── dto                                # DTO объекты для REST API
│   ├── BackupTablesRequest.java         # Запрос на бэкап выбранных таблиц
│   ├── DatabaseInfo.java                # Информация о БД
│   ├── DbCredentials.java               # Учётные данные для БД
│   ├── ExportResponse.java              # Ответ на создание бэкапа
│   ├── TablesResponse.java              # Ответ со списком таблиц
│   ├── AnalyzeSchemaRequest.java        # Запрос на анализ схемы
│   ├── SchemaCompatibilityAnalysisDto.java # Ответ анализа схемы
│   ├── RestoreRequest.java              # Запрос на восстановление
│   ├── RestoreToNewDatabaseRequest.java # Запрос на восстановление в новую БД
│   └── RestoreNewRequest.java           # Запрос с новыми настройками
│
├── utils                              # Утилиты
│   └── DatabaseConfigResolver.java    # Разрешение конфигурации БД по имени
│
└── exception                          # Исключения
    ├── GlobalExceptionHandler.java        # Глобальный обработчик исключений
    ├── ApiErrorResponse.java              # DTO для ответов с ошибками
    ├── DatabaseNotFoundException.java     # База данных не найдена
    ├── InvalidRestoreModeException.java   # Неверный режим восстановления
    ├── RestoreOperationException.java     # Ошибка операции восстановления
    ├── RestoreZipException.java           # Ошибка ZIP архива
    └── storage exceptions                 # Исключения хранилища
        ├── BackupStorageException.java
        ├── BackupFileNotFoundException.java
        ├── BackupFileAttributesException.java
        ├── BackupFileReadingException.java
        └── BackupFileWritingException.java
```

---

## 🔑 Ключевые компоненты

### 1. BackupFacade — Центральный фасад для бэкапов

```java
@Service
public class BackupFacade {
    
    // Ручной бэкап выбранных таблиц
    public BackupEnvelope backupMySql(DbCredentials credentials,
                                      String database,
                                      List<String> tablesToInclude)
    
    // Автоматический бэкап всех таблиц и сохранение в хранилище
    public String backupAndStore(String database,
                                 DbCredentials credentials) throws IOException
    
    // Храним уже созданный бэкап
    public String storeBackup(BackupEnvelope envelope, String database) throws IOException
    
    // Загрузка бэкапа из хранилища
    public void loadBackup(String database,
                           String backupName,
                           BackupTier tier,
                           OutputStream out) throws IOException
    
    // Получить список таблиц БД
    public List<String> listTablesMySql(DbCredentials credentials, String database)
}
```

### 2. RestoreService — Основной сервис восстановления

```java
@Service
public class RestoreService {
    
    // Восстановление в существующую базу данных
    public RestoreReport restoreToExistingDatabase(
            DbCredentials dbCredentials,
            BackupTier tier,
            String backupName,
            String targetDatabase,
            RestoreMode mode,
            List<String> tables
    )
}
```

### 3. RestoreStrategy — Стратегии восстановления (7 режимов)

| Режим | Ключевое поведение | Использование |
|-------|-------------------|---------------|
| `STRICT` | Полная проверка схемы и данных | Тестовая среда, полная идентичность |
| `SAFE_MERGE` | Пропуск конфликтов | Постепенное обновление |
| `FORCE_REPLACE` | Агрессивная замена | Полное восстановление |
| `APPEND_ONLY` | Только добавление | Логирование событий |
| `UPSERT_ALL` | Upsert-операции | Синхронизация данных |
| `DRY_RUN` | Эмуляция без изменений | Тестирование стратегии |
| `SAFE_SCHEMA_CHECK` | Только проверка схемы | Предварительная проверка |

### 4. BackupStorage — Интерфейс хранилища

```java
public interface BackupStorage {
    // Загрузка файла бэкапа
    InputStream load(BackupTier tier, String database, String backupName);
    
    // Загрузка бэкапа в поток
    void load(BackupTier tier, String database, String backupName, OutputStream out);
    
    // Сохранение файла бэкапа
    void save(BackupTier tier, String database, String backupName, InputStream content);
    
    // Список всех бэкапов для базы данных
    List<BackupFileInfo> listWithInfo(String database);
    
    // Удаление файла бэкапа
    void delete(BackupTier tier, String database, String backupName);
    
    // Проверка существования файла
    boolean exists(BackupTier tier, String database, String backupName);
}
```

### 5. RestoreStrategyFactory — Фабрика стратегий

```java
@Service
public class RestoreStrategyFactory {
    
    public RestoreStrategy getStrategy(RestoreMode mode) {
        return switch (mode) {
            case STRICT -> new StrictRestoreStrategy();
            case SAFE_MERGE -> new SafeMergeRestoreStrategy();
            case FORCE_REPLACE -> new ForceReplaceRestoreStrategy();
            case APPEND_ONLY -> new AppendOnlyRestoreStrategy();
            case UPSERT_ALL -> new UpsertAllRestoreStrategy();
            case DRY_RUN -> new DryRunRestoreStrategy();
            case SAFE_SCHEMA_CHECK -> new SafeSchemaCheckRestoreStrategy();
            default -> throw new InvalidRestoreModeException("Unknown mode: " + mode);
        };
    }
}
```

### 6. RestorePolicyService — Определение политики

```java
@Service
public class RestorePolicyService {
    
    public RestorePolicy policyFor(RestoreMode mode) {
        return switch (mode) {
            case STRICT -> new RestorePolicy(
                SchemaPolicy.STRICT_SCHEMA,
                RowConflictPolicy.FAIL_ON_CONFLICT,
                ForeignKeyPolicy.ENFORCE_ALL,
                ErrorPolicy.FAIL_FAST
            );
            case SAFE_MERGE, APPEND_ONLY -> new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.LOG_AND_CONTINUE
            );
            case FORCE_REPLACE, UPSERT_ALL -> new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.OVERWRITE_ON_CONFLICT,
                ForeignKeyPolicy.TEMP_DISABLE,
                ErrorPolicy.LOG_AND_CONTINUE
            );
            case DRY_RUN, SAFE_SCHEMA_CHECK -> new RestorePolicy(
                SchemaPolicy.RELAXED_SCHEMA,
                RowConflictPolicy.SKIP_ON_CONFLICT,
                ForeignKeyPolicy.SKIP_VIOLATIONS,
                ErrorPolicy.LOG_AND_CONTINUE
            );
            default -> throw new InvalidRestoreModeException("Unknown mode: " + mode);
        };
    }
}
```

---

## 💡 Использование API

### 1. Создание бэкапа

#### Ручной бэкап выбранных таблиц

```java
@Autowired
private BackupFacade backupFacade;

// Учётные данные
DbCredentials credentials = new DbCredentials(
    "jdbc:mysql://localhost:3306/bm",
    "root",
    "password"
);

// Создать бэкап всех таблиц
BackupEnvelope envelope = backupFacade.backupMySql(credentials, "bm", null);

// Создать бэкап выбранных таблиц
List<String> selectedTables = List.of("users", "orders", "products");
BackupEnvelope envelope = backupFacade.backupMySql(credentials, "bm", selectedTables);

// Сохранить бэкап
String backupName = backupFacade.storeBackup(envelope, "bm");
```

#### Через REST API

```bash
# Создание бэкапа всех таблиц
curl -X POST "http://localhost:8080/api/backup/database/bm" \
  -H "Content-Type: application/json" \
  -d '{"tables": null}'

# Создание бэкапа выбранных таблиц
curl -X POST "http://localhost:8080/api/backup/database/bm" \
  -H "Content-Type: application/json" \
  -d '{"tables": ["users", "orders", "products"]}'
```

### 2. Восстановление данных

#### Через сервис

```java
@Autowired
private RestoreService restoreService;

DbCredentials credentials = new DbCredentials(
    "jdbc:mysql://localhost:3306/bm",
    "root",
    "password"
);

// Восстановление в существующую базу
RestoreReport report = restoreService.restoreToExistingDatabase(
    credentials,
    BackupTier.DAILY,
    "backup-bm-2026-06-14T10-00-00.zip",
    "bm",
    RestoreMode.FORCE_REPLACE,
    null // null = все таблицы
);

// Восстановление только выбранных таблиц
RestoreReport report = restoreService.restoreToExistingDatabase(
    credentials,
    BackupTier.DAILY,
    "backup-bm-2026-06-14T10-00-00.zip",
    "bm",
    RestoreMode.SAFE_MERGE,
    List.of("users", "orders") // Только эти таблицы
);
```

#### Через REST API

```bash
# Восстановление всех таблиц
curl -X POST "http://localhost:8080/api/restore?tier=DAILY" \
  -H "Content-Type: application/json" \
  -d '{
    "backupName": "backup-bm-2026-06-14T10-00-00.zip",
    "targetDatabase": "bm",
    "mode": "FORCE_REPLACE",
    "tables": null
  }'

# Восстановление выбранных таблиц
curl -X POST "http://localhost:8080/api/restore?tier=DAILY" \
  -H "Content-Type: application/json" \
  -d '{
    "backupName": "backup-bm-2026-06-14T10-00-00.zip",
    "targetDatabase": "bm",
    "mode": "SAFE_MERGE",
    "tables": ["users", "orders"]
  }'
```

### 3. Получение списка бэкапов

```java
@Autowired
private BackupStorage backupStorage;

// Список всех бэкапов для базы данных
List<BackupFileInfo> backups = backupStorage.listWithInfo("bm");
```

```bash
curl "http://localhost:8080/api/backup/list/bm"
```

### 4. Анализ совместимости схемы

```java
@Autowired
private SchemaCompatibilityService schemaCompatibilityService;

BackupFile backup = ...; // Загруженный бэкап
DbCredentials credentials = ...;

SchemaCompatibilityAnalysisDto analysis = 
    schemaCompatibilityService.analyzeCompatibility(
        credentials,
        backup,
        "target_database_name"
    );

// Проверка результата
if (analysis.compatibility() == SchemaCompatibilityMode.COMPATIBLE) {
    log.info("Схемы совместимы");
} else {
    log.warn("Несовместимости: {}", analysis.incompatibilities());
}
```

```bash
curl -X POST "http://localhost:8080/api/restore/analyze-schema?database=bm&tier=DAILY" \
  -H "Content-Type: application/json" \
  -d '{
    "backupName": "backup-bm-2026-06-14T10-00-00.zip",
    "targetDatabase": "bm"
  }'
```

### 5. Планирование бэкапов

#### Автоматические бэкапы (через BackupScheduler)

```java
@Autowired
private BackupScheduler backupScheduler;

// Запуск бэкапа для всех сконфигурированных БД
backupScheduler.runBackup();
```

#### Конфигурация в application.yml

```yaml
backup:
  schedule:
    daily-backup-cron: "0 0 2 * * *"      # Ежедневно в 02:00
    promote-daily-to-weekly-cron: "0 0 3 * * *"   # В 03:00
    promote-weekly-to-monthly-cron: "0 5 3 * * *" # В 03:05
    retention-cron: "0 30 4 * * *"        # В 04:30
    
  retention:
    daily-days: 7
    weekly-weeks: 12
    monthly-months: 24
    semi-annual-years: 5
    annual-years: 10

  databases:
    - database-name: bm
      display-name: "Main Database"
      url: jdbc:mysql://localhost:3306/bm
      username: root
      password: password
```

---

## 🔧 Расширение функциональности

### 1. Добавление новой стратегии восстановления

1. Создайте новый класс, реализующий `RestoreStrategy`:

```java
@Service
public class CustomRestoreStrategy implements RestoreStrategy {
    
    @Override
    public void restore(DbCredentials dbCredentials,
                        BackupFile backup,
                        String targetDatabase,
                        RestorePolicy policy,
                        RestoreStats stats,
                        String fileName) throws Exception {
        
        // Реализация кастомной логики восстановления
    }
}
```

2. Добавьте стратегию в `RestoreStrategyFactory`:

```java
case CUSTOM -> new CustomRestoreStrategy();
```

### 2. Добавление нового движка резервного копирования (PostgreSQL)

1. Создайте реализацию `BackupEngine`:

```java
@Service
public class PostgresBackupEngine implements BackupEngine {
    
    @Override
    public BackupEnvelope backup(DbCredentials credentials,
                                 String database,
                                 List<String> tables) {
        // Реализация бэкапа для PostgreSQL
    }
}
```

2. Создайте реализацию `DatabaseMetadataReader`:

```java
@Service
public class PostgresMetadataReader implements DatabaseMetadataReader {
    
    @Override
    public List<String> listTables(DbCredentials credentials, String database) {
        // Получение списка таблиц PostgreSQL
    }
}
```

### 3. Добавление нового типа хранилища (S3, GCS, Azure Blob)

1. Создайте реализацию `BackupStorage`:

```java
@Service
public class S3BackupStorage implements BackupStorage {
    
    @Override
    public InputStream load(BackupTier tier, String database, String backupName) {
        // Загрузка из S3
    }
    
    @Override
    public void save(BackupTier tier, String database, String backupName, InputStream content) {
        // Сохранение в S3
    }
    
    // ... остальные методы
}
```

2. Настройте конфигурацию:

```java
@ConfigurationProperties(prefix = "backup.s3")
public class S3StorageProperties {
    private String bucket;
    private String region;
    // ... другие настройки
}
```

### 4. Добавление кастомной политики восстановления

```java
public class CustomRestorePolicy extends RestorePolicy {
    
    public CustomRestorePolicy() {
        super(
            SchemaPolicy.RELAXED_SCHEMA,
            RowConflictPolicy.CUSTOM_CONFLICT_RESOLUTION,
            ForeignKeyPolicy.ENFORCE_ALL,
            ErrorPolicy.LOG_AND_CONTINUE
        );
    }
}
```

---

## 🧪 Тестирование

### 1. Тесты с Testcontainers

```java
@Testcontainers
@SpringBootTest
class RestoreServiceIntegrationTest {
    
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");
    
    @Autowired
    private RestoreService restoreService;
    
    @Test
    void shouldRestoreToExistingDatabase() {
        // Given
        DbCredentials credentials = new DbCredentials(
            mysql.getJdbcUrl(),
            mysql.getUsername(),
            mysql.getPassword()
        );
        
        // When
        RestoreReport report = restoreService.restoreToExistingDatabase(
            credentials,
            BackupTier.DAILY,
            "test-backup.zip",
            "test_db",
            RestoreMode.FORCE_REPLACE,
            null
        );
        
        // Then
        assertThat(report.status()).isEqualTo(RestoreStatus.SUCCESS);
    }
}
```

### 2. Мокирование зависимостей

```java
@ExtendWith(MockExtension.class)
class RestoreServiceTest {
    
    @Mock
    private RestorePolicyService policyService;
    
    @Mock
    private RestoreStrategyFactory strategyFactory;
    
    @Mock
    private BackupStorage backupStorage;
    
    @InjectMocks
    private RestoreService restoreService;
    
    @Test
    void shouldRestoreSuccessfully() {
        // Given
        when(policyService.policyFor(any())).thenReturn(new RestorePolicy(...));
        when(strategyFactory.getStrategy(any())).thenReturn(new TestStrategy());
        
        // When
        RestoreReport report = restoreService.restoreToExistingDatabase(...);
        
        // Then
        verify(backupStorage).load(any(), any(), any());
    }
}
```

### 3. Тестирование стратегий

```java
class StrictRestoreStrategyTest {
    
    @Test
    void shouldFailOnSchemaMismatch() {
        // Given
        RestoreStrategy strategy = new StrictRestoreStrategy();
        
        // When & Then
        assertThrows(SchemaMismatchException.class, () -> {
            strategy.restore(credentials, backup, targetDb, policy, stats, fileName);
        });
    }
    
    @Test
    void shouldRestoreSuccessfullyOnMatch() {
        // Given
        RestoreStrategy strategy = new StrictRestoreStrategy();
        
        // When
        strategy.restore(credentials, backup, targetDb, policy, stats, fileName);
        
        // Then
        assertEquals(0, stats.getTablesFailed());
    }
}
```

### 4. Запуск тестов через Maven

Для единообразного запуска тестов (локально и в CI/CD) используется скрипт `run-tests.sh`.  
Он поддерживает три режима:

- `unit` — только юнит‑тесты (`*Test.java`);
- `it` — только интеграционные тесты (`*IT.java`);
- `all` — юнит + интеграционные (по умолчанию).

#### 4.1. Подготовка скрипта

Сделайте скрипт исполняемым (обычно выполняется один раз и/или на шаге CI):

```bash
chmod +x run-tests.sh
```

#### 4.2. Локальный запуск

```bash
# Только юнит-тесты
./run-tests.sh unit

# Только интеграционные тесты
./run-tests.sh it

# Все тесты (юнит + интеграционные)
./run-tests.sh all
```

#### 4.3. Пример интеграции с CI

**GitHub Actions**

```yaml
- name: Make test runner executable
  run: chmod +x run-tests.sh

- name: Run unit tests
  run: ./run-tests.sh unit

- name: Run integration tests
  run: ./run-tests.sh it
```

**GitLab CI**

```yaml
unit_tests:
  stage: test
  script:
    - chmod +x run-tests.sh
    - ./run-tests.sh unit

integration_tests:
  stage: test
  script:
    - chmod +x run-tests.sh
    - ./run-tests.sh it
```

В других CI‑системах достаточно выполнить те же команды: сначала `chmod +x run-tests.sh`, затем `./run-tests.sh <unit|it|all>`.

#### 4.3.1. Запуск тестов в Windows

В Windows (PowerShell) скрипт запускается через bash (WSL или Git Bash):

```powershell
# Для запуска на Linux/WSL
bash run-tests.sh unit
bash run-tests.sh it
bash run-tests.sh all

# Или напрямую через Maven (аналогично)
mvn test                                       # только юнит-тесты
mvn failsafe:integration-test failsafe:verify  # только интеграционные тесты
mvn verify                                     # все тесты (юнит + интеграционные)
# Для некоторых систем 
./mvnw test                                       # только юнит-тесты
./mvnw failsafe:integration-test failsafe:verify  # только интеграционные тесты
./mvnw verify                                     # все тесты (юнит + интеграционные)
```

**В PowerShell (Windows) напрямую:**

Для Windows предназначен скрипт `run-tests.ps1`, который поддерживает те же режимы, что и `run-tests.sh`:

```powershell
# Только юнит-тесты
./run-tests.ps1 -Mode unit

# Только интеграционные тесты
./run-tests.ps1 -Mode it

# Все тесты (юнит + интеграционные)
./run-tests.ps1 -Mode all
```

**Примечание:** Если в PowerShell возникает ошибка "execution of scripts is disabled", выполните:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
```

**Важно:** Скрипт `run-tests.ps1` использует команду `./mvnw` (Maven Wrapper), что позволяет запускать Maven без предварительной установки. Если Maven не работает через `./mvnw`, установите Maven в систему и замените `./mvnw` на `mvn` в скрипте.

#### 4.4. Запуск тестов через кастомный запускатор (для кастомных отчетов)

В проекте также есть кастомный запускатор тестов в классе `TestRunner.java` (`src/main/java/com/dentapinos.dataguard/TestRunner.java`).
Он позволяет:

- Запускать тесты программно через JUnit Platform Launcher API
- Использовать кастомный слушатель событий `MyCustomListener.java` для форматированного вывода результатов
- Запускать тесты в средах, где стандартный Maven запуск не подходит

**Простой способ запуска (рекомендуется для Windows):**

```powershell
# Сначала скомпилируйте тесты
./mvnw test-compile

# Получите classpath для зависимостей и сохраните в файл (обратите внимание на кавычки вокруг параметров)
./mvnw dependency:build-classpath -D"mdep.outputFile=classpath.txt" -D"includeScope=test" -q

# Прочитайте classpath из файла
$CLASSPATH = Get-Content classpath.txt

# Запустите через Java (важно: include target/classes, target/test-classes и classpath)
java -cp "target/classes;target/test-classes;$CLASSPATH" com.dentapinos.dataguard.TestRunner all
```

**Ручной способ запуска (для отладки):**

```bash
# Скомпилируйте тесты
./mvnw test-compile

# Получите classpath для зависимостей
CLASSPATH=$(./mvnw dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout)

# Запустите через Java
java -cp "target/classes:target/test-classes:$CLASSPATH" com.dentapinos.dataguard.TestRunner all

# Только юнит-тесты
java -cp "target/classes:target/test-classes:$CLASSPATH" com.dentapinos.dataguard.TestRunner unit

# Только интеграционные тесты
java -cp "target/classes:target/test-classes:$CLASSPATH" com.dentapinos.dataguard.TestRunner it
```

**Для Windows (PowerShell):** Сохраните classpath в файл, так как передача через stdout может работать некорректно:

```powershell
# Скомпилируйте тесты
./mvnw test-compile

# Получите classpath для зависимостей и сохраните в файл (обратите внимание на кавычки вокруг параметров)
./mvnw dependency:build-classpath -D"mdep.outputFile=classpath.txt" -D"includeScope=test" -q

# Прочитайте classpath из файла
$CLASSPATH = Get-Content classpath.txt

# Запустите через Java (важно: include target/classes, target/test-classes и classpath)
java -cp "target/classes;target/test-classes;$CLASSPATH" com.dentapinos.dataguard.TestRunner all

# Только юнит-тесты
java -cp "target/classes;target/test-classes;$CLASSPATH" com.dentapinos.dataguard.TestRunner unit

# Только интеграционные тесты
java -cp "target/classes;target/test-classes;$CLASSPATH" com.dentapinos.dataguard.TestRunner it
```
# Скомпилируйте тесты
./mvnw test-compile

# Получите classpath для зависимостей и сохраните в файл
./mvnw dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=classpath.txt

# Прочитайте classpath из файла
$CLASSPATH = Get-Content classpath.txt

# Запустите через Java (важно: include target/classes, target/test-classes и classpath)
java -cp "target/classes;target/test-classes;$CLASSPATH" com.dentapinos.dataguard.TestRunner all

# Только юнит-тесты
java -cp "target/classes;target/test-classes;$CLASSPATH" com.dentapinos.dataguard.TestRunner unit

# Только интеграционные тесты
java -cp "target/classes;target/test-classes;$CLASSPATH" com.dentapinos.dataguard.TestRunner it
```
# Скомпилируйте тесты
./mvnw test-compile

# Получите classpath для зависимостей и сохраните в файл
./mvnw dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=classpath.txt

# Прочитайте classpath из файла
$CLASSPATH = Get-Content classpath.txt

# Запустите через Java (используйте ; вместо : как разделитель)
java -cp "target/classes;target/test-classes;$CLASSPATH" com.dentapinos.dataguard.TestRunner all
```

**Кастомный слушатель `MyCustomListener.java`** (`src/main/java/com/dentapinos/dataguard/MyCustomListener.java`) отображает результаты тестов с эмодзи:

- 🚀 Запуск тест-планов (количество корневых элементов)
- 🧪 Запуск теста (имя теста и legacy имя)
- ✅ Успешное выполнение
- ⚠️ Прервано
- ❌ Ошибка (с выводом stacktrace)
- 🏁 Завершение тест-плана

**Преимущества кастомного запускатора:**

- Форматированный вывод результатов тестов
- Возможность кастомизации логики запуска
- Интеграция с CI/CD через Java API

**Альтернативно**, можно использовать стандартный Maven:

```powershell
# Только юнит-тесты (используя Maven Wrapper)
./mvnw test

# Только интеграционные тесты (используя Maven Wrapper)
./mvnw verify

# Все тесты (юнит + интеграционные) (используя Maven Wrapper)
./mvnw clean verify

# Альтернативно, если Maven установлен в системе:
mvn test
mvn verify
mvn clean verify
```

---

## 🛠️ Лучшие практики

### 1. Использование REST API

**Контроллеры не должны содержать бизнес-логику** — они только делегируют в сервисы.

**Пример правильного использования:**

```java
@RestController
public class BackupController {
    
    @Autowired
    private BackupFacade backupFacade; // Делегирование в фасад
    
    @PostMapping("/backup")
    public ResponseEntity<?> createBackup(@RequestBody BackupRequest request) {
        try {
            // Простое делегирование
            String backupName = backupFacade.backupAndStore(request.getDatabase());
            return ResponseEntity.ok(new BackupResponse(backupName));
        } catch (IOException e) {
            // Обработка ошибок
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }
}
```

### 2. Обработка ошибок

**Используйте специфичные исключения:**

```java
try {
    backupFacade.backupAndStore(database);
} catch (DatabaseNotFoundException e) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Database not found");
} catch (IOException e) {
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Storage unavailable");
} catch (Exception e) {
    // Логирование
    log.error("Unexpected error", e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
}
```

### 3. Конфигурация через application.yml

**Выносите все настройки во внешнюю конфигурацию:**

```yaml
backup:
  schedule:
    daily-backup-cron: "${BACKUP_SCHEDULE_DAILY_CRON:0 0 2 * * *}"
    retention-cron: "${BACKUP_SCHEDULE_RETENTION_CRON:0 30 4 * * *}"
    
  retention:
    daily-days: "${BACKUP_RETENTION_DAILY_DAYS:7}"
    
  file-system:
    base-path: "${BACKUP_STORAGE_PATH:/var/backup}"
```

### 4. Мониторинг и логирование

**Добавляйте логирование в критических точках:**

```java
log.info("[BACKUP] Starting backup for database: {}", database);
log.info("[BACKUP] Completed backup: {}", backupName);
log.info("[RESTORE] Started restore for backup: {}", backupName);
log.info("[RESTORE] Completed restore with status: {}", report.status());
```

### 5. Тестирование изменений

**Перед изменением стратегии убедитесь, что:**

1. Все существующие тесты проходят
2. Добавлены тесты для нового поведения
3. Документация обновлена
4. Произведён бэкап перед изменением в продакшене

### 6. Безопасность

**Храните учётные данные в безопасном месте:**

- Используйте переменные окружения
- Используйте Spring Cloud Config
- Используйте Vault или аналоги
- **Никогда не храните пароли в коде**

```java
// ✅ Правильно
@Value("${database.password:}")
private String password;

// ❌ Неправильно
private String password = "password123";
```

---

## 📚 Дополнительные ресурсы

- [Project Structure](src/main/java/com/dentapinos/dataguard/project-structure.md) — Подробное описание структуры проекта
- [Selective Restore](src/main/java/com/dentapinos/dataguard/SELECTIVE_RESTORE.md) — Документация по выборочному восстановлению таблиц
- [README.md](../../../README.md) — Общая документация проекта

---

## 📞 Поддержка

**Author**: Dentapinos  
**Email**: support@center.beer  
**GitHub**: [https://github.com/dentapinos](https://github.com/dentapinos)

---

## 📝 История изменений

### v0.0.1-SNAPSHOT (2026-06-22)
- ✅ Базовая функциональность резервного копирования
- ✅ REST API для бэкапов и восстановления
- ✅ Поддержка MySQL
- ✅ 5 уровней хранения (DAILY, WEEKLY, MONTHLY, SEMI_ANNUAL, ANNUAL)
- ✅ 7 стратегий восстановления
- ✅ Выборочное восстановление таблиц
- ✅ Автоматическое создание бэкапов по расписанию
- ✅ Swagger UI документация

---

**Последнее обновление**: 2026-06-22
