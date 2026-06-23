package com.dentapinos.dataguard.dto;

import org.springframework.context.annotation.Description;

import java.util.List;

/**
 * DTO для ответа со списком таблиц.
 */
public record TablesResponse(

        @Description("Список имён таблиц в базе данных")
        List<String> tables
) {}
