package storage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File

object StoragePersistence {
    private val json = Json { prettyPrint = true }

    /**
     * Сохраняет данные в JSON-файл, игнорируя ключи с забаненными префиксами.
     */
    fun saveToFile(
        data: Map<String, Any>,
        ignoredPrefixes: List<String> = listOf("lobbies.", "sessions.", "tmp."),
        filePath: String = "./data/dump.json"
    ) {
        try {
            val file = File(filePath)
            file.parentFile?.mkdirs()

            // Фильтруем забаненные префиксы
            val filteredData = data.filterKeys { key ->
                ignoredPrefixes.none { prefix -> key.startsWith(prefix) }
            }

            // Преобразуем Map<String, Any> в JsonObject
            val jsonObject = toJsonElement(filteredData) as JsonObject

            // Сериализуем готовый json
            val jsonString = json.encodeToString<JsonElement>(jsonObject)
            file.writeText(jsonString)
        } catch (e: Exception) {
            System.err.println("Ошибка при сохранении снапшота БД: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Загружает данные из JSON-файла
     */
    fun loadFromFile(filePath: String = "./data/dump.json"): Map<String, Any>? {
        val file = File(filePath)
        if (!file.exists()) return null

        return try {
            val jsonString = file.readText()
            val jsonElement = json.parseToJsonElement(jsonString)

            @Suppress("UNCHECKED_CAST")
            fromJsonElement(jsonElement) as? Map<String, Any>
        } catch (e: Exception) {
            System.err.println("Ошибка при чтении снапшота БД: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    // Помощь конвертации

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.mapKeys { it.key.toString() }.mapValues { toJsonElement(it.value) })
        is List<*> -> JsonArray(value.map { toJsonElement(it) })
        else -> JsonPrimitive(value.toString())
    }

    private fun fromJsonElement(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> {
            if (element.isString) {
                element.content
            } else {
                element.booleanOrNull
                    ?: element.longOrNull
                    ?: element.doubleOrNull
                    ?: element.content
            }
        }
        is JsonArray -> element.map { fromJsonElement(it) }
        is JsonObject -> element.mapValues { fromJsonElement(it.value) }
    }
}