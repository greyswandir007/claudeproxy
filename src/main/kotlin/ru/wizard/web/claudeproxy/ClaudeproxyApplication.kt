package ru.wizard.web.claudeproxy

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class ClaudeproxyApplication

fun main(args: Array<String>) {
	runApplication<ClaudeproxyApplication>(*args)
}
