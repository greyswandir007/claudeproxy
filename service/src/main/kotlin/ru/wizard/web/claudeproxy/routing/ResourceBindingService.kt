package ru.wizard.web.claudeproxy.routing

/**
 * Привязка upstream-ресурсов (батчей, файлов) к провайдеру, на котором они
 * созданы: последующие запросы по идентификатору ресурса маршрутизируются
 * напрямую, без выбора маршрута по модели — ресурс живёт у провайдера.
 */
interface ResourceBindingService {

    /** Запомнить провайдера, на котором создан ресурс. */
    suspend fun bind(resourceType: ResourceType, resourceId: String, providerName: String)

    /** Провайдер ресурса; null, если привязки нет (создан вне прокси). */
    suspend fun providerNameOf(resourceType: ResourceType, resourceId: String): String?

    /** Убрать привязку (ресурс удалён у провайдера). */
    suspend fun forget(resourceType: ResourceType, resourceId: String)

    /**
     * Отметить строку результатов батча учтённой; false — уже учтена ранее
     * (повторное чтение results не должно дублировать usage).
     */
    suspend fun rememberBatchResult(batchId: String, customId: String): Boolean

    enum class ResourceType { MESSAGE_BATCH, FILE }
}
