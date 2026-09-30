package com.dentapinos.dataguard.service.restore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Сервис для разрешения плейсхолдеров в значениях по умолчанию.
 * <p>
 * Поддерживает шаблоны вида {@code #function_name} которые заменяются на
 * сгенерированные значения. Например:
 * <ul>
 *   <li>{@code #uuid} → "550e8400-e29b-41d4-a716-446655440000"</li>
 *   <li>{@code #now} → "2024-01-15T10:30:00Z"</li>
 *   <li>{@code #random_int} → 12345</li>
 * </ul>
 *
 * @see <a href="https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/UUID.html">UUID</a>
 */
@Service
@Slf4j
public class DefaultFunctionResolver {

    private static final String PLACEHOLDER_PREFIX = "#";

    /**
     * Разрешает плейсхолдер в значении.
     * <p>
     * Если значение начинается с {@code #}, вызывается соответствующая функция-генератор.
     * Иначе значение возвращается без изменений.
     * </p>
     *
     * @param value значение, которое может содержать плейсхолдер
     * @return разрешённое значение
     */
    public Object resolve(Object value) {
        if (value == null) {
            return null;
        }

        if (!(value instanceof String stringValue)) {
            return value;
        }

        if (!stringValue.startsWith(PLACEHOLDER_PREFIX)) {
            return value;
        }

        String functionName = stringValue.substring(PLACEHOLDER_PREFIX.length()).trim();
        return resolveFunction(functionName);
    }

    /**
     * Вызывает функцию-генератор по имени.
     *
     * @param functionName имя функции (без символа #)
     * @return сгенерированное значение
     */
    private Object resolveFunction(String functionName) {
        return switch (functionName) {
            // === Уникальные идентификаторы ===
            case "uuid" -> generateUuid();
            case "uuid_nodash" -> generateUuidNoDash();
            
            // === Дата и время ===
            case "now" -> generateNow();
            case "current_timestamp" -> generateCurrentTimestamp();
            case "current_date" -> generateCurrentDate();
            case "current_time" -> generateCurrentTime();
            case "unix_timestamp" -> generateUnixTimestamp();
            
            // === Случайные числа ===
            case "random_int" -> generateRandomInt();
            case "random_long" -> generateRandomLong();
            case "random_bool" -> generateRandomBool();
            case "random_pin_4" -> generateRandomPin(4);
            case "random_pin_6" -> generateRandomPin(6);
            
            // === Случайные строки ===
            case "random_string" -> generateRandomString(8);
            case "random_string_16" -> generateRandomString(16);
            case "random_string_32" -> generateRandomString(32);
            case "random_email" -> generateRandomEmail();
            case "random_name" -> generateRandomName();
            case "random_ip" -> generateRandomIp();
            case "random_hash" -> generateRandomHash(32);
            case "random_hash_64" -> generateRandomHash(64);
            
            default -> {
                log.warn("[DEFAULT_FUNCTION] Неизвестная функция: #{}", functionName);
                yield null;
            }
        };
    }

    /**
     * Генерирует уникальный идентификатор UUID v4.
     */
    private String generateUuid() {
        return UUID.randomUUID().toString();
    }

    /**
     * Генерирует текущую дату и время в формате MySQL DATETIME.
     * Формат: yyyy-MM-dd HH:mm:ss.SSSSSS (совместимо с MySQL datetime(6))
     */
    private String generateNow() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"));
    }

    /**
     * Генерирует случайное целое число.
     */
    private int generateRandomInt() {
        return ThreadLocalRandom.current().nextInt();
    }

    /**
     * Генерирует случайное длинное число.
     */
    private long generateRandomLong() {
        return ThreadLocalRandom.current().nextLong();
    }

    /**
     * Генерирует случайную строку заданной длины.
     */
    private String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        return ThreadLocalRandom.current().ints(length, 0, chars.length())
                .mapToObj(i -> String.valueOf(chars.charAt(i)))
                .reduce("", String::concat);
    }

    /**
     * Генерирует текущую метку времени в формате MySQL TIMESTAMP.
     * Формат: yyyy-MM-dd HH:mm:ss.SSSSSS
     */
    private String generateCurrentTimestamp() {
        return java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS"));
    }

    /**
     * Генерирует текущую дату в формате MySQL DATE.
     */
    private String generateCurrentDate() {
        return java.time.LocalDate.now().toString();
    }

    /**
     * Генерирует текущее время в формате MySQL TIME.
     */
    private String generateCurrentTime() {
        return java.time.LocalTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    // ========================================
    // Дополнительные функции-генераторы
    // ========================================

    /**
     * Генерирует UUID без дефисов (32 символа).
     */
    private String generateUuidNoDash() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Генерирует Unix timestamp (количество секунд с 1970-01-01).
     */
    private long generateUnixTimestamp() {
        return Instant.now().getEpochSecond();
    }

    /**
     * Генерирует случайное boolean значение.
     */
    private boolean generateRandomBool() {
        return ThreadLocalRandom.current().nextBoolean();
    }

    /**
     * Генерирует случайный PIN-код заданной длины (обычно 4 или 6 цифр).
     */
    private String generateRandomPin(int length) {
        return ThreadLocalRandom.current().ints(length, 0, 10)
                .mapToObj(String::valueOf)
                .reduce("", String::concat);
    }

    /**
     * Генерирует случайный email адрес.
     */
    private String generateRandomEmail() {
        String[] domains = {"gmail.com", "yahoo.com", "outlook.com", "example.com", "mail.ru"};
        String username = generateRandomString(8).toLowerCase();
        String domain = domains[ThreadLocalRandom.current().nextInt(domains.length)];
        return username + "@" + domain;
    }

    /**
     * Генерирует случайное имя (Latin).
     */
    private String generateRandomName() {
        String[] firstNames = {"John", "Jane", "Alice", "Bob", "Eve", "Charlie", "Diana", "Frank"};
        String[] lastNames = {"Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis"};
        String firstName = firstNames[ThreadLocalRandom.current().nextInt(firstNames.length)];
        String lastName = lastNames[ThreadLocalRandom.current().nextInt(lastNames.length)];
        return firstName + " " + lastName;
    }

    /**
     * Генерирует случайный IPv4 адрес.
     */
    private String generateRandomIp() {
        return String.format("%d.%d.%d.%d",
                ThreadLocalRandom.current().nextInt(1, 255),
                ThreadLocalRandom.current().nextInt(0, 255),
                ThreadLocalRandom.current().nextInt(0, 255),
                ThreadLocalRandom.current().nextInt(1, 255));
    }

    /**
     * Генерирует случайный hex-хеш заданной длины.
     */
    private String generateRandomHash(int length) {
        String chars = "0123456789abcdef";
        return ThreadLocalRandom.current().ints(length, 0, chars.length())
                .mapToObj(i -> String.valueOf(chars.charAt(i)))
                .reduce("", String::concat);
    }

    /**
     * Возвращает список доступных функций с описаниями.
     */
    public List<DefaultFunctionInfo> getAvailableFunctions() {
        return List.of(
                // === Уникальные идентификаторы ===
                new DefaultFunctionInfo(
                        "uuid",
                        "Генерирует уникальный идентификатор UUID v4 (с дефисами)",
                        "550e8400-e29b-41d4-a716-446655440000",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "uuid_nodash",
                        "Генерирует UUID без дефисов (32 символа)",
                        "550e8400e29b41d4a716446655440000",
                        "string"
                ),

                // === Дата и время ===
                new DefaultFunctionInfo(
                        "now",
                        "Генерирует текущую дату и время в формате MySQL DATETIME (yyyy-MM-dd HH:mm:ss.SSSSSS)",
                        "2024-01-15 10:30:00.000000",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "current_timestamp",
                        "Генерирует текущую метку времени в формате MySQL TIMESTAMP",
                        "2024-01-15 10:30:00.000000",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "current_date",
                        "Генерирует текущую дату в формате MySQL DATE",
                        "2024-01-15",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "current_time",
                        "Генерирует текущее время в формате MySQL TIME",
                        "10:30:00",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "unix_timestamp",
                        "Генерирует Unix timestamp (количество секунд с 1970-01-01)",
                        "1705312200",
                        "long"
                ),

                // === Случайные числа ===
                new DefaultFunctionInfo(
                        "random_int",
                        "Генерирует случайное целое число",
                        "12345",
                        "integer"
                ),
                new DefaultFunctionInfo(
                        "random_long",
                        "Генерирует случайное длинное число",
                        "9876543210",
                        "long"
                ),
                new DefaultFunctionInfo(
                        "random_bool",
                        "Генерирует случайное boolean значение (true/false)",
                        "true",
                        "boolean"
                ),
                new DefaultFunctionInfo(
                        "random_pin_4",
                        "Генерирует случайный PIN-код из 4 цифр",
                        "1234",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "random_pin_6",
                        "Генерирует случайный PIN-код из 6 цифр",
                        "123456",
                        "string"
                ),

                // === Случайные строки ===
                new DefaultFunctionInfo(
                        "random_string",
                        "Генерирует случайную строку длиной 8 символов",
                        "aB3xYz9Q",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "random_string_16",
                        "Генерирует случайную строку длиной 16 символов",
                        "xK9mP2nL4vR7wQ8j",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "random_string_32",
                        "Генерирует случайную строку длиной 32 символа",
                        "aB3xYz9QwR5tU7iO1pL4kM6nV8cX0zF2",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "random_email",
                        "Генерирует случайный email адрес",
                        "john.doe1234@gmail.com",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "random_name",
                        "Генерирует случайное имя (FirstName LastName)",
                        "John Smith",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "random_ip",
                        "Генерирует случайный IPv4 адрес",
                        "192.168.1.100",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "random_hash",
                        "Генерирует случайный hex-хеш длиной 32 символа (как MD5)",
                        "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
                        "string"
                ),
                new DefaultFunctionInfo(
                        "random_hash_64",
                        "Генерирует случайный hex-хеш длиной 64 символа (как SHA-256)",
                        "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
                        "string"
                )
        );
    }

    /**
     * Информация о доступной функции-генераторе.
     */
    public record DefaultFunctionInfo(
            String name,
            String description,
            String exampleValue,
            String returnType
    ) {}
}
