package ru.wizard.web.claudeproxy.routing.impl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import ru.wizard.web.claudeproxy.routing.ModelRegistry

/**
 * Юнит-тесты ротатора равноприоритетных маршрутов. Контекст Spring не поднимается.
 */
class RoundRobinRouteRotatorTest {

    private val rotator = RoundRobinRouteRotator()

    private var nextProviderId = 1L

    private fun route(
        name: String,
        priority: Int,
        type: String = "anthropic",
    ): ModelRegistry.Route {
        val provider = ModelRegistry.ProviderInfo(
            id = nextProviderId++,
            name = name,
            type = type,
            baseUrl = "http://$name.test",
            apiKey = "secret",
            authType = "api_key",
            extraHeaders = emptyMap(),
            effortMapping = emptyMap(),
            settingOverrides = emptyMap(),
        )
        val mapping = ModelRegistry.ModelInfo(
            publicName = "public-model",
            upstreamName = "$name-upstream",
            reasoning = "map",
            maxCompletionParam = false,
            priority = priority,
        )
        return ModelRegistry.Route(provider, mapping)
    }

    private fun names(routes: List<ModelRegistry.Route>): List<String> = routes.map { it.provider.name }

    @Test
    fun `одиночный маршрут возвращается как есть`() {
        val routes = listOf(route("one", priority = 10))
        assertSame(routes, rotator.rotate("m", routes))
    }

    @Test
    fun `разные приоритеты не ротируются`() {
        val routes = listOf(route("one", priority = 10), route("two", priority = 20))
        assertSame(routes, rotator.rotate("m", routes))
        assertSame(routes, rotator.rotate("m", routes))
    }

    @Test
    fun `одиночный вызов не сдвигает курсор модели`() {
        val single = listOf(route("one", priority = 10))
        rotator.rotate("m", single)
        // курсор не тратится на no-op: первая ротация пары стартует с базового порядка
        val pair = listOf(route("one", priority = 10), route("two", priority = 10))
        assertEquals(listOf("one", "two"), names(rotator.rotate("m", pair)))
    }

    @Test
    fun `равные приоритеты чередуют порядок двух маршрутов`() {
        val routes = listOf(route("one", priority = 10), route("two", priority = 10))
        assertEquals(listOf("one", "two"), names(rotator.rotate("m", routes)))
        assertEquals(listOf("two", "one"), names(rotator.rotate("m", routes)))
        assertEquals(listOf("one", "two"), names(rotator.rotate("m", routes)))
        assertEquals(listOf("two", "one"), names(rotator.rotate("m", routes)))
    }

    @Test
    fun `равные приоритеты циклически вращают три маршрута`() {
        val routes = listOf(
            route("one", priority = 10),
            route("two", priority = 10),
            route("three", priority = 10),
        )
        assertEquals(listOf("one", "two", "three"), names(rotator.rotate("m", routes)))
        assertEquals(listOf("two", "three", "one"), names(rotator.rotate("m", routes)))
        assertEquals(listOf("three", "one", "two"), names(rotator.rotate("m", routes)))
        assertEquals(listOf("one", "two", "three"), names(rotator.rotate("m", routes)))
    }

    @Test
    fun `ротация не смешивает типы провайдеров`() {
        val routes = listOf(
            route("anthropic-one", priority = 10, type = "anthropic"),
            route("openai-one", priority = 10, type = "openai"),
            route("anthropic-two", priority = 10, type = "anthropic"),
        )
        // каждый тип — свой сегмент; первый маршрут остаётся anthropic при любом сдвиге
        assertSame(routes, rotator.rotate("m", routes))
        assertSame(routes, rotator.rotate("m", routes))
    }

    @Test
    fun `соседние однотипные маршруты ротируются внутри сегмента`() {
        val routes = listOf(
            route("anthropic-one", priority = 10, type = "anthropic"),
            route("anthropic-two", priority = 10, type = "anthropic"),
            route("openai-one", priority = 10, type = "openai"),
        )
        assertEquals(listOf("anthropic-one", "anthropic-two", "openai-one"), names(rotator.rotate("m", routes)))
        assertEquals(listOf("anthropic-two", "anthropic-one", "openai-one"), names(rotator.rotate("m", routes)))
    }

    @Test
    fun `строгий приоритет доминирует над ротацией`() {
        val routes = listOf(
            route("primary", priority = 5),
            route("secondary-one", priority = 10),
            route("secondary-two", priority = 10),
        )
        assertEquals(listOf("primary", "secondary-one", "secondary-two"), names(rotator.rotate("m", routes)))
        assertEquals(listOf("primary", "secondary-two", "secondary-one"), names(rotator.rotate("m", routes)))
    }

    @Test
    fun `входной список не мутируется`() {
        val routes = listOf(route("one", priority = 10), route("two", priority = 10))
        val snapshot = routes.toList()
        rotator.rotate("m", routes)
        assertEquals(snapshot, routes)
    }

    @Test
    fun `sticky поднимает привязанного в голову своего сегмента`() {
        val routes = listOf(
            route("one", priority = 10),
            route("two", priority = 10),
            route("three", priority = 10),
        )
        assertEquals(listOf("two", "one", "three"), names(rotator.stickyOrder(routes, "two")!!))
    }

    @Test
    fun `sticky не пересекает границу приоритета`() {
        val routes = listOf(
            route("primary", priority = 5),
            route("secondary-one", priority = 10),
            route("secondary-two", priority = 10),
        )
        // привязка к строгому приоритету: сегмент одиночный, продвижения нет
        assertNull(rotator.stickyOrder(routes, "primary"))
        // привязка во втором сегменте: головной сегмент не тронут
        assertEquals(
            listOf("primary", "secondary-two", "secondary-one"),
            names(rotator.stickyOrder(routes, "secondary-two")!!),
        )
    }

    @Test
    fun `sticky отсутствующего провайдера неприменим`() {
        val routes = listOf(route("one", priority = 10), route("two", priority = 10))
        assertNull(rotator.stickyOrder(routes, "missing"))
    }

    @Test
    fun `sticky не сдвигает курсор ротации`() {
        val routes = listOf(route("one", priority = 10), route("two", priority = 10))
        rotator.stickyOrder(routes, "two")
        // курсор не потрачен на sticky-запрос: следующая ротация стартует с базового порядка
        assertEquals(listOf("one", "two"), names(rotator.rotate("m", routes)))
    }
}
