package vm

data class AuthContext(
    val isAuthenticated: Boolean = false,
    val userId: String = "guest",
    val roles: List<String> = emptyList()
)

class ExecutionContext(
    val body: Map<String, Any?> = emptyMap(),
    val query: Map<String, String> = emptyMap(),
    val auth: AuthContext = AuthContext()
) {
    val vars = mutableMapOf<String, Any?>()

    var isFinished: Boolean = false
        private set

    var responseCode: Int = 200
        private set

    var responseData: Any? = null
        private set

    var errorMessage: String? = null
        private set

    fun finishWithReturn(code: Int, data: Any?) {
        this.isFinished = true
        this.responseCode = code
        this.responseData = data
    }

    fun finishWithError(code: Int, message: String) {
        this.isFinished = true
        this.responseCode = code
        this.errorMessage = message
    }

    /**
     * Получение значения по пути (например: "in.body.username", "vars.userId", "vars.player.badge")
     */
    fun resolvePath(path: String): Any? {
        if (path.isEmpty()) return null
        // Системные переменные
        when (path) {
            "sys.uuid", "uuid" -> return java.util.UUID.randomUUID().toString()
            "sys.now", "now"   -> return System.currentTimeMillis()
            "sys.nano", "nano" -> return System.nanoTime()
        }
        val parts = path.split(".")

        return when (parts[0]) {
            "vars" -> getNestedValue(vars, parts.drop(1))
            "in" -> {
                if (parts.size < 2) return null
                when (parts[1]) {
                    "body" -> getNestedValue(body, parts.drop(2))
                    "query" -> getNestedValue(query, parts.drop(2))
                    "auth" -> {
                        if (parts.size < 3) auth
                        else when (parts[2]) {
                            "isAuthenticated" -> auth.isAuthenticated
                            "userId" -> auth.userId
                            "roles" -> auth.roles
                            else -> null
                        }
                    }
                    else -> null
                }
            }
            else -> getNestedValue(vars, parts)
        }
    }

    /**
     * Запись значения с поддержкой вложенности (например: "vars.player.badge" или "player.badge")
     */
    fun setVariable(path: String, value: Any?) {
        val cleanPath = path.removePrefix("vars.")
        val parts = cleanPath.split(".")

        if (parts.size == 1) {
            vars[parts[0]] = value
            return
        }

        // Поддержка глубоких путей: превращаем вложенности в MutableMap
        var currentMap = vars
        for (i in 0 until parts.size - 1) {
            val key = parts[i]
            @Suppress("UNCHECKED_CAST")
            val nextMap = currentMap[key] as? MutableMap<String, Any?> ?: mutableMapOf<String, Any?>().also {
                currentMap[key] = it
            }
            currentMap = nextMap
        }

        currentMap[parts.last()] = value
    }

    private fun getNestedValue(obj: Any?, path: List<String>): Any? {
        if (obj == null) return null
        if (path.isEmpty()) return obj

        val currentKey = path.first()
        val tail = path.drop(1)

        return when (obj) {
            is Map<*, *> -> getNestedValue(obj[currentKey], tail)
            else -> null
        }
    }
}