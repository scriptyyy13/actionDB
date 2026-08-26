package vm

import storage.StorageEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VirtualMachineTest {

    // Тесты проверки работы с контекстом на различные операции доступа к путям
    @Test
    fun `test execution context path resolution`() {
        val auth = AuthContext(
            isAuthenticated = true,
            userId = "usr_42",
            roles = listOf("admin", "player")
        )
        val body = mapOf(
            "username" to "Alice",
            "stats" to mapOf("level" to 10)
        )
        val query = mapOf("action" to "ping")

        val ctx = ExecutionContext(body = body, query = query, auth = auth)
        ctx.setVariable("score", 100.0)
        ctx.setVariable("player.badge", "gold")

        // Проверяем резолв вложенных данных
        assertEquals("Alice", ctx.resolvePath("in.body.username"))
        assertEquals(10, ctx.resolvePath("in.body.stats.level"))
        assertEquals("ping", ctx.resolvePath("in.query.action"))
        assertEquals("usr_42", ctx.resolvePath("in.auth.userId"))
        assertEquals(true, ctx.resolvePath("in.auth.isAuthenticated"))
        assertEquals(listOf("admin", "player"), ctx.resolvePath("in.auth.roles"))
        assertEquals(100.0, ctx.resolvePath("vars.score"))
        assertEquals("gold", ctx.resolvePath("vars.player.badge"))
        assertNull(ctx.resolvePath("in.body.nonexistent"))
    }

    // Тесты парсинга шаблонных и системных значений (${sys.uuid}, ${sys.now})
    @Test
    fun `test evaluateString evaluateValue and sys properties`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext(body = mapOf("tag" to "hero", "hp" to 100))
        ctx.setVariable("id", 777)

        // Подстановка обычных строк
        val strResult = vm.evaluateString("users.${ctx.resolvePath("in.body.tag")}_${ctx.resolvePath("vars.id")}", ctx)
        assertEquals("users.hero_777", strResult)

        // Одиночное выражение сохраняет исходный тип
        val rawNumber = vm.evaluateValue("\${in.body.hp}", ctx)
        assertEquals(100, rawNumber)

        // Проверка резолвинга системных генераторов UUID и NOW через выражения
        val uuid = vm.evaluateString("\${sys.uuid}", ctx)
        assertNotNull(uuid)
        assertEquals(36, uuid.length)
        assertTrue(uuid.contains("-"))

        val now = vm.evaluateValue("\${sys.now}", ctx) as Long
        assertTrue(now > 0)

        // Рекурсивная подстановка в Map
        val mapInput = mapOf(
            "key" to "player_\${vars.id}",
            "value" to "\${in.body.hp}",
            "traceId" to "\${uuid}"
        )
        val mapResult = vm.evaluateValue(mapInput, ctx) as Map<*, *>
        assertEquals("player_777", mapResult["key"])
        assertEquals(100, mapResult["value"])
        assertNotNull(mapResult["traceId"])
    }

    // Тесты базовых команд работы с СУБД в ВМ
    @Test
    fun `test storage instructions inside VM`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext(body = mapOf("userId" to "usr_10"))

        val script = listOf(
            mapOf("op" to "SET", "key" to "users.\${in.body.userId}.coins", "value" to 50.0),
            mapOf("op" to "INC", "key" to "users.\${in.body.userId}.coins", "value" to 25.0, "as" to "vars.newBalance"),
            mapOf("op" to "GET", "key" to "users.\${in.body.userId}.coins", "as" to "vars.fetchedCoins"),
            mapOf("op" to "ARRAY_PUSH", "key" to "users.\${in.body.userId}.items", "value" to "sword"),
            mapOf("op" to "EXISTS", "key" to "users.\${in.body.userId}.coins", "as" to "vars.hasCoins")
        )

        vm.execute(script, ctx)

        // Проверяем данные в СУБД
        assertEquals(75.0, storage.get("users.usr_10.coins"))
        assertEquals(listOf("sword"), storage.get("users.usr_10.items"))

        // Проверяем переменные в контексте
        assertEquals(75.0, ctx.resolvePath("vars.newBalance"))
        assertEquals(75.0, ctx.resolvePath("vars.fetchedCoins"))
        assertEquals(true, ctx.resolvePath("vars.hasCoins"))
    }

    // Тесты управления потоками (GOTO, CHECK, THROW, RETURN)
    @Test
    fun `test control flow with CHECK GOTO and RETURN`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)

        // Положительный сценарий
        val validCtx = ExecutionContext(body = mapOf("amount" to 100))
        val script = listOf(
            mapOf("op" to "CHECK", "cond" to "\${in.body.amount} == 100", "onFail" to "@error"),
            mapOf("op" to "GOTO", "target" to "@success"),
            mapOf("op" to "THROW", "label" to "@error", "code" to 400, "message" to "Invalid amount"),
            mapOf("op" to "RETURN", "label" to "@success", "code" to 200, "data" to mapOf("status" to "ok"))
        )

        vm.execute(script, validCtx)

        assertTrue(validCtx.isFinished)
        assertEquals(200, validCtx.responseCode)
        assertEquals(mapOf("status" to "ok"), validCtx.responseData)

        // Негативный сценарий: CHECK не проходит -> прыжок на THROW
        val invalidCtx = ExecutionContext(body = mapOf("amount" to 50))
        vm.execute(script, invalidCtx)

        assertTrue(invalidCtx.isFinished)
        assertEquals(400, invalidCtx.responseCode)
        assertEquals("Invalid amount", invalidCtx.errorMessage)
    }

    // Тест защиты от бесконечных циклов
    @Test
    fun `test infinite loop protection`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext()

        val infiniteScript = listOf(
            mapOf("op" to "NOP", "label" to "@loop"),
            mapOf("op" to "GOTO", "target" to "@loop")
        )

        vm.execute(infiniteScript, ctx)

        assertTrue(ctx.isFinished)
        assertEquals(508, ctx.responseCode)
        assertTrue(ctx.errorMessage?.contains("Execution limit exceeded") == true)
    }

    // Тест переполнения стека при рекурсивных вызовах
    @Test
    fun `test stack overflow prevention on recursive CALL_ACTION`() {
        val storage = StorageEngine()
        val registry = ActionRegistry()

        val recursiveScript = listOf(
            mapOf("op" to "CALL_ACTION", "action" to "rec.loop")
        )
        registry.register("rec.loop", recursiveScript)

        val vm = VirtualMachine(storage, actionRegistry = registry, maxStackDepth = 3)
        val ctx = ExecutionContext()

        vm.execute(recursiveScript, ctx)

        assertEquals(500, ctx.responseCode)
        assertTrue(ctx.errorMessage?.contains("Stack overflow") == true)
    }

    // Тест всех команд работы с коллекциями и очередями
    @Test
    fun `test all VM array and deque instructions`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext()

        val script = listOf(
            mapOf("op" to "ARRAY_PUSH", "key" to "match.queue", "value" to "player_mid"),
            mapOf("op" to "ARRAY_PUSH", "key" to "match.queue", "value" to "player_last"),
            mapOf("op" to "ARRAY_UNSHIFT", "key" to "match.queue", "value" to "player_first"),

            mapOf("op" to "ARRAY_LEN", "key" to "match.queue", "as" to "vars.initialLen"),

            mapOf("op" to "ARRAY_SHIFT", "key" to "match.queue", "as" to "vars.firstPlayer"),
            mapOf("op" to "ARRAY_POP", "key" to "match.queue", "as" to "vars.lastPlayer"),
            mapOf("op" to "ARRAY_REMOVE", "key" to "match.queue", "value" to "player_mid", "as" to "vars.wasRemoved"),

            mapOf("op" to "ARRAY_LEN", "key" to "match.queue", "as" to "vars.finalLen")
        )

        vm.execute(script, ctx)

        assertEquals(3, ctx.resolvePath("vars.initialLen"))
        assertEquals("player_first", ctx.resolvePath("vars.firstPlayer"))
        assertEquals("player_last", ctx.resolvePath("vars.lastPlayer"))
        assertEquals(true, ctx.resolvePath("vars.wasRemoved"))
        assertEquals(0, ctx.resolvePath("vars.finalLen"))
    }

    // Тест утилит VAR_SET и SET_IN_MAP со встроенными выражениями
    @Test
    fun `test VM utilities VAR_SET and SET_IN_MAP`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext()

        val startTime = System.currentTimeMillis()

        val script = listOf(
            // VAR_SET через синтаксис as
            mapOf("op" to "VAR_SET", "value" to "custom_value", "as" to "vars.myLocalVar"),
            // VAR_SET через синтаксис key
            mapOf("op" to "VAR", "key" to "vars.generatedId", "value" to "id_\${sys.uuid}"),
            mapOf("op" to "VAR", "key" to "vars.timestamp", "value" to "\${sys.now}"),

            // Изменение поля во вложенном Map в БД
            mapOf("op" to "SET_IN_MAP", "key" to "user.profile", "field" to "nickname", "value" to "ProGamer")
        )

        vm.execute(script, ctx)

        // Проверяем VAR_SET
        assertEquals("custom_value", ctx.resolvePath("vars.myLocalVar"))

        // Проверяем генерацию через ключевые слова
        val generatedId = ctx.resolvePath("vars.generatedId") as String
        assertTrue(generatedId.startsWith("id_"))
        assertEquals(39, generatedId.length) // "id_" + 36 символов UUID

        val now = ctx.resolvePath("vars.timestamp") as Long
        assertTrue(now >= startTime)

        // Проверяем SET_IN_MAP в СУБД
        @Suppress("UNCHECKED_CAST")
        val profile = storage.get("user.profile") as Map<String, Any>
        assertEquals("ProGamer", profile["nickname"])
    }

    // Тест обработки ошибки на ARRAY_REMOVE
    @Test
    fun `test ARRAY_REMOVE onFail jump when item is missing`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext()

        val script = listOf(
            mapOf("op" to "ARRAY_PUSH", "key" to "inventory", "value" to "potion"),
            mapOf("op" to "ARRAY_REMOVE", "key" to "inventory", "value" to "shield", "onFail" to "@notFound"),
            mapOf("op" to "RETURN", "code" to 200, "data" to "Success"),
            mapOf("op" to "THROW", "label" to "@notFound", "code" to 404, "message" to "Item not in inventory")
        )

        vm.execute(script, ctx)

        assertTrue(ctx.isFinished)
        assertEquals(404, ctx.responseCode)
        assertEquals("Item not in inventory", ctx.errorMessage)
    }

    // Тест на обработку математических и строковых выражений
    @Test
    fun `test math and string expressions in instructions`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext(body = mapOf("baseDamage" to 40, "name" to "Alex"))
        ctx.setVariable("coins", 100)
        ctx.setVariable("multiplier", 2)

        val script = listOf(
            // Вычисление математики с переменными: (100 + 50) * 2 = 300
            mapOf(
                "op" to "VAR_SET",
                "value" to "(\${vars.coins} + 50) * \${vars.multiplier}",
                "as" to "vars.totalCoins"
            ),

            // Расчет урона
            mapOf("op" to "VAR_SET", "value" to "\${in.body.baseDamage} * 1.5", "as" to "vars.finalDamage"),

            // Конкатенация строк
            mapOf("op" to "VAR_SET", "value" to "'Hello, ' + '\${in.body.name}'", "as" to "vars.greeting")
        )

        vm.execute(script, ctx)

        assertEquals(300L, (ctx.resolvePath("vars.totalCoins") as Number).toLong())
        assertEquals(60L, (ctx.resolvePath("vars.finalDamage") as Number).toLong())
        assertEquals("Hello, Alex", ctx.resolvePath("vars.greeting"))
    }

    // Тесты криптографии, хешей и JWT
    @Test
    fun `test HASH JWT_SIGN and JWT_VERIFY instructions`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext(body = mapOf("password" to "secret123", "userId" to "usr_99"))

        val script = listOf(
            mapOf("op" to "HASH", "value" to "\${in.body.password}", "algo" to "SHA-256", "as" to "vars.pwdHash"),

            mapOf(
                "op" to "JWT_SIGN",
                "secret" to "super_secret_jwt_key_1234567890",
                "expiresIn" to 3600,
                "payload" to mapOf(
                    "userId" to "\${in.body.userId}",
                    "role" to "admin"
                ),
                "as" to "vars.token"
            ),

            mapOf(
                "op" to "JWT_VERIFY",
                "token" to "\${vars.token}",
                "secret" to "super_secret_jwt_key_1234567890",
                "as" to "vars.jwtPayload",
                "onFail" to "@badToken"
            ),

            mapOf("op" to "RETURN", "code" to 200, "data" to "Success"),

            mapOf("op" to "THROW", "label" to "@badToken", "code" to 401, "message" to "Unauthorized token")
        )

        vm.execute(script, ctx)

        val pwdHash = ctx.resolvePath("vars.pwdHash") as String
        assertEquals("fcf730b6d95236ecd3c9fc2d92d7b6b2bb061514961aec041d6c7a7192f592e4", pwdHash)

        val token = ctx.resolvePath("vars.token") as String
        assertTrue(token.isNotEmpty())
        assertEquals(3, token.split(".").size)

        @Suppress("UNCHECKED_CAST")
        val jwtPayload = ctx.resolvePath("vars.jwtPayload") as Map<String, Any?>
        assertEquals("usr_99", jwtPayload["userId"])
        assertEquals("admin", jwtPayload["role"])

        assertEquals(200, ctx.responseCode)
    }

    // Тест перехода на ошибку в JWT_VERIFY
    @Test
    fun `test JWT_VERIFY jump to onFail on invalid token`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext()

        val script = listOf(
            mapOf(
                "op" to "JWT_VERIFY",
                "token" to "invalid.jwt.token",
                "secret" to "super_secret_jwt_key_1234567890",
                "onFail" to "@invalid"
            ),
            mapOf("op" to "RETURN", "code" to 200, "data" to "OK"),
            mapOf("op" to "THROW", "label" to "@invalid", "code" to 401, "message" to "Token is forged")
        )

        vm.execute(script, ctx)

        assertTrue(ctx.isFinished)
        assertEquals(401, ctx.responseCode)
        assertEquals("Token is forged", ctx.errorMessage)
    }

    // Тест работы с сетью, JSON, фильтрации списков и задержки
    @Test
    fun `test HTTP_REQUEST with config map JSON and ARRAY operations`() {
        val storage = StorageEngine()
        val vm = VirtualMachine(storage)
        val ctx = ExecutionContext()

        storage.set(
            "users.list", listOf(
                mapOf("id" to 1, "role" to "admin", "name" to "Alice"),
                mapOf("id" to 2, "role" to "player", "name" to "Bob"),
                mapOf("id" to 3, "role" to "admin", "name" to "Charlie")
            )
        )

        val script = listOf(
            mapOf(
                "op" to "VAR_SET",
                "value" to mapOf("url" to "https://postman-echo.com/get", "method" to "GET"),
                "as" to "vars.httpConfig"
            ),
            mapOf("op" to "SET_IN_MAP", "key" to "vars.httpConfig", "field" to "method", "value" to "GET"),

            mapOf("op" to "HTTP_REQUEST", "config" to "\${vars.httpConfig}", "as" to "vars.httpResult"),

            mapOf("op" to "DELAY", "ms" to 100),

            mapOf(
                "op" to "ARRAY_FILTER",
                "key" to "users.list",
                "field" to "role",
                "value" to "admin",
                "as" to "vars.admins"
            ),

            mapOf("op" to "ARRAY_MAP", "key" to "vars.admins", "field" to "name", "as" to "vars.adminNames"),

            mapOf("op" to "JSON_STRINGIFY", "value" to "\${vars.adminNames}", "as" to "vars.jsonNamesStr"),
            mapOf("op" to "JSON_PARSE", "value" to "\${vars.jsonNamesStr}", "as" to "vars.parsedNames")
        )

        val startTime = System.currentTimeMillis()
        vm.execute(script, ctx)
        val duration = System.currentTimeMillis() - startTime

        assertTrue(duration >= 100)

        @Suppress("UNCHECKED_CAST")
        val adminNames = ctx.resolvePath("vars.adminNames") as List<String>
        assertEquals(listOf("Alice", "Charlie"), adminNames)

        assertEquals("[\"Alice\",\"Charlie\"]", ctx.resolvePath("vars.jsonNamesStr"))
        assertEquals(listOf("Alice", "Charlie"), ctx.resolvePath("vars.parsedNames"))

        @Suppress("UNCHECKED_CAST")
        val httpResult = ctx.resolvePath("vars.httpResult") as Map<String, Any?>
        assertEquals(200, httpResult["status"])
        assertEquals(true, httpResult["isSuccess"])
    }

    // Тест загрузки директории реестра и вызова CALL_ACTION
    @Test
    fun `test ActionRegistry loading from directory and CALL_ACTION execution`() {
        val tempDir = java.nio.file.Files.createTempDirectory("actions_test").toFile()
        tempDir.deleteOnExit()

        val userSubDir = java.io.File(tempDir, "user").apply { mkdirs() }
        val helperFile = java.io.File(userSubDir, "math_helper.json")

        val helperJson = """
            [
              { "op": "VAR_SET", "value": "${'$'}{in.body.a} * ${'$'}{in.body.b}", "as": "vars.subResult" },
              { "op": "RETURN", "code": 200, "data": { "calculated": "${'$'}{vars.subResult}" } }
            ]
        """.trimIndent()
        helperFile.writeText(helperJson)

        val registry = ActionRegistry()
        registry.loadFromDirectory(tempDir)

        assertTrue(registry.listActions().contains("user.math_helper"))

        val storage = StorageEngine()
        val vm = VirtualMachine(storage, registry)
        val ctx = ExecutionContext()

        val parentScript = listOf(
            mapOf(
                "op" to "CALL_ACTION",
                "action" to "user.math_helper",
                "args" to mapOf("a" to 10, "b" to 5),
                "as" to "vars.helperResponse"
            ),
            mapOf(
                "op" to "RETURN",
                "code" to 200,
                "data" to mapOf("finalResult" to "\${vars.helperResponse.calculated}")
            )
        )

        vm.execute(parentScript, ctx)

        assertEquals(200, ctx.responseCode)
        @Suppress("UNCHECKED_CAST")
        val response = ctx.responseData as Map<String, Any?>
        assertEquals(50L, (response["finalResult"] as Number).toLong())
    }
}