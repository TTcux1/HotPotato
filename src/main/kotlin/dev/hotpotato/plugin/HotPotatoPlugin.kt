package dev.hotpotato.plugin

import dev.hotpotato.core.GameRules
import dev.hotpotato.core.InMemoryStatsRepository
import dev.hotpotato.core.StatsRepository
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.logging.Level

/**
 * Точка входа. Игровая логика — в пакете core (без Bukkit, покрыта тестами),
 * здесь только связка с сервером.
 */
class HotPotatoPlugin : JavaPlugin() {

    lateinit var arenas: ArenaStore
        private set
    lateinit var sessions: SessionManager
        private set
    lateinit var stats: StatsRepository
        private set

    private lateinit var rules: GameRules

    override fun onEnable() {
        saveDefaultConfig()
        rules = loadRules()
        stats = connectStats()

        arenas = ArenaStore(File(dataFolder, "arenas.yml"), logger).apply { load() }
        sessions = SessionManager(this, { rules }, { stats })

        server.pluginManager.registerEvents(GameListener(sessions), this)

        val autoJoin = System.getenv(ENV_AUTO_JOIN)?.takeIf { it.isNotBlank() }
            ?: config.getString("auto-join-arena", "")!!.takeIf { it.isNotBlank() }
        if (autoJoin != null) {
            val listener = AutoJoinListener(this, autoJoin)
            server.pluginManager.registerEvents(listener, this)
            listener.start()
            logger.info("Выделенный режим: игроки автоматически попадают на арену $autoJoin")
        }
        getCommand("hotpotato")?.let { command ->
            val executor = HotPotatoCommand(this)
            command.setExecutor(executor)
            command.tabCompleter = executor
        }

        // Игровые часы: один вызов на тик сервера.
        server.scheduler.runTaskTimer(this, Runnable { sessions.tick() }, 1L, 1L)
        logger.info("Горячая картошка запущена. Арен: ${arenas.all.size}")
    }

    override fun onDisable() {
        if (::sessions.isInitialized) sessions.shutdown()
        if (::stats.isInitialized) stats.close()
    }

    /** Выполнить [work] асинхронно и передать результат в [then] в главном потоке. */
    fun <T> async(work: () -> T, then: (T) -> Unit) {
        server.scheduler.runTaskAsynchronously(this, Runnable {
            val result = try {
                work()
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Ошибка при обращении к статистике", e)
                return@Runnable
            }
            server.scheduler.runTask(this, Runnable { then(result) })
        })
    }

    private fun loadRules(): GameRules {
        val defaults = GameRules()
        return try {
            GameRules(
                minPlayers = config.getInt("game.min-players", defaults.minPlayers),
                maxPlayers = config.getInt("game.max-players", defaults.maxPlayers),
                countdownSeconds = config.getInt("game.countdown-seconds", defaults.countdownSeconds),
                initialFuseSeconds = config.getInt("game.fuse.initial-seconds", defaults.initialFuseSeconds),
                fuseStepSeconds = config.getInt("game.fuse.step-seconds", defaults.fuseStepSeconds),
                minFuseSeconds = config.getInt("game.fuse.min-seconds", defaults.minFuseSeconds),
                playersPerPotato = config.getInt("game.players-per-potato", defaults.playersPerPotato),
                receiveCooldownTicks = config.getInt("game.receive-cooldown-ticks", defaults.receiveCooldownTicks),
                noTagBackTicks = config.getInt("game.no-tag-back-ticks", defaults.noTagBackTicks),
                intermissionSeconds = config.getInt("game.intermission-seconds", defaults.intermissionSeconds),
                clutchWindowTicks = config.getInt("game.clutch-window-ticks", defaults.clutchWindowTicks),
            )
        } catch (e: IllegalArgumentException) {
            logger.warning("Некорректные правила в config.yml (${e.message}), используются значения по умолчанию")
            defaults
        }
    }

    /**
     * MongoDB, если включена и доступна, иначе статистика в памяти до перезапуска.
     *
     * Переменные окружения важнее config.yml: в Docker и Kubernetes адрес базы
     * и пароль приходят из окружения и секретов, а не из файла в образе.
     */
    private fun connectStats(): StatsRepository {
        val envUri = System.getenv(ENV_MONGO_URI)?.takeIf { it.isNotBlank() }
        if (envUri == null && !config.getBoolean("mongodb.enabled", false)) {
            logger.info("MongoDB выключена: статистика хранится в памяти до перезапуска")
            return InMemoryStatsRepository()
        }
        return try {
            MongoStatsRepository(
                uri = envUri ?: config.getString("mongodb.uri", "mongodb://localhost:27017")!!,
                database = System.getenv(ENV_MONGO_DATABASE)?.takeIf { it.isNotBlank() }
                    ?: config.getString("mongodb.database", "hotpotato")!!,
            ).also { logger.info("Подключено к MongoDB" + if (envUri != null) " (адрес из окружения)" else "") }
        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Не удалось подключиться к MongoDB, статистика будет в памяти", e)
            InMemoryStatsRepository()
        }
    }

    companion object {
        const val ENV_MONGO_URI = "HOTPOTATO_MONGODB_URI"
        const val ENV_MONGO_DATABASE = "HOTPOTATO_MONGODB_DATABASE"
        const val ENV_AUTO_JOIN = "HOTPOTATO_AUTO_JOIN_ARENA"
    }
}
