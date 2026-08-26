package vm

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File

class ActionRegistry(
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
) {
    private val actions = mutableMapOf<String, List<Map<String, Any?>>>()

    /**
     * Регистрирует экшен вручную из кода
     */
    fun register(name: String, script: List<Map<String, Any?>>) {
        actions[name] = script
    }

    /**
     * Загружает один JSON файл экшена.
     * Имя экшена берётся из имени файла без расширения (например, "user.login.json" -> "user.login")
     */
    fun loadFromFile(file: File) {
        if (!file.exists() || !file.name.endsWith(".json")) return
        val actionName = file.nameWithoutExtension
        val script: List<Map<String, Any?>> = objectMapper.readValue(file)
        register(actionName, script)
    }

    /**
     * Сканирует директорию и рекурсивно загружает все .json файлы.
     * Если файл лежит в папке "user/login.json", именем станет "user.login"
     */
    fun loadFromDirectory(dir: File, basePrefix: String = "") {
        if (!dir.exists() || !dir.isDirectory) return

        dir.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                val newPrefix = if (basePrefix.isEmpty()) file.name else "$basePrefix.${file.name}"
                loadFromDirectory(file, newPrefix)
            } else if (file.isFile && file.extension == "json") {
                val actionName =
                    if (basePrefix.isEmpty()) file.nameWithoutExtension else "$basePrefix.${file.nameWithoutExtension}"
                val script: List<Map<String, Any?>> = objectMapper.readValue(file)
                register(actionName, script)
            }
        }
    }

    /**
     * Получить скрипт экшена по имени
     */
    fun getAction(name: String): List<Map<String, Any?>>? = actions[name]

    /**
     * Список всех зарегистрированных экшенов
     */
    fun listActions(): Set<String> = actions.keys
}