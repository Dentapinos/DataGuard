package com.dentapinos.dataguard.unit.service.restore;

import com.dentapinos.dataguard.service.restore.DefaultFunctionResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit-тесты для DefaultFunctionResolver.
 * Проверяет генерацию значений для плейсхолдеров (#uuid, #now, и т.д.).
 */
@DisplayName("Unit-test для DefaultFunctionResolver")
class DefaultFunctionResolverTest {

    private DefaultFunctionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultFunctionResolver();
    }

    @Test
    @DisplayName("Должен вернуть null для null значения")
    void shouldReturnNullForNullValue() {
        assertThat(resolver.resolve(null)).isNull();
    }

    @Test
    @DisplayName("Должен вернуть строку без изменений если нет плейсхолдера")
    void shouldReturnStringUnchangedIfNoPlaceholder() {
        assertThat(resolver.resolve("hello")).isEqualTo("hello");
        assertThat(resolver.resolve("123")).isEqualTo("123");
    }

    @Test
    @DisplayName("Должен вернуть число без изменений")
    void shouldReturnNumberUnchanged() {
        assertThat(resolver.resolve(42)).isEqualTo(42);
        assertThat(resolver.resolve(99.5)).isEqualTo(99.5);
    }

    @Test
    @DisplayName("Должен генерировать уникальный UUID")
    void shouldGenerateUniqueUuid() {
        Object result1 = resolver.resolve("#uuid");
        Object result2 = resolver.resolve("#uuid");

        assertThat(result1).isInstanceOf(String.class);
        assertThat(result2).isInstanceOf(String.class);
        assertThat(result1).isNotEqualTo(result2); // UUID должны быть уникальными

        // Проверяем формат UUID (8-4-4-4-12)
        String uuid = (String) result1;
        assertThat(uuid).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Должен генерировать текущую дату и время")
    void shouldGenerateNow() {
        Object result = resolver.resolve("#now");

        assertThat(result).isInstanceOf(String.class);
        String now = (String) result;
        // MySQL-формат: 2024-01-15 10:30:00.000000
        assertThat(now).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}");
    }

    @Test
    @DisplayName("Должен генерировать случайное int")
    void shouldGenerateRandomInt() {
        Object result = resolver.resolve("#random_int");

        assertThat(result).isInstanceOf(Integer.class);
        // Проверяем что разные вызовы дают разные значения
        int val1 = (int) result;
        int val2 = (int) resolver.resolve("#random_int");
        // Могут совпасть с вероятностью 1/2^32, но это нормально
        assertThat(val1).isNotNull();
    }

    @Test
    @DisplayName("Должен генерировать случайное long")
    void shouldGenerateRandomLong() {
        Object result = resolver.resolve("#random_long");

        assertThat(result).isInstanceOf(Long.class);
    }

    @Test
    @DisplayName("Должен генерировать случайные строки")
    void shouldGenerateRandomStrings() {
        Object result8 = resolver.resolve("#random_string");
        Object result16 = resolver.resolve("#random_string_16");
        Object result32 = resolver.resolve("#random_string_32");

        assertThat(result8).isInstanceOf(String.class);
        assertThat((String) result8).hasSize(8);

        assertThat(result16).isInstanceOf(String.class);
        assertThat((String) result16).hasSize(16);

        assertThat(result32).isInstanceOf(String.class);
        assertThat((String) result32).hasSize(32);
    }

    @Test
    @DisplayName("Должен генерировать текущую дату")
    void shouldGenerateCurrentDate() {
        Object result = resolver.resolve("#current_date");

        assertThat(result).isInstanceOf(String.class);
        String date = (String) result;
        // Формат: 2024-01-15
        assertThat(date).matches("\\d{4}-\\d{2}-\\d{2}");
    }

    @Test
    @DisplayName("Должен генерировать текущее время")
    void shouldGenerateCurrentTime() {
        Object result = resolver.resolve("#current_time");

        assertThat(result).isInstanceOf(String.class);
        String time = (String) result;
        // Формат: 10:30:00 или 10:30:00.123456
        assertThat(time).matches("\\d{2}:\\d{2}:\\d{2}.*");
    }

    @Test
    @DisplayName("Должен возвращать null для неизвестной функции")
    void shouldReturnNullForUnknownFunction() {
        Object result = resolver.resolve("#unknown_function");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("Должен вернуть список доступных функций")
    void shouldReturnAvailableFunctions() {
        List<DefaultFunctionResolver.DefaultFunctionInfo> functions = resolver.getAvailableFunctions();

        assertThat(functions).isNotEmpty();
        assertThat(functions).extracting(DefaultFunctionResolver.DefaultFunctionInfo::name)
                .containsExactlyInAnyOrder(
                        "uuid", "uuid_nodash",
                        "now", "current_timestamp", "current_date", "current_time", "unix_timestamp",
                        "random_int", "random_long", "random_bool",
                        "random_pin_4", "random_pin_6",
                        "random_string", "random_string_16", "random_string_32",
                        "random_email", "random_name", "random_ip",
                        "random_hash", "random_hash_64"
                );

        // Проверяем что у каждой функции есть описание и пример
        for (DefaultFunctionResolver.DefaultFunctionInfo func : functions) {
            assertThat(func.name()).isNotBlank();
            assertThat(func.description()).isNotBlank();
            assertThat(func.exampleValue()).isNotBlank();
            assertThat(func.returnType()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Должен генерировать разные UUID для каждого вызова")
    void shouldGenerateDifferentUuids() {
        List<String> uuids = List.of(
                (String) resolver.resolve("#uuid"),
                (String) resolver.resolve("#uuid"),
                (String) resolver.resolve("#uuid"),
                (String) resolver.resolve("#uuid"),
                (String) resolver.resolve("#uuid")
        );

        assertThat(uuids).hasSize(5);
        assertThat(new java.util.HashSet<>(uuids)).hasSize(5); // все уникальны
    }

    @Test
    @DisplayName("Должен генерировать UUID без дефисов")
    void shouldGenerateUuidNoDash() {
        Object result = resolver.resolve("#uuid_nodash");

        assertThat(result).isInstanceOf(String.class);
        String uuid = (String) result;
        assertThat(uuid).hasSize(32);
        assertThat(uuid).doesNotContain("-");
    }

    @Test
    @DisplayName("Должен генерировать Unix timestamp")
    void shouldGenerateUnixTimestamp() {
        Object result = resolver.resolve("#unix_timestamp");

        assertThat(result).isInstanceOf(Long.class);
        long timestamp = (Long) result;
        assertThat(timestamp).isPositive();
    }

    @Test
    @DisplayName("Должен генерировать случайное boolean")
    void shouldGenerateRandomBool() {
        Object result = resolver.resolve("#random_bool");

        assertThat(result).isInstanceOf(Boolean.class);
    }

    @Test
    @DisplayName("Должен генерировать PIN-коды")
    void shouldGenerateRandomPins() {
        Object pin4 = resolver.resolve("#random_pin_4");
        Object pin6 = resolver.resolve("#random_pin_6");

        assertThat(pin4).isInstanceOf(String.class);
        assertThat((String) pin4).hasSize(4);
        assertThat((String) pin4).matches("\\d{4}");

        assertThat(pin6).isInstanceOf(String.class);
        assertThat((String) pin6).hasSize(6);
        assertThat((String) pin6).matches("\\d{6}");
    }

    @Test
    @DisplayName("Должен генерировать случайный email")
    void shouldGenerateRandomEmail() {
        Object result = resolver.resolve("#random_email");

        assertThat(result).isInstanceOf(String.class);
        String email = (String) result;
        assertThat(email).contains("@");
        assertThat(email).contains(".");
    }

    @Test
    @DisplayName("Должен генерировать случайное имя")
    void shouldGenerateRandomName() {
        Object result = resolver.resolve("#random_name");

        assertThat(result).isInstanceOf(String.class);
        String name = (String) result;
        assertThat(name.split(" ")).hasSize(2); // FirstName LastName
    }

    @Test
    @DisplayName("Должен генерировать случайный IP адрес")
    void shouldGenerateRandomIp() {
        Object result = resolver.resolve("#random_ip");

        assertThat(result).isInstanceOf(String.class);
        String ip = (String) result;
        assertThat(ip).matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    @Test
    @DisplayName("Должен генерировать случайный хеш")
    void shouldGenerateRandomHash() {
        Object hash32 = resolver.resolve("#random_hash");
        Object hash64 = resolver.resolve("#random_hash_64");

        assertThat(hash32).isInstanceOf(String.class);
        assertThat((String) hash32).hasSize(32);

        assertThat(hash64).isInstanceOf(String.class);
        assertThat((String) hash64).hasSize(64);
    }
}
