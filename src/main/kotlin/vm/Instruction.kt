package vm

data class Instruction(
    val op: String, // "SET", "GET", "INC", "CHECK", "GOTO", "THROW", "RETURN" и т.д.
    val label: String? = null, // Метка для прыжков (например, "@create")
    val key: String? = null, // Ключ БД
    val value: Any? = null, // Значение (может быть шаблоном "${vars.x}")
    val asVar: String? = null, // В какую переменную сохранить результат (поле "as")
    val cond: String? = null, // Условие для CHECK
    val target: String? = null, // Целевая метка для GOTO / onFail
    val onFail: String? = null, // Метка прыжка при неудаче CHECK
    val code: Int? = null, // HTTP-код ошибки/ответа (для THROW / RETURN)
    val message: String? = null, // Сообщение ошибки
    val data: Any? = null, // Данные для RETURN
    val raw: Map<String, Any?> = emptyMap() // Полный raw JSON для специфических опций
) {
    companion object {
        fun fromMap(map: Map<String, Any?>): Instruction {
            return Instruction(
                op = map["op"]?.toString() ?: "NOP",
                label = map["label"]?.toString(),
                key = map["key"]?.toString(),
                value = map["value"],
                asVar = map["as"]?.toString(),
                cond = map["cond"]?.toString(),
                target = map["target"]?.toString(),
                onFail = map["onFail"]?.toString(),
                code = (map["code"] as? Number)?.toInt(),
                message = map["message"]?.toString(),
                data = map["data"],
                raw = map
            )
        }
    }
}