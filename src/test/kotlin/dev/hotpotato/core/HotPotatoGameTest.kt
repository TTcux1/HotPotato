package dev.hotpotato.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.random.Random

class HotPotatoGameTest {

    private val rules = GameRules(
        minPlayers = 2,
        maxPlayers = 8,
        countdownSeconds = 2,
        initialFuseSeconds = 5,
        fuseStepSeconds = 2,
        minFuseSeconds = 2,
        playersPerPotato = 4,
        receiveCooldownTicks = 10,
        noTagBackTicks = 40,
        intermissionSeconds = 1,
        clutchWindowTicks = 20,
    )

    private fun players(n: Int) = List(n) { UUID(0, it.toLong() + 1) }

    private fun HotPotatoGame.ticks(n: Int): List<GameEvent> = (1..n).flatMap { tick() }

    /** Игра с игроками, доведённая до первого раунда. */
    private fun startedGame(n: Int, seed: Int = 42): Pair<HotPotatoGame, List<UUID>> {
        val game = HotPotatoGame(rules, Random(seed))
        val ids = players(n)
        ids.forEach { game.join(it) }
        game.ticks(rules.countdownSeconds * TICKS_PER_SECOND)
        assertEquals(HotPotatoGame.Phase.ROUND, game.phase)
        return game to ids
    }

    @Test
    fun countdownStartsWhenEnoughPlayersAndCancelsWhenSomeoneLeaves() {
        val game = HotPotatoGame(rules, Random(1))
        val (a, b) = players(2)

        val first = game.join(a) as JoinResult.Joined
        assertTrue(first.events.none { it is GameEvent.CountdownStarted })

        val second = game.join(b) as JoinResult.Joined
        assertTrue(second.events.any { it is GameEvent.CountdownStarted })
        assertEquals(HotPotatoGame.Phase.COUNTDOWN, game.phase)

        val leaveEvents = game.leave(b)
        assertTrue(GameEvent.CountdownCancelled in leaveEvents)
        assertEquals(HotPotatoGame.Phase.WAITING, game.phase)
    }

    @Test
    fun joinIsRejectedWhenFullOrRunning() {
        val game = HotPotatoGame(rules.copy(maxPlayers = 2), Random(1))
        val (a, b, c) = players(3)
        game.join(a)
        assertEquals(JoinResult.AlreadyJoined, game.join(a))
        game.join(b)
        assertEquals(JoinResult.Full, game.join(c))

        game.ticks(rules.countdownSeconds * TICKS_PER_SECOND)
        assertEquals(JoinResult.InProgress, game.join(UUID.randomUUID()))
    }

    @Test
    fun countdownTicksEverySecond() {
        val game = HotPotatoGame(rules, Random(1))
        players(2).forEach { game.join(it) }
        val ticks = game.ticks(TICKS_PER_SECOND).filterIsInstance<GameEvent.CountdownTick>()
        assertEquals(listOf(GameEvent.CountdownTick(1)), ticks)
    }

    @Test
    fun potatoCountScalesWithPlayersButAlwaysLeavesSurvivor() {
        val r = GameRules(playersPerPotato = 4)
        assertEquals(0, r.potatoCount(1))
        assertEquals(1, r.potatoCount(2))
        assertEquals(1, r.potatoCount(7))
        assertEquals(2, r.potatoCount(8))
        assertEquals(4, r.potatoCount(16))
        assertEquals(1, GameRules(playersPerPotato = 2).potatoCount(2))
    }

    @Test
    fun fuseShrinksEachRoundDownToMinimum() {
        assertEquals(5 * TICKS_PER_SECOND, rules.fuseTicks(1))
        assertEquals(3 * TICKS_PER_SECOND, rules.fuseTicks(2))
        assertEquals(2 * TICKS_PER_SECOND, rules.fuseTicks(3))
        assertEquals(2 * TICKS_PER_SECOND, rules.fuseTicks(10))
    }

    @Test
    fun firstRoundAssignsOnePotatoPerFourPlayers() {
        val (game, _) = startedGame(8)
        assertEquals(2, game.potatoHolders.size)
        assertEquals(1, game.round)
    }

    @Test
    fun passRules() {
        val (game, ids) = startedGame(3)
        val holder = game.potatoHolders.single()
        val others = ids - holder
        val target = others[0]

        assertEquals(PassResult.NotHolder, game.tryPass(target, holder))
        assertEquals(PassResult.JustReceived, game.tryPass(holder, target), "сразу после старта раунда")

        game.ticks(rules.receiveCooldownTicks)
        val passed = game.tryPass(holder, target)
        assertTrue(passed is PassResult.Passed)
        assertTrue(game.isHolder(target))
        assertFalse(game.isHolder(holder))

        game.ticks(rules.receiveCooldownTicks)
        assertEquals(PassResult.NoTagBack, game.tryPass(target, holder), "нельзя сразу вернуть отдавшему")
        assertTrue(game.tryPass(target, others[1]) is PassResult.Passed, "третьему можно")
    }

    @Test
    fun cannotPassToSomeoneAlreadyHolding() {
        val (game, _) = startedGame(8)
        val (h1, h2) = game.potatoHolders.toList()
        game.ticks(rules.receiveCooldownTicks)
        assertEquals(PassResult.TargetAlreadyHolds, game.tryPass(h1, h2))
    }

    @Test
    fun passInLastSecondIsClutch() {
        val (game, ids) = startedGame(2)
        val holder = game.potatoHolders.single()
        val target = (ids - holder).single()
        game.ticks(rules.fuseTicks(1) - rules.clutchWindowTicks)

        val result = game.tryPass(holder, target) as PassResult.Passed
        assertTrue(result.event.clutch)
    }

    @Test
    fun explosionEliminatesHolderAndStartsShorterRound() {
        val (game, ids) = startedGame(3)
        val holder = game.potatoHolders.single()

        val events = game.ticks(rules.fuseTicks(1))
        val exploded = events.filterIsInstance<GameEvent.Exploded>().single()
        assertEquals(setOf(holder), exploded.players)
        assertEquals(2, exploded.aliveLeft)
        assertFalse(game.isAlive(holder))
        assertEquals(HotPotatoGame.Phase.INTERMISSION, game.phase)

        val next = game.ticks(rules.intermissionSeconds * TICKS_PER_SECOND)
            .filterIsInstance<GameEvent.RoundStarted>().single()
        assertEquals(2, next.round)
        assertEquals(3, next.fuseSeconds)
        assertTrue(next.alive.all { it in ids && it != holder })
    }

    @Test
    fun lastSurvivorWinsWithPlacementsAndStats() {
        val (game, ids) = startedGame(3)
        val events = mutableListOf<GameEvent>()
        repeat(2_000) {
            if (game.phase != HotPotatoGame.Phase.FINISHED) events += game.tick()
        }

        val result = events.filterIsInstance<GameEvent.GameFinished>().single().result
        assertEquals(HotPotatoGame.Phase.FINISHED, game.phase)
        val winner = result.winner!!
        assertEquals(3, result.placements.size)
        assertEquals(winner, result.placements.first())
        assertEquals(ids.toSet(), result.placements.toSet())
        assertFalse(result.stats.getValue(winner).exploded)
        assertEquals(2, result.stats.getValue(winner).roundsSurvived)
        assertTrue(result.stats.filterKeys { it != winner }.values.all { it.exploded })
        assertEquals(2, result.rounds)
    }

    @Test
    fun holderLeavingHandsPotatoToSomeoneElse() {
        val (game, _) = startedGame(4)
        val holder = game.potatoHolders.single()

        val events = game.leave(holder)
        val reassigned = events.filterIsInstance<GameEvent.PotatoReassigned>().single()
        assertTrue(game.isHolder(reassigned.to))
        assertFalse(game.isPlaying(holder))
    }

    @Test
    fun gameEndsWhenOnlyOnePlayerRemains() {
        val (game, ids) = startedGame(2)
        val events = game.leave(ids[0])
        val result = events.filterIsInstance<GameEvent.GameFinished>().single().result
        assertEquals(ids[1], result.winner)
        assertEquals(HotPotatoGame.Phase.FINISHED, game.phase)
    }

    @Test
    fun forceStartShortensCountdown() {
        val game = HotPotatoGame(rules.copy(countdownSeconds = 60), Random(1))
        players(2).forEach { game.join(it) }
        game.forceStart()
        game.ticks(3 * TICKS_PER_SECOND)
        assertEquals(HotPotatoGame.Phase.ROUND, game.phase)
    }

    @Test
    fun fuseProgressGoesFromOneToZero() {
        val (game, _) = startedGame(2)
        assertEquals(1f, game.fuseProgress)
        game.ticks(rules.fuseTicks(1) / 2)
        assertEquals(0.5f, game.fuseProgress)
    }

    @Test
    fun statsAccumulateInRepository() {
        val repo = InMemoryStatsRepository()
        val id = UUID(0, 99)
        repo.record(id, "Steve", PlayerGameStats(passes = 3, clutchPasses = 1, roundsSurvived = 2), won = true)
        repo.record(id, "Steve", PlayerGameStats(passes = 1, exploded = true), won = false)

        val stats = repo.findByName("steve")!!
        assertEquals(2, stats.games)
        assertEquals(1, stats.wins)
        assertEquals(4, stats.passes)
        assertEquals(1, stats.clutchPasses)
        assertEquals(1, stats.explosions)
        assertEquals(0.5, stats.winRate)
        assertEquals(listOf(stats), repo.top(10))
    }
}
