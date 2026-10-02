package ru.wizard.web.claudeproxy.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ResourceLoader
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse
import reactor.core.publisher.Mono
import java.nio.file.Path

/**
 * Веб-конфигурация приложения:
 * - лимит чтения тел запросов/ответов: свойство spring.codec.max-in-memory-size
 *   в Boot 4 к серверным кодекам НЕ применяется (дефолт 256КБ ронял большие
 *   сессии Claude Code ошибкой 413) — поднимаем явно через WebFluxConfigurer;
 * - раздача дашборда: собранный web/dist (npm run build), "/" и /assets.
 *   Resource-handler'ы Boot 4 статику не раздали — работают точечные GET-роуты.
 */
@Configuration
class WebConfiguration(private val resourceLoader: ResourceLoader) : WebFluxConfigurer {

    override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
        configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE_BYTES)
    }

    /** Точечные GET-роуты раздачи дашборда: «/» и /assets/{filename}. */
    @Bean
    fun dashboardRoutes(): RouterFunction<ServerResponse> = RouterFunctions.route()
        .GET("/") { _ -> serveDashboardFile("index.html", MediaType.TEXT_HTML) }
        .GET("/assets/{filename}") { request ->
            val filename = request.pathVariable("filename")
            serveDashboardFile("assets/$filename", contentTypeFor(filename))
        }
        .build()

    private fun serveDashboardFile(relativePath: String, contentType: MediaType): Mono<ServerResponse> {
        val resource = dashboardLocations.firstNotNullOfOrNull { location ->
            resourceLoader.getResource(location + relativePath).takeIf { it.exists() && it.isReadable }
        }
        return if (resource != null) {
            ServerResponse.ok()
                .contentType(contentType)
                .body(BodyInserters.fromResource(resource))
        } else {
            ServerResponse.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(
                    """{"type":"error","error":{"type":"not_found_error",""" +
                        """"message":"Файл дашборда не найден: $relativePath""" +
                        """${if (relativePath == "index.html") " — выполните npm run build в web/" else ""}}"}}""",
                )
        }
    }

    private fun contentTypeFor(filename: String): MediaType = when {
        filename.endsWith(".js") -> MediaType.parseMediaType("text/javascript")
        filename.endsWith(".css") -> MediaType.parseMediaType("text/css")
        filename.endsWith(".map") -> MediaType.APPLICATION_JSON
        filename.endsWith(".svg") -> MediaType.parseMediaType("image/svg+xml")
        filename.endsWith(".png") -> MediaType.parseMediaType("image/png")
        filename.endsWith(".ico") -> MediaType.parseMediaType("image/x-icon")
        else -> MediaType.APPLICATION_OCTET_STREAM
    }

    private companion object {
        /** Claude Code шлёт мегабайты контекста: лимит чтения тел — 64 МБ. */
        const val MAX_IN_MEMORY_SIZE_BYTES = 64 * 1024 * 1024

        /** Абсолютный file-URI обязателен: относительные file: локации не раздаются. */
        val dashboardLocations = listOf(
            "classpath:/static/",
            Path.of("web", "dist").toAbsolutePath().toUri().toString(),
        )
    }
}
