package storage

enum class StorageEventType {
    SET,
    DEL,
    INC,
    ARRAY_PUSH,
    ARRAY_REMOVE
}

data class StorageEvent(
    val type: StorageEventType,
    val key: String,
    val oldValue: Any?,
    val newValue: Any?,
    val timestamp: Long = System.currentTimeMillis()
)