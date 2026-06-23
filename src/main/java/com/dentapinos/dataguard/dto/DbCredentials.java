package com.dentapinos.dataguard.dto;


import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Параметры подключения к базе данных.
 * <p>
 * Используется во всех запросах, где нужно передать данные для подключения
 * к СУБД (URL, имя пользователя и пароль). Позволяет избежать дублирования
 * одинаковых полей в разных DTO.
 */
@Schema(description = "Параметры подключения к базе данных (URL, пользователь, пароль)")
public record DbCredentials(

        @Schema(
                description = "JDBC URL для подключения к базе данных или серверу БД",
                example = "jdbc:mysql://localhost:3306/center_beer?useSSL=false&serverTimezone=UTC"
        )
        String url,

        @Schema(
                description = "Имя пользователя базы данных",
                example = "backup_user"
        )
        String username,

        @Schema(
                description = "Пароль пользователя базы данных",
                example = "secret-password"
        )
        String password
) {}
