package ru.wizard.web.claudeproxy.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * ObjectMapper для разбора запросов/ответов (JsonNode, без строгих DTO).
 * В Boot 4 starter-webflux не приносит авто-конфигурацию Jackson — создаём сами.
 * Лимит длины одной JSON-строки снят: base64-картинки бывают мегабайтными
 * (дефолт Jackson — 20M символов).
 */
@Configuration
class JacksonConfiguration {

    /** Единственный ObjectMapper приложения (JsonNode-разбор без строгих DTO). */
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper().apply {
        factory.setStreamReadConstraints(
            com.fasterxml.jackson.core.StreamReadConstraints.builder()
                .maxStringLength(Int.MAX_VALUE)
                .build(),
        )
    }
}
