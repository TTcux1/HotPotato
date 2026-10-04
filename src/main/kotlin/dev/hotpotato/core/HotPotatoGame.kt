package dev.hotpotato.core

import java.util.UUID
import kotlin.random.Random

/**
 * Состояние одной игры «Горячая картошка» как машина состояний.
 *
 * ```
 * WAITING ──(хватает игроков)──▶ COUNTDOWN ──▶ ROUND ──(взрыв)──▶ INTERMISSION ──▶ ROUND …
 *    ▲                              │                     │
 *    └────(кто-то вышел)────────────┘                     └──(остался один)──▶ FINISHED
 * ```
 *
 * Класс ничего не знает о Bukkit: время продвигается вызовами [tick] (один вызов = один тик),
 * случайность берётся из переданного [random]. Поэтому всю игру можно проиграть в тестах
 * детерминированно. Не потокобезопасен: вызывать из главного потока сервера.
 */
class HotPotatoGame(
    val rules: GameRules = GameRules(),
    private val random: Random = Random.Default,
) {

    enum class Phase { WAITING, COUNTDOWN, ROUND, INTERMISSION, FINISHED }

    var phase: Phase = Phase.WAITING
        private set

    var round: Int = 0
        private set

    private val joined = LinkedHashSet<UUID>()
    private val alive = LinkedHashSet<UUID>()
    private val holders = LinkedHashSet<UUID>()
    private val eliminationOrder = ArrayList<UUID>()
    private val stats = HashMap<UUID, PlayerGameStats>()

    /** Кто и когда передал картошку текущему держателю: для кулдауна и запрета возврата. */
    private val received = HashMap<UUID, Received>()

    private var now = 0L
    private var phaseTicksLeft = 0
    private var fuseTotalTicks = 0

    val players: Set<UUID> get() = joined.toSet()
    val alivePlayers: Set<UUID> get() = alive.toSet()
    val potatoHolders: Set<UUID> get() = holders.toSet()

    fun isPlaying(player: UUID) = player in joined
    fun isAlive(player: UUID) = player in alive
    fun isHolder(player: UUID) = player in holders

    /** Доля оставшегося фитиля от 1.0 до 0.0, для полоски боссбара. */
    val fuseProgress: Float
        get() = if (phase == Phase.ROUND && fuseTotalTicks > 0) {
            phaseTicksLeft.toFloat() / fuseTotalTicks
        } else 0f

    val secondsLeft: Int
        get() = ceilSeconds(phaseTicksLeft)

    // ------------------------------------------------------------------ участники

    fun join(player: UUID): JoinResult {
        if (player in joined) return JoinResult.AlreadyJoined
        if (phase != Phase.WAITING && phase != Phase.COUNTDOWN) return JoinResult.InProgress
        if (joined.size >= rules.maxPlayers) return JoinResult.Full

        joined += player
        val events = mutableListOf<GameEvent>(
            GameEvent.PlayerJoined(player, joined.size, rules.minPlayers),
        )
        if (phase == Phase.WAITING && joined.size >= rules.minPlayers) {
            events += startCountdown(rules.countdownSeconds)
        }
        return JoinResult.Joined(events)
    }

    fun leave(player: UUID): List<GameEvent> {
        if (!joined.remove(player)) return emptyList()
        val events = mutableListOf<GameEvent>(GameEvent.PlayerLeft(player, joined.size))

        when (phase) {
            Phase.WAITING, Phase.FINISHED -> Unit

            Phase.COUNTDOWN -> if (joined.size < rules.minPlayers) {
                phase = Phase.WAITING
                events += GameEvent.CountdownCancelled
            }

            Phase.ROUND, Phase.INTERMISSION -> {
                val wasHolder = holders.remove(player)
                alive.remove(player)
                received.remove(player)
                stats.putIfAbsent(player, PlayerGameStats())

                if (alive.size <= 1) {
                    events += finish()
                } else if (wasHolder && phase == Phase.ROUND &&
                    holders.size < rules.potatoCount(alive.size)
                ) {
                    val candidate = alive.filter { it !in holders }.randomOrNull(random)
                    if (candidate != null) {
                        holders += candidate
                        received[candidate] = Received(from = null, at = now)
                        events += GameEvent.PotatoReassigned(candidate)
                    }
                }
            }
        }
        return events
    }

    /** Начать раньше: сократить отсчёт до 3 секунд. */
    fun forceStart(): List<GameEvent> {
        if (joined.size < 2) return emptyList()
        return when (phase) {
            Phase.WAITING -> listOf(startCountdown(3))
            Phase.COUNTDOWN -> {
                phaseTicksLeft = phaseTicksLeft.coerceAtMost(3 * TICKS_PER_SECOND)
                emptyList()
            }
            else -> emptyList()
        }
    }

    // ------------------------------------------------------------------ время

    /** Продвинуть игру на один тик. */
    fun tick(): List<GameEvent> {
        now++
        return when (phase) {
            Phase.WAITING, Phase.FINISHED -> emptyList()
            Phase.COUNTDOWN -> tickCountdown()
            Phase.ROUND -> tickRound()
            Phase.INTERMISSION -> tickIntermission()
        }
    }

    private fun tickCountdown(): List<GameEvent> {
        phaseTicksLeft--
        if (phaseTicksLeft <= 0) {
            alive.clear()
            alive += joined
            joined.forEach { stats[it] = PlayerGameStats() }
            return startRound()
        }
        return if (phaseTicksLeft % TICKS_PER_SECOND == 0) {
            listOf(GameEvent.CountdownTick(phaseTicksLeft / TICKS_PER_SECOND))
        } else emptyList()
    }

    private fun tickRound(): List<GameEvent> {
        phaseTicksLeft--
        if (phaseTicksLeft > 0) {
            return if (phaseTicksLeft % TICKS_PER_SECOND == 0) {
                listOf(GameEvent.FuseSecond(phaseTicksLeft / TICKS_PER_SECOND))
            } else emptyList()
        }

        // Фитиль догорел: все держатели выбывают.
        val exploded = holders.toSet()
        holders.clear()
        received.clear()
        alive.removeAll(exploded)
        eliminationOrder += exploded
        exploded.forEach { id -> stats.compute(id) { _, s -> (s ?: PlayerGameStats()).copy(exploded = true) } }
        alive.forEach { id ->
            stats.compute(id) { _, s -> (s ?: PlayerGameStats()).let { it.copy(roundsSurvived = it.roundsSurvived + 1) } }
        }

        val events = mutableListOf<GameEvent>(GameEvent.Exploded(exploded, alive.size))
        if (alive.size <= 1) {
            events += finish()
        } else {
            phase = Phase.INTERMISSION
            phaseTicksLeft = rules.intermissionSeconds * TICKS_PER_SECOND
            events += GameEvent.IntermissionStarted(rules.intermissionSeconds)
            if (phaseTicksLeft == 0) events += startRound()
        }
        return events
    }

    private fun tickIntermission(): List<GameEvent> {
        phaseTicksLeft--
        return if (phaseTicksLeft <= 0) startRound() else emptyList()
    }

    // ------------------------------------------------------------------ передача

    fun tryPass(from: UUID, to: UUID): PassResult {
        if (phase != Phase.ROUND) return PassResult.NotInRound
        if (from !in holders) return PassResult.NotHolder
        if (to !in alive) return PassResult.TargetNotAlive
        if (to in holders) return PassResult.TargetAlreadyHolds

        val got = received[from]
        if (got != null) {
            val since = now - got.at
            if (since < rules.receiveCooldownTicks) return PassResult.JustReceived
            if (got.from == to && since < rules.noTagBackTicks) return PassResult.NoTagBack
        }

        val clutch = phaseTicksLeft <= rules.clutchWindowTicks
        holders.remove(from)
        holders += to
        received.remove(from)
        received[to] = Received(from = from, at = now)
        stats.compute(from) { _, s ->
            val base = s ?: PlayerGameStats()
            base.copy(passes = base.passes + 1, clutchPasses = base.clutchPasses + if (clutch) 1 else 0)
        }

        return PassResult.Passed(GameEvent.PotatoPassed(from, to, clutch))
    }

    // ------------------------------------------------------------------ фазы

    private fun startCountdown(seconds: Int): GameEvent {
        phase = Phase.COUNTDOWN
        phaseTicksLeft = seconds * TICKS_PER_SECOND
        return GameEvent.CountdownStarted(seconds)
    }

    private fun startRound(): List<GameEvent> {
        round++
        phase = Phase.ROUND
        fuseTotalTicks = rules.fuseTicks(round)
        phaseTicksLeft = fuseTotalTicks

        holders.clear()
        received.clear()
        val chosen = alive.shuffled(random).take(rules.potatoCount(alive.size))
        holders += chosen
        chosen.forEach { received[it] = Received(from = null, at = now) }

        return listOf(
            GameEvent.RoundStarted(round, fuseTotalTicks / TICKS_PER_SECOND, holders.toSet(), alive.toSet()),
        )
    }

    private fun finish(): GameEvent {
        phase = Phase.FINISHED
        holders.clear()
        received.clear()
        val winner = alive.singleOrNull()
        val placements = buildList {
            winner?.let(::add)
            addAll(eliminationOrder.asReversed())
        }
        return GameEvent.GameFinished(GameResult(winner, placements, stats.toMap(), round))
    }

    private fun ceilSeconds(ticks: Int) = (ticks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND

    private data class Received(val from: UUID?, val at: Long)
}
