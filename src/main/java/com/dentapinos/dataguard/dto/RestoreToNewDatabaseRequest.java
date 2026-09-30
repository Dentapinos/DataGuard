package com.dentapinos.dataguard.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Запрос на восстановление в новую базу данных.
 * <p>
 * Создаёт новую базу данных на MySQL и восстанавливает в неё данные из бэкапа.
 * Используется для изолированных тестов, развёртывания или миграции.
 * </p>
 * <p>
 * <b>Важно:</b> Восстановление выполняется в режиме STRICT — схема бэкапа должна совпадать с создаваемой БД.
 * </p>
 */
@Schema(description = "Запрос на восстановление данных из резервной копии в новую базу данных")
public record RestoreToNewDatabaseRequest(

        @Schema(
                description = "Имя ZIP-файла бэкапа в хранилище",
                example = "center_beer_2024-06-15_02-30-00.zip"
        )
        String backupName,

        @Schema(
                description = "Имя создаваемой базы данных. Возвращает 409, если база уже существует",
                example = "restored_db"
        )
        String newDatabaseName,

        @Schema(
                description = "Учётные данные для подключения к MySQL-серверу. Используются для создания новой базы данных и подключения к ней.",
                example = "{\"url\": \"jdbc:mysql://localhost:3306/restored_db\", \"username\": \"root\", \"password\": \"password123\"}"
        )
        DbCredentials newDatabaseCredentials
) {}
