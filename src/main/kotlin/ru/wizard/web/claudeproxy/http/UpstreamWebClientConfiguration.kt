package ru.wizard.web.claudeproxy.http

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient

/**
 * Общий WebClient для запросов к провайдерам (Reactor Netty).
 * В Boot 4 авто-конфигурации WebClient.Builder в starter-webflux нет — собираем сами.
 * Лимит буфера поднят до 64 МБ: ответы Claude Code бывают мегабайтными.
 */
@Configuration
class UpstreamWebClientConfiguration {

    @Bean
    fun upstreamWebClient(): WebClient =
        WebClient.builder()
            .clientConnector(ReactorClientHttpConnector(HttpClient.create()))
            .codecs { it.defaultCodecs().maxInMemorySize(64 * 1024 * 1024) }
            .build()
}
