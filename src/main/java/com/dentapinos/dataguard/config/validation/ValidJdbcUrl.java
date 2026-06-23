package com.dentapinos.dataguard.config.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.Pattern;

import java.lang.annotation.*;

/**
 * Валидатор JDBC URL.
 * <p>
 * Проверяет, что строка соответствует формату JDBC URL для MySQL:
 * <pre>
 * jdbc:mysql://[host][,failoverhost...][:port]/[database][?propertyName1=propertyValue1][&propertyName2=propertyValue2]
 * </pre>
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = {})
@Pattern(
        regexp = "^jdbc:mysql://[^/?]+(:\\d+)?(/[\\w.-]*)?(\\?[\\w=&]*)?$",
        message = "Invalid JDBC URL format. Expected: jdbc:mysql://host:port/database?param=value"
)
public @interface ValidJdbcUrl {

    String message() default "Invalid JDBC URL format";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
