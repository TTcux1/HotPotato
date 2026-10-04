package dev.hotpotato.core

import java.util.UUID

/**
 * Что произошло в игре. Ядро только сообщает о событиях,
 * а слой плагина решает, как их показать: звуки, частицы, телепорты.
 */
sealed interface GameEvent {
    data class PlayerJoined(val player: UUID, val players: Int, val needed: Int) : GameEvent
    data class PlayerLeft(val player: UUID, val players: Int) : GameEvent

    data class CountdownStarted(val seconds: Int) : GameEvent
    data class CountdownTick(val secondsLeft: Int) : GameEvent
    data object CountdownCancelled : GameEvent

    data class RoundStarted(
        val round: Int,
        val fuseSeconds: Int,
        val holders: Set<UUID>,
        val alive: Set<UUID>,
    ) : GameEvent

    data class FuseSecond(val secondsLeft: Int) : GameEvent

    data class PotatoPassed(val from: UUID, val to: UUID, val clutch: Boolean) : GameEvent

    /** Держатель вышел из игры — картошка перешла случайному игроку. */
    data class PotatoReassigned(val to: UUID) : GameEvent

    data class Exploded(val players: Set<UUID>, val aliveLeft: Int) : GameEvent

    data class IntermissionStarted(val seconds: Int) : GameEvent

    data class GameFinished(val result: GameResult) : GameEvent
}

/** Почему передача не удалась (или что она удалась). */
sealed interface PassResult {
    data class Passed(val event: GameEvent.PotatoPassed) : PassResult
    data object NotInRound : PassResult
    data object NotHolder : PassResult
    data object TargetNotAlive : PassResult
    data object TargetAlreadyHolds : PassResult
    data object JustReceived : PassResult
    data object NoTagBack : PassResult
}

sealed interface JoinResult {
    data class Joined(val events: List<GameEvent>) : JoinResult
    data object AlreadyJoined : JoinResult
    data object Full : JoinResult
    data object InProgress : JoinResult
}

/** Итоги одного игрока за игру — именно это потом прибавляется к статистике в базе. */
data class PlayerGameStats(
    val passes: Int = 0,
    val clutchPasses: Int = 0,
    val exploded: Boolean = false,
    val roundsSurvived: Int = 0,
)

/**
 * @property winner победитель или null, если все вышли
 * @property placements места: победитель первым, затем в обратном порядке выбывания
 * @property stats итоги каждого, кто участвовал (включая вышедших)
 */
data class GameResult(
    val winner: UUID?,
    val placements: List<UUID>,
    val stats: Map<UUID, PlayerGameStats>,
    val rounds: Int,
)
