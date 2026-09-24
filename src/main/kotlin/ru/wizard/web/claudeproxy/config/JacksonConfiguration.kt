package ru.wizard.web.claudeproxy.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * ObjectMapper для разбора запросов/ответов (JsonNode, без строгих DTO).
 * В Boot 4 starter-webflux не приносит авто-конфигурацию Jackson — создаём сами.
 */
@Configuration
class JacksonConfiguration {

    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()
}
