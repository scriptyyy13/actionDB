package vm

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import storage.StorageEngine
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkIntegrationTest {

    private lateinit var storage: StorageEngine
    private lateinit var registry: ActionRegistry
    private lateinit var vm: VirtualMachine
    private lateinit var server: ActionServer
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3))
        .build()

    private val port = 8095
    private val baseUrl = "http://localhost:$port"

    @BeforeEach
    fun setUp() {
        storage = StorageEngine()
        registry = ActionRegistry()

        // Сохраняем счет игрока
        val setScoreScript = listOf(
            mapOf("op" to "SET", "key" to "users.\${in.body.userId}.score", "value" to "\${in.body.score}"),
            mapOf(
                "op" to "RETURN",
                "code" to 200,
                "data" to mapOf("status" to "success", "user" to "\${in.auth.userId}")
            )
        )
        registry.register("player.setScore", setScoreScript)

        // 2. Фоновый экшен-триггер: записывает в логи аудит по изменению пользователей
        val auditTriggerScript = listOf(
            mapOf("op" to "SET", "key" to "audit.last_changed", "value" to "\${in.body.key}")
        )
        registry.register("triggers.audit", auditTriggerScript)

        vm = VirtualMachine(storage, actionRegistry = registry)
        server = ActionServer(vm, registry, storage, port = port)

        // Регистрируем фоновый триггер на префикс "users."
        server.registerTrigger("users.", "triggers.audit")
        server.start(wait = false)
    }

    @AfterEach
    fun tearDown() {
        server.stop()
    }

    // Тест рест апи
    @Test
    fun `test REST API endpoint success and 404 handling`() {
        // Запрос к существующему экшену
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/action/player/setScore"))
            .header("Content-Type", "application/json")
            .header("X-User-Id", "admin_player")
            .POST(HttpRequest.BodyPublishers.ofString("""{"userId": "user_1", "score": 500}"""))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("success"))
        assertTrue(response.body().contains("admin_player"))

        val storedScore = (storage.get("users.user_1.score") as? Number)?.toLong()
        assertEquals(500L, storedScore)

        // Запрос к несуществующему экшену (ожидаем 404)
        val notFoundRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/action/unknown/action"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()

        val notFoundResponse = httpClient.send(notFoundRequest, HttpResponse.BodyHandlers.ofString())
        assertEquals(404, notFoundResponse.statusCode())
        assertTrue(notFoundResponse.body().contains("not found"))
    }

    // Тест фонового триггера
    @Test
    fun `test reactive storage trigger executes background action on storage change`() {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/action/player/setScore"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("""{"userId": "user_777", "score": 100}"""))
            .build()

        httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        // Даем асинхронному слушателю время обработать событие
        Thread.sleep(150)

        // Проверяем, что триггер "triggers.audit" вызвался и записал ключ в "audit.last_changed"
        val lastChanged = storage.get("audit.last_changed")
        assertNotNull(lastChanged)
        assertEquals("users.user_777.score", lastChanged)
    }

    // Тест подписок на изменения
    @Test
    fun `test SSE subscribe endpoint receives real-time storage events`() {
        val receivedEvents = mutableListOf<String>()
        val future = CompletableFuture<Unit>()

        // Асинхронно подключаемся к SSE потоку подписки на "users."
        val sseRequest = HttpRequest.newBuilder()
            .uri(URI.create("$baseUrl/api/subscribe/users"))
            .header("X-User-Id", "user_42")
            .GET()
            .build()

        val sseThread = Thread {
            try {
                httpClient.send(sseRequest, HttpResponse.BodyHandlers.ofLines()).body().forEach { line ->
                    if (line.startsWith("data:")) {
                        synchronized(receivedEvents) {
                            receivedEvents.add(line)
                            if (receivedEvents.size >= 1) {
                                future.complete(Unit)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Игнорируем закрытие сокета при tearDown
            }
        }
        sseThread.start()

        Thread.sleep(1000)

        // Делаем изменения в БД
        storage.set("users.user_42.level", 10)

        // Ждем получения события через SSE поток (максимум 10 секунд)
        future.get(10, TimeUnit.SECONDS)

        synchronized(receivedEvents) {
            assertTrue(receivedEvents.isNotEmpty())
            assertTrue(receivedEvents[0].contains("users.user_42.level"))
        }

        sseThread.interrupt()
    }
}