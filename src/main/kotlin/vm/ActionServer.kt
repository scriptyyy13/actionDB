package vm

import io.ktor.http.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filter
import storage.StorageEngine

class ActionServer(
    private val vm: VirtualMachine,
    private val actionRegistry: ActionRegistry,
    private val storage: StorageEngine,
    private val port: Int = 8080
) {
    private var server: NettyApplicationEngine? = null
    private val serverScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Карты авто-триггеров: keyPrefix -> actionName (например, "users." -> "triggers.on_user_update")
    private val triggers = mutableMapOf<String, String>()

    /**
     * Регистрирует авто-запуск экшена при изменении ключей с указанным префиксом
     */
    fun registerTrigger(keyPrefix: String, actionName: String) {
        triggers[keyPrefix] = actionName
    }

    fun start(wait: Boolean = false) {
        startStorageEventListener()

        server = embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                jackson()
            }

            routing {
                // Выполнение REST экшенов (POST)
                post("/api/action/{actionName...}") {
                    val pathSegments = call.parameters.getAll("actionName") ?: emptyList()
                    val actionName = pathSegments.joinToString(".")

                    if (actionName.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Action name is required"))
                        return@post
                    }

                    val script = actionRegistry.getAction(actionName)
                    if (script == null) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Action '$actionName' not found"))
                        return@post
                    }

                    @Suppress("UNCHECKED_CAST")
                    val bodyMap = try {
                        call.receive<Map<String, Any?>>()
                    } catch (_: Exception) {
                        emptyMap()
                    }

                    val queryMap = call.request.queryParameters.entries().associate {
                        it.key to (it.value.firstOrNull() ?: "")
                    }

                    val authCtx = extractAuthContext(call)

                    val ctx = ExecutionContext(
                        body = bodyMap,
                        query = queryMap,
                        auth = authCtx
                    )

                    vm.execute(script, ctx)

                    val httpStatus = HttpStatusCode.fromValue(ctx.responseCode)

                    if (ctx.errorMessage != null) {
                        call.respond(httpStatus, mapOf("error" to ctx.errorMessage))
                    } else {
                        call.respond(httpStatus, ctx.responseData ?: mapOf("status" to "ok"))
                    }
                }

                // Динамическая SSE подписка с контролем префикса из ВМ (GET)
                get("/api/subscribe/{actionName...}") {
                    val pathSegments = call.parameters.getAll("actionName") ?: emptyList()
                    val actionName = pathSegments.joinToString(".")

                    if (actionName.isEmpty()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Subscription action name is required"))
                        return@get
                    }

                    val authCtx = extractAuthContext(call)
                    val queryMap = call.request.queryParameters.entries().associate {
                        it.key to (it.value.firstOrNull() ?: "")
                    }

                    var targetPrefix: String? = null

                    // Проверяем наличие специального auth-экшена подписки (например, "test.auth")
                    val authActionName = "$actionName.auth"
                    val authScript = actionRegistry.getAction(authActionName)

                    if (authScript != null) {
                        val authCheckCtx = ExecutionContext(
                            query = queryMap,
                            auth = authCtx
                        )
                        vm.execute(authScript, authCheckCtx)

                        // Если авторизация не прошла
                        if (authCheckCtx.responseCode >= 400) {
                            val status = HttpStatusCode.fromValue(authCheckCtx.responseCode)
                            call.respond(status, mapOf("error" to (authCheckCtx.errorMessage ?: "Subscription forbidden")))
                            return@get
                        }

                        // Извлекаем префикс, который разрешил экшен авторизации
                        targetPrefix = authCheckCtx.resolvePath("vars.allowedPrefix")?.toString()
                    } else if (!authCtx.isAuthenticated) {
                        // Анонимные подписки без auth-экшена запрещены по умолчанию
                        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required for SSE subscriptions"))
                        return@get
                    }

                    // Если экшен авторизации не вернул vars.allowedPrefix — по умолчанию используем "$actionName."
                    val allowedPrefix = targetPrefix ?: "$actionName."

                    // Открываем SSE поток с фильтрацией по динамическому префиксу
                    call.response.cacheControl(CacheControl.NoCache(null))
                    call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                        storage.events
                            .filter { event ->
                                event.key.startsWith(allowedPrefix) ||
                                        event.key.startsWith("users.${authCtx.userId}.") ||
                                        event.key.startsWith("public.")
                            }
                            .collect { event ->
                                val data = """{"type":"${event.type}","key":"${event.key}","newValue":${event.newValue}}"""
                                writeStringUtf8("event: $actionName\ndata: $data\n\n")
                                flush()
                            }
                    }
                }
            }
        }.start(wait = wait)
    }

    private fun extractAuthContext(call: ApplicationCall): AuthContext {
        val userId = call.request.headers["X-User-Id"] ?: "guest"
        val rolesHeader = call.request.headers["X-User-Roles"] ?: ""
        val roles = rolesHeader.split(",").map { it.trim() }.filter { it.isNotEmpty() }

        return AuthContext(
            isAuthenticated = userId != "guest",
            userId = userId,
            roles = roles
        )
    }

    private fun startStorageEventListener() {
        serverScope.launch {
            storage.events.collect { event ->
                triggers.forEach { (prefix, actionName) ->
                    if (event.key.startsWith(prefix)) {
                        val script = actionRegistry.getAction(actionName) ?: return@forEach
                        val ctx = ExecutionContext(
                            body = mapOf(
                                "eventType" to event.type.name,
                                "key" to event.key,
                                "oldValue" to event.oldValue,
                                "newValue" to event.newValue
                            )
                        )
                        vm.execute(script, ctx)
                    }
                }
            }
        }
    }

    fun stop() {
        serverScope.cancel()
        server?.stop(1000, 2000)
    }
}