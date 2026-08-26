package vm

import storage.StorageEngine
import com.fasterxml.jackson.databind.ObjectMapper

class VirtualMachine(
    private val storage: StorageEngine,
    private val actionRegistry: ActionRegistry? = null,
    private val maxInstructions: Int = 10_000, // Лимит инструкций на один запуск
    private val maxStackDepth: Int = 50       // Защита от бесконечной рекурсии
) {

    /**
     * Главный метод запуска JSON-скрипта
     */
    fun execute(
        script: List<Map<String, Any?>>,
        context: ExecutionContext,
        callDepth: Int = 0
    ): ExecutionContext {
        // Защита от бесконечной рекурсии (CALL_ACTION)
        if (callDepth > maxStackDepth) {
            context.finishWithError(500, "Stack overflow: maximum call depth of $maxStackDepth exceeded")
            return context
        }

        val instructions = script.map { Instruction.fromMap(it) }

        // Строим карту меток для быстрых прыжков (@label -> индекс инструкции)
        val labels = mutableMapOf<String, Int>()
        instructions.forEachIndexed { index, instr ->
            instr.label?.let { labels[it] = index }
        }

        var ip = 0 // Instruction Pointer
        var stepCount = 0

        while (ip < instructions.size && !context.isFinished) {
            // Защита от бесконечных циклов
            if (++stepCount > maxInstructions) {
                context.finishWithError(508, "Execution limit exceeded (infinite loop protection)")
                break
            }

            val instr = instructions[ip]

            // Выполняем инструкцию, передавая currentDepth для CALL_ACTION
            val jumpTarget = executeInstruction(instr, context, callDepth)

            if (jumpTarget != null) {
                // Если команда требует прыжка на метку (GOTO / onFail)
                val targetIndex = labels[jumpTarget]
                if (targetIndex != null) {
                    ip = targetIndex
                    continue
                } else {
                    context.finishWithError(500, "Label not found: $jumpTarget")
                    break
                }
            }
            ip++
        }
        return context
    }

    private fun executeInstruction(instr: Instruction, ctx: ExecutionContext, callDepth: Int = 0): String? {
        when (instr.op.uppercase()) {
            "NOP" -> { /* Ничего не делаем, да, это БЭВМ референс ❤ */
            }

            // Работа с СУБД
            "SET" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val value = evaluateValue(instr.value, ctx) ?: ""
                storage.set(key, value)
            }

            "GET" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val result = storage.get(key)
                instr.asVar?.let { ctx.setVariable(it, result) }
            }

            "DEL", "DELETE" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                storage.delete(key)
            }

            "EXISTS" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val exists = storage.exists(key)
                if (!exists && instr.onFail != null) {
                    return instr.onFail
                }
                instr.asVar?.let { ctx.setVariable(it, exists) }
            }

            // В целом уменьшить/увеличить значение, зависит от дельты (delta)
            "INC", "INCREMENT" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val delta = (evaluateValue(instr.value, ctx) as? Number)?.toDouble() ?: 1.0
                val newValue = storage.increment(key, delta)
                instr.asVar?.let { ctx.setVariable(it, newValue) }
            }

            // Двухсторонняя очередь/массивы
            // Push в конец
            "ARRAY_PUSH" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val item = evaluateValue(instr.value, ctx) ?: return null
                storage.arrayPush(key, item)
            }

            // Push в начало (unshift)
            "ARRAY_UNSHIFT" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val item = evaluateValue(instr.value, ctx) ?: return null
                storage.arrayUnshift(key, item)
            }

            // Извлечь с конца (POP) и сохранить в переменную
            "ARRAY_POP" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val item = storage.arrayPop(key)
                instr.asVar?.let { ctx.setVariable(it, item) }
            }

            // Извлечь с начала (SHIFT / FIFO) и сохранить в переменную
            "ARRAY_SHIFT" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val item = storage.arrayShift(key)
                instr.asVar?.let { ctx.setVariable(it, item) }
            }

            // Удалить конкретное значение из массива
            "ARRAY_REMOVE" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val item = evaluateValue(instr.value, ctx) ?: return null
                val removed = storage.arrayRemove(key, item)
                if (!removed && instr.onFail != null) {
                    return instr.onFail
                }
                instr.asVar?.let { ctx.setVariable(it, removed) }
            }

            // Узнать длину массива
            "ARRAY_LEN" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val len = storage.arrayLen(key)
                instr.asVar?.let { ctx.setVariable(it, len) }
            }

            // Фильтровать массив
            "ARRAY_FILTER" -> {
                val sourceKey = evaluateString(instr.key, ctx) ?: return null
                val field = instr.raw["field"]?.toString() ?: ""
                val expectedValue = evaluateValue(instr.raw["value"], ctx)
                val targetVar = instr.asVar ?: sourceKey

                val rawData = if (sourceKey.startsWith("vars.")) {
                    ctx.resolvePath(sourceKey)
                } else {
                    storage.get(sourceKey)
                }

                @Suppress("UNCHECKED_CAST")
                val list = (rawData as? List<Map<String, Any?>>) ?: emptyList()
                val filtered = list.filter { item -> item[field] == expectedValue }

                if (targetVar.startsWith("vars.")) {
                    ctx.setVariable(targetVar, filtered)
                } else {
                    storage.set(targetVar, filtered)
                }
            }

            // Мапа в массивах
            "ARRAY_MAP" -> {
                val sourceKey = evaluateString(instr.key, ctx) ?: return null
                val extractField = instr.raw["field"]?.toString() ?: ""
                val targetVar = instr.asVar ?: return null

                // Извлекаем массив из vars (через ctx) или из storage
                val rawData = if (sourceKey.startsWith("vars.")) {
                    ctx.resolvePath(sourceKey)
                } else {
                    storage.get(sourceKey)
                }

                @Suppress("UNCHECKED_CAST")
                val list = (rawData as? List<Map<String, Any?>>) ?: emptyList()
                val mapped = list.mapNotNull { item -> item[extractField] }

                if (targetVar.startsWith("vars.")) {
                    ctx.setVariable(targetVar, mapped)
                } else {
                    storage.set(targetVar, mapped)
                }
            }

            // Работа с json
            "JSON_PARSE" -> {
                val jsonStr = evaluateString(instr.value?.toString(), ctx) ?: ""
                val targetVar = instr.asVar ?: return null
                try {
                    val parsed = objectMapper.readValue(jsonStr, Any::class.java)
                    ctx.setVariable(targetVar, parsed)
                } catch (e: Exception) {
                    if (instr.onFail != null) return instr.onFail
                }
            }

            "JSON_STRINGIFY" -> {
                val value = evaluateValue(instr.value, ctx)
                val targetVar = instr.asVar ?: return null
                val jsonStr = objectMapper.writeValueAsString(value)
                ctx.setVariable(targetVar, jsonStr)
            }

            // Работа с map/объектами
            // Точечное изменение поля внутри объекта/Map в базе
            "SET_IN_MAP" -> {
                val key = evaluateString(instr.key, ctx) ?: return null
                val field = instr.raw["field"]?.toString() ?: return null
                val value = evaluateValue(instr.value, ctx) ?: ""
                storage.setInMap(key, field, value)
            }

            // Утилиты и генераторы
            // Присвоение значения в локальную переменную контекста без БД
            "VAR_SET", "VAR" -> {
                val targetVar = instr.asVar ?: instr.key ?: return null
                val value = evaluateValue(instr.value, ctx)
                ctx.setVariable(targetVar, value)
            }

            // Генерация UUID v4
            "UUID" -> {
                val uuid = java.util.UUID.randomUUID().toString()
                val targetVar = instr.asVar ?: "vars.uuid"
                ctx.setVariable(targetVar, uuid)
            }

            // Текущий Timestamp (epoch millis)
            "NOW" -> {
                val now = System.currentTimeMillis()
                val targetVar = instr.asVar ?: "vars.now"
                ctx.setVariable(targetVar, now)
            }

            // Ветвления

            "GOTO" -> {
                return instr.target
            }

            "CHECK" -> {
                val conditionMet = evaluateCondition(instr.cond, ctx)
                if (!conditionMet && instr.onFail != null) {
                    return instr.onFail
                }
            }

            "THROW" -> {
                val code = instr.code ?: 400
                val msg = evaluateString(instr.message, ctx) ?: "Error"
                ctx.finishWithError(code, msg)
            }

            "RETURN" -> {
                val code = instr.code ?: 200
                val data = evaluateValue(instr.data, ctx)
                ctx.finishWithReturn(code, data)
            }

            // Криптография и хеширование

            "HASH" -> {
                val rawValue = instr.raw["value"]?.toString() ?: ""
                val input = evaluateString(rawValue, ctx) ?: rawValue
                val algo = (instr.raw["algo"]?.toString())?.uppercase() ?: "SHA-256"
                val secret = evaluateString(instr.raw["secret"]?.toString(), ctx)
                val targetVar = instr.asVar ?: return null

                val hashedResult = hashString(input, algo, secret)
                ctx.setVariable(targetVar, hashedResult)
            }

            "JWT_SIGN" -> {
                val targetVar = instr.asVar ?: return null
                val secret =
                    evaluateString(instr.raw["secret"]?.toString(), ctx) ?: "default_secret_key_change_me_32bytes"
                val expiresInSec = (instr.raw["expiresIn"] as? Number)?.toLong() ?: 3600L // 1 час по умолчанию

                @Suppress("UNCHECKED_CAST")
                val payloadMap = (evaluateValue(instr.raw["payload"], ctx) as? Map<String, Any?>) ?: emptyMap()

                val token = createJwtToken(payloadMap, secret, expiresInSec)
                ctx.setVariable(targetVar, token)
            }

            "JWT_VERIFY" -> {
                val token = evaluateString(instr.raw["token"]?.toString(), ctx)
                val secret =
                    evaluateString(instr.raw["secret"]?.toString(), ctx) ?: "default_secret_key_change_me_32bytes"

                if (token.isNull_or_empty()) {
                    if (instr.onFail != null) return instr.onFail
                    ctx.finishWithError(401, "JWT token is missing")
                    return null
                }

                val claims = verifyJwtToken(token, secret)
                if (claims == null) {
                    if (instr.onFail != null) return instr.onFail
                    ctx.finishWithError(401, "Invalid or expired JWT token")
                    return null
                }

                instr.asVar?.let { ctx.setVariable(it, claims) }
            }

            // Работа с сетью
            "HTTP_REQUEST", "HTTP" -> {
                // Поддержка формирования через configMap из vars
                val configVar = instr.raw["config"]?.toString()
                val configMap = if (configVar != null) {
                    evaluateValue(configVar, ctx) as? Map<*, *>
                } else null

                val url = evaluateString(configMap?.get("url")?.toString() ?: instr.raw["url"]?.toString(), ctx)
                if (url.isNullOrEmpty()) {
                    if (instr.onFail != null) return instr.onFail
                    ctx.finishWithError(400, "HTTP URL is missing")
                    return null
                }

                val method =
                    (configMap?.get("method")?.toString() ?: instr.raw["method"]?.toString() ?: "GET").uppercase()
                val headers = (configMap?.get("headers") as? Map<*, *>)
                    ?: (instr.raw["headers"] as? Map<*, *>)
                    ?: emptyMap()

                val body = configMap?.get("body") ?: instr.raw["body"]
                val serializedBody = if (body != null) {
                    if (body is String) evaluateString(body, ctx) else objectMapper.writeValueAsString(
                        evaluateValue(
                            body,
                            ctx
                        )
                    )
                } else null

                val response = executeHttpRequest(url, method, headers, serializedBody)

                if (response.statusCode >= 400 && instr.onFail != null) {
                    return instr.onFail
                }

                // Сохраняем результат
                instr.asVar?.let { targetVar ->
                    ctx.setVariable(
                        targetVar, mapOf(
                            "status" to response.statusCode,
                            "body" to response.body,
                            "isSuccess" to (response.statusCode in 200..299)
                        )
                    )
                }
            }

            // Асинхронная пауза
            "DELAY", "SLEEP" -> {
                val ms = (evaluateValue(instr.raw["ms"], ctx) as? Number)?.toLong() ?: 1000L
                if (ms > 0) {
                    Thread.sleep(ms)
                }
            }

            // Вызов экшенов из экшенов
            "CALL_ACTION", "CALL" -> {
                val actionName = evaluateString(instr.raw["action"]?.toString(), ctx)
                if (actionName.isNullOrEmpty()) {
                    if (instr.onFail != null) return instr.onFail
                    ctx.finishWithError(400, "Action name is missing in CALL_ACTION")
                    return null
                }

                val script = actionRegistry?.getAction(actionName)
                if (script == null) {
                    if (instr.onFail != null) return instr.onFail
                    ctx.finishWithError(404, "Action '$actionName' not found in registry")
                    return null
                }

                @Suppress("UNCHECKED_CAST")
                val args = (evaluateValue(instr.raw["args"], ctx) as? Map<String, Any?>) ?: emptyMap()

                val subCtx = ExecutionContext(
                    body = args,
                    query = ctx.query,
                    auth = ctx.auth
                )

                // Передаём инкрементированный callDepth
                execute(script, subCtx, callDepth = callDepth + 1)

                if (subCtx.errorMessage != null || subCtx.responseCode >= 400) {
                    if (instr.onFail != null) return instr.onFail
                    ctx.finishWithError(subCtx.responseCode, subCtx.errorMessage ?: "Sub-action failed")
                    return null
                }

                instr.asVar?.let { targetVar ->
                    ctx.setVariable(targetVar, subCtx.responseData ?: mapOf("status" to subCtx.responseCode))
                }
            }
        }
        return null
    }

    // Вспомогательный хелпер Хеширования (SHA-256 / MD5 / HMAC)
    private fun hashString(input: String, algo: String, secret: String?): String {
        return try {
            if (!secret.isNullOrEmpty() && algo.startsWith("HMAC")) {
                val macAlgo = when (algo) {
                    "HMAC-SHA1" -> "HmacSHA1"
                    "HMAC-MD5" -> "HmacMD5"
                    else -> "HmacSHA256"
                }
                val keySpec = javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), macAlgo)
                val mac = javax.crypto.Mac.getInstance(macAlgo)
                mac.init(keySpec)
                val bytes = mac.doFinal(input.toByteArray(Charsets.UTF_8))
                bytes.joinToString("") { "%02x".format(it) }
            } else {
                val digestAlgo = when (algo) {
                    "MD5" -> "MD5"
                    "SHA-1", "SHA1" -> "SHA-1"
                    else -> "SHA-256"
                }
                val md = java.security.MessageDigest.getInstance(digestAlgo)
                val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
                bytes.joinToString("") { "%02x".format(it) }
            }
        } catch (e: Exception) {
            ""
        }
    }

    // Вспомогательные хелперы JWT
    private fun createJwtToken(claims: Map<String, Any?>, secret: String, expiresInSec: Long): String {
        val now = java.util.Date()
        val exp = java.util.Date(now.time + expiresInSec * 1000)
        val keyBytes = io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.padEnd(32, '0').toByteArray(Charsets.UTF_8))

        return io.jsonwebtoken.Jwts.builder()
            .claims(claims)
            .issuedAt(now)
            .expiration(exp)
            .signWith(keyBytes)
            .compact()
    }

    private fun verifyJwtToken(token: String?, secret: String): Map<String, Any?>? {
        return try {
            val keyBytes =
                io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.padEnd(32, '0').toByteArray(Charsets.UTF_8))
            val claims = io.jsonwebtoken.Jwts.parser()
                .verifyWith(keyBytes)
                .build()
                .parseSignedClaims(token)
                .payload

            claims.toMap()
        } catch (e: Exception) {
            null
        }
    }

    private fun String?.isNull_or_empty(): Boolean = this == null || this.trim().isEmpty()

    // Интерполяции и вычисление шаблонов
    /**
     * Вычисляет строковые шаблоны и арифметику (например: "${vars.coins} + 50")
     */
    fun evaluateString(template: String?, ctx: ExecutionContext): String? {
        if (template == null) return null
        val regex = Regex("\\$\\{([^}]+)\\}")

        // Подставляем все переменные ${...}
        val interpolated = regex.replace(template) { matchResult ->
            val path = matchResult.groupValues[1].trim()
            ctx.resolvePath(path)?.toString() ?: "0"
        }

        // Если внутри есть математические операторы или скобки - вычисляем!
        if (containsMathOrConcat(interpolated)) {
            return ExpressionEvaluator.evaluate(interpolated).toString()
        }

        return interpolated
    }

    /**
     * Вычисляет значения с сохранением оригинальных типов (Numbers, Boolean, Map, List)
     */
    fun evaluateValue(value: Any?, ctx: ExecutionContext): Any? {
        if (value is String) {
            // Если в выражении есть ${...}, подставляем
            if (value.contains("\${")) {
                // Если выражение чисто одиочное ${vars.x}, сохраняем исходный тип
                if (value.startsWith("\${") && value.endsWith("}") && value.count { it == '{' } == 1) {
                    val path = value.substring(2, value.length - 1).trim()
                    return ctx.resolvePath(path)
                }

                val strResult = evaluateString(value, ctx) ?: return null
                // Пробуем кастануть к числу, если получилось математическое выражение
                return strResult.toLongOrNull() ?: strResult.toDoubleOrNull() ?: strResult
            }

            // Если строка без ${...}, но с математикой (например, "100 + 50")
            if (containsMathOrConcat(value)) {
                return ExpressionEvaluator.evaluate(value)
            }

            return value
        }

        if (value is Map<*, *>) {
            return value.mapValues { evaluateValue(it.value, ctx) }
        }
        if (value is List<*>) {
            return value.map { evaluateValue(it, ctx) }
        }

        return value
    }

    private fun containsMathOrConcat(str: String): Boolean {
        // Не считаем дефис внутри слов математикой, проверяем математику по наличию пробелов вокруг операторов или скобок
        val trimmed = str.trim()
        if (trimmed.startsWith("'") || trimmed.startsWith("\"")) return true
        return trimmed.contains(" + ") || trimmed.contains(" - ") || trimmed.contains(" * ") ||
                trimmed.contains(" / ") || trimmed.contains(" % ") || trimmed.contains("(")
    }

    /**
     * Базовая проверка условий cond (например, "vars.userId != null" или "vars.isValid == true")
     */
    private fun evaluateCondition(cond: String?, ctx: ExecutionContext): Boolean {
        if (cond == null) return true

        val evaluated = evaluateString(cond, ctx) ?: return false

        if (evaluated.contains("==")) {
            val parts = evaluated.split("==").map { it.trim() }
            return parts[0] == parts[1]
        }
        if (evaluated.contains("!=")) {
            val parts = evaluated.split("!=").map { it.trim() }
            return parts[0] != parts[1]
        }

        return evaluated.lowercase() == "true"
    }

    // Сетевое взаимодействие
    private val httpClient = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(5))
        .build()

    private val objectMapper = ObjectMapper()

    private data class HttpResponseData(val statusCode: Int, val body: String)

    private fun executeHttpRequest(
        url: String,
        method: String,
        headers: Map<out Any?, Any?>,
        body: String?
    ): HttpResponseData {
        return try {
            val requestBuilder = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(10))

            headers.forEach { (k, v) -> requestBuilder.header(k as String?, v as String?) }

            val publisher = if (body != null) {
                java.net.http.HttpRequest.BodyPublishers.ofString(body)
            } else {
                java.net.http.HttpRequest.BodyPublishers.noBody()
            }

            when (method) {
                "POST" -> requestBuilder.POST(publisher)
                "PUT" -> requestBuilder.PUT(publisher)
                "DELETE" -> requestBuilder.DELETE()
                else -> requestBuilder.GET()
            }

            val response = httpClient.send(requestBuilder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
            HttpResponseData(response.statusCode(), response.body())
        } catch (e: Exception) {
            HttpResponseData(500, e.message ?: "HTTP Request failed")
        }
    }
}