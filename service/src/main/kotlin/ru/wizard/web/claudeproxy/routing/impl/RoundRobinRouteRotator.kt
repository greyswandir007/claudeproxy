package ru.wizard.web.claudeproxy.routing.impl

import ru.wizard.web.claudeproxy.routing.ModelRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Round-robin между равноприоритетными маршрутами: внутри сегмента подряд идущих
 * маршрутов с равными (priority, тип провайдера) сдвигает порядок на шаг курсора,
 * чтобы равнозначные каналы чередовались как primary от запроса к запросу.
 *
 * Порядок сегментов по priority не меняется — более высокий приоритет всегда первичен.
 * Граница по типу провайдера удерживает диспетчеризацию хендлера (она идёт по типу
 * первого маршрута) и не отправляет claude-формат в openai-эндпоинт при fallback.
 *
 * Курсор — [AtomicLong] на publicName: индексы снапшота нестабильны (пересборка раз
 * в минуту и после каждого CRUD), поэтому ротация считается от текущего размера
 * сегмента. Best-effort честность: смена состава сегмента скачет сдвиг — приемлемо.
 * Персистентности нет: рестарт сбрасывает порядок (прецедент — RouteCircuitBreaker).
 */
class RoundRobinRouteRotator {

    private val cursorsByModel = ConcurrentHashMap<String, AtomicLong>()

    /**
     * Возвращает маршруты модели с ротацией равноприоритетных сегментов.
     * Списки без ротируемых сегментов (меньше двух маршрутов или все приоритеты
     * различаются) возвращаются как есть — без сдвига курсора.
     */
    fun rotate(model: String, routes: List<ModelRegistry.Route>): List<ModelRegistry.Route> {
        if (routes.size < 2) return routes
        val segments = segmentsByPriorityAndType(routes)
        if (segments.none { (start, end) -> end - start > 1 }) return routes
        val offset = cursorsByModel.computeIfAbsent(model) { AtomicLong() }
            .getAndIncrement()
            .toInt()
        val rotated = ArrayList<ModelRegistry.Route>(routes.size)
        for ((start, end) in segments) {
            val length = end - start
            if (length == 1) {
                rotated.add(routes[start])
                continue
            }
            val shift = Math.floorMod(offset, length)
            for (position in 0 until length) {
                rotated.add(routes[start + (position + shift) % length])
            }
        }
        return rotated
    }

    /** Границы (start, end) сегментов подряд идущих маршрутов с равными (priority, тип провайдера). */
    private fun segmentsByPriorityAndType(routes: List<ModelRegistry.Route>): List<Pair<Int, Int>> {
        val segments = ArrayList<Pair<Int, Int>>()
        var start = 0
        while (start < routes.size) {
            val priority = routes[start].mapping.priority
            val providerType = routes[start].provider.type
            var end = start + 1
            while (end < routes.size &&
                routes[end].mapping.priority == priority &&
                routes[end].provider.type == providerType
            ) {
                end++
            }
            segments.add(start to end)
            start = end
        }
        return segments
    }
}
