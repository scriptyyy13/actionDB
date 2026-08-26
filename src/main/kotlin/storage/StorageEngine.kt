package storage

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class StorageEngine {
    // Основная таблица данных в память
    val data = ConcurrentHashMap<String, Any>()

    // Шина реактивных неблокирующих событий
    private val _events = MutableSharedFlow<StorageEvent>(extraBufferCapacity = 20_000)
    val events: SharedFlow<StorageEvent> = _events.asSharedFlow()

    // Префиксы ключей, которые НЕ будут сохраняться на диск
    var ignoredSavePrefixes: MutableList<String> = mutableListOf("lobbies.", "sessions.", "tmp.")

    // Базовые микрокоманды для базы данных

    fun set(key: String, value: Any) {
        val old = data[key]
        data[key] = value
        _events.tryEmit(StorageEvent(StorageEventType.SET, key, old, value))
    }

    fun get(key: String): Any? = data[key]

    fun exists(key: String): Boolean = data.containsKey(key)

    fun delete(key: String): Boolean {
        val old = data.remove(key)
        if (old != null) {
            _events.tryEmit(StorageEvent(StorageEventType.DEL, key, old, null))
            return true
        }
        return false
    }

    /**
     * Возвращает все ключи, соответствующие префиксу (например, "users." или "lobbies.")
     */
    fun getKeysByPrefix(prefix: String): List<String> {
        return data.keys().toList().filter { it.startsWith(prefix) }
    }

    /**
     * Очистка всей базы данных
     */
    fun clear() {
        data.clear()
    }

    /**
     * Атомарный инкремент/декремент.
     * Безопасен при одновременном вызове из экшенов.
     */
    fun increment(key: String, delta: Double): Double {
        var newValue = delta
        var oldValue: Any? = null

        data.compute(key) { _, current ->
            oldValue = current
            val num = (current as? Number)?.toDouble() ?: 0.0
            newValue = num + delta
            newValue
        }

        _events.tryEmit(StorageEvent(StorageEventType.INC, key, oldValue, newValue))
        return newValue
    }

    // Далее атомарные операции со списками
    /**
     * Добавление элемента в конец списка по ключу
     */
    fun arrayPush(key: String, element: Any) {
        var updatedList: List<Any> = emptyList()
        var oldValue: Any? = null

        data.compute(key) { _, current ->
            oldValue = current
            @Suppress("UNCHECKED_CAST")
            val list = (current as? List<Any>)?.toMutableList() ?: mutableListOf()
            list.add(element)
            updatedList = list
            list
        }

        _events.tryEmit(StorageEvent(StorageEventType.ARRAY_PUSH, key, oldValue, updatedList))
    }

    /**
     * Добавление в начало массива
     */
    fun arrayUnshift(key: String, element: Any) {
        var updatedList: List<Any> = emptyList()
        var oldValue: Any? = null

        data.compute(key) { _, current ->
            oldValue = current
            @Suppress("UNCHECKED_CAST")
            val list = (current as? List<Any>)?.toMutableList() ?: mutableListOf()
            list.add(0, element)
            updatedList = list
            list
        }

        _events.tryEmit(StorageEvent(StorageEventType.ARRAY_PUSH, key, oldValue, updatedList))
    }

    /**
     * Удаление элемента из списка по значению
     */
    fun arrayRemove(key: String, element: Any): Boolean {
        var removed = false
        var updatedList: List<Any> = emptyList()
        var oldValue: Any? = null

        data.compute(key) { _, current ->
            oldValue = current
            @Suppress("UNCHECKED_CAST")
            val list = (current as? List<Any>)?.toMutableList() ?: mutableListOf()
            removed = list.remove(element)
            updatedList = list
            list
        }

        if (removed) {
            _events.tryEmit(StorageEvent(StorageEventType.ARRAY_REMOVE, key, oldValue, updatedList))
        }

        return removed
    }

    /**
     * Извлечь и удалить последний элемент.
     */
    fun arrayPop(key: String): Any? {
        var popped: Any? = null
        data.compute(key) { _, current ->
            @Suppress("UNCHECKED_CAST")
            val list = (current as? List<Any>)?.toMutableList() ?: return@compute current
            if (list.isNotEmpty()) {
                popped = list.removeAt(list.size - 1)
            }
            list
        }
        return popped
    }

    /**
     * Извлечь и удалить первый элемент.
     */
    fun arrayShift(key: String): Any? {
        var shifted: Any? = null
        data.compute(key) { _, current ->
            @Suppress("UNCHECKED_CAST")
            val list = (current as? List<Any>)?.toMutableList() ?: return@compute current
            if (list.isNotEmpty()) {
                shifted = list.removeAt(0)
            }
            list
        }
        return shifted
    }

    /**
     * Длина массива по ключу
     */
    fun arrayLen(key: String): Int {
        @Suppress("UNCHECKED_CAST")
        val list = data[key] as? List<Any>
        return list?.size ?: 0
    }

    /**
     * Точечное обновление поля внутри Map (например, users.10 -> field "score")
     */
    fun setInMap(key: String, field: String, value: Any) {
        data.compute(key) { _, current ->
            @Suppress("UNCHECKED_CAST")
            val map = (current as? Map<String, Any>)?.toMutableMap() ?: mutableMapOf()
            map[field] = value
            map
        }
        _events.tryEmit(StorageEvent(StorageEventType.SET, "$key.$field", null, value))
    }

    // Операции сохранения
    /**
     * Восстанавливает базу из снапшота на диске при старте
     */
    fun loadSnapshot(filePath: String = "./data/dump.json") {
        val loadedData = StoragePersistence.loadFromFile(filePath)
        if (loadedData != null) {
            data.clear()
            data.putAll(loadedData)
            println("БД успешно восстановлена из $filePath (${data.size} ключей)")
        }
    }

    /**
     * Сохраняет текущее состояние памяти на диск
     */
    fun saveSnapshot(filePath: String = "./data/dump.json") {
        StoragePersistence.saveToFile(
            data = data,
            ignoredPrefixes = ignoredSavePrefixes,
            filePath = filePath
        )
    }
}