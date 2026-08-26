package vm

object ExpressionEvaluator {

    /**
     * Вычисляет выражения вида "100 + 50 * 2" или "'Hello ' + 'World'"
     */
    fun evaluate(expression: String): Any {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return ""

        // Проверяем, не является ли это сложением строк вида 'Hello ' + 'World'
        if (trimmed.contains("'") || trimmed.contains("\"")) {
            return evaluateStringConcatenation(trimmed)
        }

        // Иначе вычисляем математическое выражение
        return try {
            val result = parseMath(trimmed)
            // Если число целое (15.0), возвращаем Long/Int для красивого отображения
            if (result % 1.0 == 0.0) {
                result.toLong()
            } else {
                result
            }
        } catch (e: Exception) {
            // Если вычисление математики упало, возвращаем исходную строку
            trimmed
        }
    }

    private fun evaluateStringConcatenation(expr: String): String {
        return expr.split("+").joinToString("") { part ->
            part.trim().removeSurrounding("'").removeSurrounding("\"")
        }
    }

    // Простой рекурсивный математический парсер

    private fun parseMath(str: String): Double {
        var pos = -1
        var ch = -1

        fun nextChar() {
            ch = if (++pos < str.length) str[pos].code else -1
        }

        fun eat(charToEat: Int): Boolean {
            while (ch == ' '.code) nextChar()
            if (ch == charToEat) {
                nextChar()
                return true
            }
            return false
        }

        // Объявляем функции в правильном порядке (снизу вверх по приоритету)

        // Объявляем первыми переменные-ссылки на функции для поддержки взаимной рекурсии
        lateinit var parseExpression: () -> Double
        lateinit var parseTerm: () -> Double
        lateinit var parseFactor: () -> Double

        parseFactor = {
            if (eat('+'.code)) parseFactor()
            else if (eat('-'.code)) -parseFactor()
            else {
                var x: Double
                val startPos = pos
                if (eat('('.code)) {
                    x = parseExpression()
                    eat(')'.code)
                } else if ((ch in '0'.code..'9'.code) || ch == '.'.code) {
                    while ((ch in '0'.code..'9'.code) || ch == '.'.code) nextChar()
                    x = str.substring(startPos, pos).toDouble()
                } else {
                    throw RuntimeException("Unexpected char: " + ch.toChar())
                }
                x
            }
        }

        parseTerm = {
            var x = parseFactor()
            while (true) {
                when {
                    eat('*'.code) -> x *= parseFactor()
                    eat('/'.code) -> x /= parseFactor()
                    eat('%'.code) -> x %= parseFactor()
                    else -> break
                }
            }
            x
        }

        parseExpression = {
            var x = parseTerm()
            while (true) {
                when {
                    eat('+'.code) -> x += parseTerm()
                    eat('-'.code) -> x -= parseTerm()
                    else -> break
                }
            }
            x
        }

        nextChar()
        val result = parseExpression()
        if (pos < str.length) throw RuntimeException("Unexpected char: " + ch.toChar())
        return result
    }
}