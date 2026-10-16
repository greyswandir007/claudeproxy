package ru.wizard.web.claudeproxy.config.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import net.logstash.logback.composite.AbstractFieldJsonProvider

/**
 * Провайдер поля exception: класс исключения (без трейса) — по нему
 * ошибки фильтруют и группируют, полный стек остаётся в stackTrace.
 * Правило файловых JSON-логов (2026-10-15).
 */
class ExceptionClassNameProvider : AbstractFieldJsonProvider<ILoggingEvent>() {

    override fun writeTo(
        generator: com.fasterxml.jackson.core.JsonGenerator,
        event: ILoggingEvent,
    ) {
        val throwable = event.throwableProxy ?: return
        generator.writeStringField(fieldName, throwable.className)
    }
}
