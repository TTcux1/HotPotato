package dev.hotpotato.core

import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Накопленная статистика игрока за всё время. */
data class PlayerStats(
    val id: UUID,
    val name: String,
    val games: Int = 0,
    val wins: Int = 0,
    val passes: Int = 0,
    val clutchPasses: Int = 0,
    val explosions: Int = 0,
    val roundsSurvived: Int = 0,
) {
    val winRate: Double get() = if (games == 0) 0.0 else wins.toDouble() / games

    fun plus(game: PlayerGameStats, won: Boolean, newName: String) = copy(
        name = newName,
        games = games + 1,
        wins = wins + if (won) 1 else 0,
        passes = passes + game.passes,
        clutchPasses = clutchPasses + game.clutchPasses,
        explosions = explosions + if (game.exploded) 1 else 0,
        roundsSurvived = roundsSurvived + game.roundsSurvived,
    )
}

/**
 * Хранилище статистики. Методы блокирующие: вызывать их нужно не из главного
 * потока сервера, а из асинхронной задачи.
 */
interface StatsRepository : AutoCloseable {
    fun record(id: UUID, name: String, game: PlayerGameStats, won: Boolean)
    fun findByName(name: String): PlayerStats?
    fun top(limit: Int): List<PlayerStats>
    override fun close() {}
}

/** Хранилище в памяти: для тестов и для запуска без MongoDB. */
class InMemoryStatsRepository : StatsRepository {

    private val byId = ConcurrentHashMap<UUID, PlayerStats>()

    override fun record(id: UUID, name: String, game: PlayerGameStats, won: Boolean) {
        byId.compute(id) { _, current -> (current ?: PlayerStats(id, name)).plus(game, won, name) }
    }

    override fun findByName(name: String): PlayerStats? {
        val key = name.lowercase(Locale.ROOT)
        return byId.values.firstOrNull { it.name.lowercase(Locale.ROOT) == key }
    }

    override fun top(limit: Int): List<PlayerStats> =
        byId.values.sortedWith(compareByDescending<PlayerStats> { it.wins }.thenByDescending { it.passes })
            .take(limit)
}
