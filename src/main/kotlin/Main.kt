import storage.StorageEngine
import vm.ActionRegistry
import vm.ActionServer
import vm.VirtualMachine
import java.io.File

fun main() {
    println("🚀 Запуск ActionDB & Execution Engine...")

    // Инициализация хранилища и загрузка снапшота
    val storage = StorageEngine()
    val dataDir = File("./data")
    if (!dataDir.exists()) dataDir.mkdirs()

    val snapshotFile = File(dataDir, "dump.json")
    if (snapshotFile.exists()) {
        storage.loadSnapshot(snapshotFile.path)
    }

    // Автосохранение при выключении
    Runtime.getRuntime().addShutdownHook(Thread {
        println("\n💾 Сохранение снапшота базы данных...")
        storage.saveSnapshot(snapshotFile.path)
        println("✅ Сохранение завершено.")
    })

    // Инициализация реестра экшенов
    val registry = ActionRegistry()
    val actionsDir = File("./actions")
    if (!actionsDir.exists()) {
        actionsDir.mkdirs()
        println("📁 Создана директория ./actions (поместите туда *.json файлы экшенов)")
    } else {
        registry.loadFromDirectory(actionsDir)
        println("📦 Загружено экшенов: ${registry.listActions().size} (${registry.listActions().joinToString()})")
    }

    // Создание виртуалки
    val vm = VirtualMachine(
        storage = storage,
        actionRegistry = registry,
        maxInstructions = 10_000,
        maxStackDepth = 50
    )

    // Запуск веб сервера
    val serverPort = 8080
    val server = ActionServer(
        vm = vm,
        actionRegistry = registry,
        storage = storage,
        port = serverPort
    )

    // Запускаем сервер с блокировкой главного потока
    server.start(wait = true)
}