package com.dentapinos.dataguard.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.servers.Server;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * <p></p>Конфигурация OpenAPI (Swagger) для API документации.
 * </p>
 * Проект: DataGuard - Защитник Данных
 * Автор: Dentapinos (<a href="https://github.com/Dentapinos">gitHub</a>)
 * Контакт: Ladchenkovd@gmail.com
 */
@Slf4j
@Configuration
@Validated
@ConfigurationProperties(prefix = "app.server")
@Getter
@Setter
public class OpenApiConfig {

    @NotBlank(message = "app.server.base-url must be specified")
    @Value("${app.server.base-url:http://localhost:8080}")
    private String baseUrl;

    @NotBlank(message = "app.server.email must be specified")
    @Email(message = "app.server.email must be a valid email address")
    private String email = "Ladchenkovden@gmail.com";

    @NestedConfigurationProperty
    @Valid
    private ApiInfo apiInfo = new ApiInfo();

    @NestedConfigurationProperty
    @Valid
    private ServerInfo serverInfo = new ServerInfo();

    /**
     * Настройки информации об API.
     */
    @Getter
    @Setter
    public static class ApiInfo {
        @NotBlank(message = "apiInfo.title must be specified")
        private String title = "DataGuard - Защитник Данных";

        @NotBlank(message = "apiInfo.description must be specified")
        private String description = "API сервиса резервного копирования и восстановления баз данных (DataGuard)";

        @NotBlank(message = "apiInfo.version must be specified")
        private String version = "1.0.0";

        @Email(message = "apiInfo.contact.email must be a valid email address")
        private String contactEmail = "Ladchenkovd@gmail.com";
    }

    /**
     * Настройки сервера.
     */
    @Getter
    @Setter
    public static class ServerInfo {
        @NotBlank(message = "serverInfo.description must be specified")
        private String description = "Production server";
    }

    /**
     * Бин OpenAPI для документации.
     */
    @Bean
    public OpenAPI openAPI() {
        Contact contact = new Contact()
                .name("Dentapinos")
                .email("Ladchenkovd@gmail.com")
                .url("https://github.com/Dentapinos");

        return new OpenAPI()
                .info(new Info()
                        .title(apiInfo.getTitle())
                        .description(apiInfo.getDescription())
                        .version(apiInfo.getVersion())
                        .contact(contact))
                .servers(List.of(
                        new Server()
                                .url(baseUrl)
                                .description(serverInfo.getDescription())
                ));
    }

    /**
     * Конфигурация для разрешения статических ресурсов Swagger UI.
     * Используется для корректной работы swagger-ui.html в Spring Boot 3.x
     */
    @Bean
    public WebMvcConfigurer webMvcConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/swagger-ui/**")
                        .addResourceLocations("classpath:/META-INF/resources/webjars/");
                registry.addResourceHandler("/v3/api-docs/**")
                        .addResourceLocations("classpath:/META-INF/resources/");
            }
        };
    }
}
