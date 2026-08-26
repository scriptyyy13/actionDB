package storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageEngineTest {

    // Тесты на базовые функции
    @Test
    fun `test set get delete and exists`() {
        val storage = StorageEngine()

        // 1. Проверяем отсутствие несуществующего ключа
        assertFalse(storage.exists("user.100"))
        assertNull(storage.get("user.100"))

        // 2. Устанавливаем значение
        storage.set("user.100", mapOf("name" to "Alex", "age" to 25))
        assertTrue(storage.exists("user.100"))
        assertEquals(mapOf("name" to "Alex", "age" to 25), storage.get("user.100"))

        // 3. Удаляем ключ
        val deleted = storage.delete("user.100")
        assertTrue(deleted)
        assertFalse(storage.exists("user.100"))
        assertNull(storage.get("user.100"))

        // 4. Повторное удаление возвращает false
        assertFalse(storage.delete("user.100"))
    }

    @Test
    fun `test getKeysByPrefix and clear`() {
        val storage = StorageEngine()

        storage.set("lobbies.room1", "data1")
        storage.set("lobbies.room2", "data2")
        storage.set("users.usr1", "data3")

        val lobbyKeys = storage.getKeysByPrefix("lobbies.")
        assertEquals(2, lobbyKeys.size)
        assertTrue(lobbyKeys.containsAll(listOf("lobbies.room1", "lobbies.room2")))

        // Проверяем полную очистку базы
        storage.clear()
        assertEquals(0, storage.getKeysByPrefix("").size)
        assertFalse(storage.exists("users.usr1"))
    }

    // Тесты на работу со списками и мапами

    @Test
    fun `test arrayPush and arrayRemove`() {
        val storage = StorageEngine()
        val key = "lobby.102.players"

        // Push в несуществующий ключ создает новый список
        storage.arrayPush(key, "usr_1")
        storage.arrayPush(key, "usr_2")
        storage.arrayPush(key, "usr_3")

        @Suppress("UNCHECKED_CAST")
        val players = storage.get(key) as List<String>
        assertEquals(listOf("usr_1", "usr_2", "usr_3"), players)

        // Удаление элемента из списка
        val removed = storage.arrayRemove(key, "usr_2")
        assertTrue(removed)

        @Suppress("UNCHECKED_CAST")
        val updatedPlayers = storage.get(key) as List<String>
        assertEquals(listOf("usr_1", "usr_3"), updatedPlayers)

        // Попытка удалить несуществующий элемент
        val removedNonExisting = storage.arrayRemove(key, "usr_999")
        assertFalse(removedNonExisting)
    }

    @Test
    fun `test setInMap`() {
        val storage = StorageEngine()
        val key = "player.stats"

        // Точечно обновляем поля
        storage.setInMap(key, "kills", 10)
        storage.setInMap(key, "deaths", 2)

        @Suppress("UNCHECKED_CAST")
        val stats = storage.get(key) as Map<String, Any>
        assertEquals(10, stats["kills"])
        assertEquals(2, stats["deaths"])
    }

    // Тесты событий

    @Test
    fun `test storage events emission`() = runBlocking {
        val storage = StorageEngine()
        val collectedEvents = mutableListOf<StorageEvent>()

        val job = launch(Dispatchers.Unconfined) {
            storage.events.take(4).toList(collectedEvents)
        }

        storage.set("test.key", "val1")
        storage.increment("test.key_num", 5.0)
        storage.arrayPush("test.list", "item1")
        storage.delete("test.key")

        job.join() // Подписка завершается сразу после 4-го события

        assertEquals(4, collectedEvents.size)
        assertEquals(StorageEventType.SET, collectedEvents[0].type)
        assertEquals("test.key", collectedEvents[0].key)

        assertEquals(StorageEventType.INC, collectedEvents[1].type)
        assertEquals(5.0, collectedEvents[1].newValue)

        assertEquals(StorageEventType.ARRAY_PUSH, collectedEvents[2].type)
        assertEquals(StorageEventType.DEL, collectedEvents[3].type)
    }

    // Тесты корректного восстановления с игнором префиксов

    @Test
    fun `test persistence with ignored prefixes`() {
        val storage = StorageEngine()
        val testDumpPath = "./data/test_dump.json"

        try {
            // Заполняем постоянными и временными данными
            storage.set("users.usr_1", mapOf("name" to "Bob"))
            storage.set("inventory.usr_1", listOf("sword", "shield"))
            storage.set("lobbies.room_1", "active_game_state")
            storage.set("sessions.token_123", "user_session")

            // Сохраняем на диск
            storage.saveSnapshot(testDumpPath)

            // Создаем новый чистый инстанс БД и восстанавливаем
            val newStorage = StorageEngine()
            newStorage.loadSnapshot(testDumpPath)

            // Проверяем, что постоянные данные восстановились
            assertTrue(newStorage.exists("users.usr_1"))
            assertTrue(newStorage.exists("inventory.usr_1"))

            // Проверяем, что забаненные префиксы (lobbies., sessions.) НЕ сохранились!
            assertFalse(newStorage.exists("lobbies.room_1"))
            assertFalse(newStorage.exists("sessions.token_123"))

        } finally {
            // Подчищаем за собой тестовый файл
            File(testDumpPath).delete()
        }
    }

    // Тесты гонки потоков

    @Test
    fun `test concurrent increments and array pushes`() = runBlocking {
        val storage = StorageEngine()
        val counterKey = "global.counter"
        val listKey = "global.items"

        val threadsCount = 10
        val operationsPerThread = 10

        // Запускаем 10 параллельных корутин, каждая из которых делает по 10 операций
        val jobs = List(threadsCount) { threadId ->
            async(Dispatchers.Default) {
                repeat(operationsPerThread) {
                    storage.increment(counterKey, 1.0)
                    storage.arrayPush(listKey, "item_${threadId}")
                }
            }
        }

        jobs.awaitAll() // Ждем завершения всех 100 операций

        // Проверяем, что ни один инкремент и ни одна вставка в массив не потерялись
        val expectedTotal = (threadsCount * operationsPerThread).toDouble()
        assertEquals(expectedTotal, storage.get(counterKey))

        @Suppress("UNCHECKED_CAST")
        val list = storage.get(listKey) as List<String>
        assertEquals(threadsCount * operationsPerThread, list.size)
    }

    @Test
    fun `test deque operations arrayUnshift arrayPop arrayShift and arrayLen`() {
        val storage = StorageEngine()
        val key = "queue.players"

        // Проверяем начальную длину
        assertEquals(0, storage.arrayLen(key))

        // Наполняем очередь через arrayPush (в конец) и arrayUnshift (в начало)
        storage.arrayPush(key, "player_2") // [player_2]
        storage.arrayPush(key, "player_3") // [player_2, player_3]
        storage.arrayUnshift(key, "player_1") // [player_1, player_2, player_3]

        assertEquals(3, storage.arrayLen(key))

        // Проверяем FIFO извлечение через arrayShift (с начала)
        val shifted = storage.arrayShift(key)
        assertEquals("player_1", shifted)
        assertEquals(2, storage.arrayLen(key))

        // Проверяем LIFO извлечение через arrayPop (с конца)
        val popped = storage.arrayPop(key)
        assertEquals("player_3", popped)
        assertEquals(1, storage.arrayLen(key))

        // Остался только player_2
        assertEquals("player_2", storage.arrayShift(key))
        assertEquals(0, storage.arrayLen(key))

        // Извлечение из пустого массива возвращает null
        assertNull(storage.arrayPop(key))
        assertNull(storage.arrayShift(key))
    }
}