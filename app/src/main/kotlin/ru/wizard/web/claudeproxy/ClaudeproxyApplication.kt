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
	// файловый JSON-лог (правило 2026-10-15): имя по времени старта,
	// задаётся ДО инициализации логирования Spring
	System.setProperty("LOG_FILE_BASE", "claudeproxy-" + java.time.LocalDateTime.now().format(LOG_NAME_FORMATTER))
	java.nio.file.Files.createDirectories(java.nio.file.Path.of("logs"))
	runApplication<ClaudeproxyApplication>(*args)
}

private val LOG_NAME_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
