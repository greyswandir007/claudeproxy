package ru.wizard.web.claudeproxy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/** Точка входа claudeproxy: WebFlux-прокси к LLM-провайдерам с дашбордом. */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
class ClaudeproxyApplication

/** Стандартный запуск Spring Boot. */
fun main(args: Array<String>) {
	runApplication<ClaudeproxyApplication>(*args)
}
