package dev.hotpotato.plugin

import dev.hotpotato.core.GameResult
import dev.hotpotato.core.GameRules
import dev.hotpotato.core.JoinResult
import dev.hotpotato.core.StatsRepository
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.logging.Level

/** Какие игры идут и кто в какой играет. */
class SessionManager(
    private val plugin: Plugin,
    private val rules: () -> GameRules,
    private val stats: () -> StatsRepository,
) {
    private val sessions = LinkedHashMap<String, GameSession>()

    sealed interface JoinOutcome {
        data class Joined(val session: GameSession) : JoinOutcome
        data object ArenaNotReady : JoinOutcome
        data object AlreadyInGame : JoinOutcome
        data object Full : JoinOutcome
        data object InProgress : JoinOutcome
    }

    fun sessionOf(player: Player): GameSession? = sessions.values.firstOrNull { it.contains(player) }

    fun join(player: Player, arena: Arena): JoinOutcome {
        if (!arena.isReady) return JoinOutcome.ArenaNotReady
        if (sessionOf(player) != null) return JoinOutcome.AlreadyInGame

        val session = sessions.getOrPut(arena.name) { GameSession(arena, rules(), ::onFinished) }
        return when (session.join(player)) {
            is JoinResult.Joined -> JoinOutcome.Joined(session)
            JoinResult.AlreadyJoined -> JoinOutcome.AlreadyInGame
            JoinResult.Full -> JoinOutcome.Full
            JoinResult.InProgress -> JoinOutcome.InProgress
        }
    }

    fun leave(player: Player): Boolean {
        val session = sessionOf(player) ?: return false
        session.leave(player)
        if (session.playerCount == 0) {
            sessions.remove(session.arena.name)
        }
        return true
    }

    fun session(arenaName: String): GameSession? = sessions[arenaName]

    fun isArenaBusy(arenaName: String) = sessions.keys.any { it.equals(arenaName, ignoreCase = true) }

    /** Вызывается каждый тик сервера. */
    fun tick() {
        val finished = sessions.values.filter { it.tick() }
        finished.forEach { sessions.remove(it.arena.name) }
    }

    fun shutdown() {
        sessions.values.forEach(GameSession::shutdown)
        sessions.clear()
    }

    /** Статистику пишем асинхронно: обращение к MongoDB не должно тормозить сервер. */
    private fun onFinished(session: GameSession, result: GameResult, names: Map<UUID, String>) {
        val repository = stats()
        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            for ((id, game) in result.stats) {
                try {
                    repository.record(id, names[id] ?: id.toString(), game, won = id == result.winner)
                } catch (e: Exception) {
                    plugin.logger.log(Level.WARNING, "Не удалось сохранить статистику игрока $id", e)
                }
            }
        })
        plugin.logger.info("Игра на арене ${session.arena.name} окончена, раундов: ${result.rounds}")
    }
}
