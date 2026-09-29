package ru.wizard.web.claudeproxy.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.TextNode

/**
 * Каноническая сериализация JSON для стабильного хэширования: поля объектов
 * выдаются в сортированном порядке, массивы — в исходном. Одинаковые по смыслу
 * тела, сериализованные разными SDK с разным порядком ключей, дают одну и ту
 * же строку (используется кэшем запросов и ключом разговора sticky-аффинности).
 */
internal object CanonicalJsonSerializer {

    fun serialize(node: JsonNode): String = when {
        node.isObject -> {
            val fields = ArrayList<Map.Entry<String, JsonNode>>(node.size())
            node.fields().forEach { fields.add(it) }
            fields.sortBy { it.key }
            fields.joinToString(separator = ",", prefix = "{", postfix = "}") { (name, value) ->
                TextNode.valueOf(name).toString() + ":" + serialize(value)
            }
        }
        node.isArray -> node.joinToString(separator = ",", prefix = "[", postfix = "]") { serialize(it) }
        else -> node.toString()
    }
}
