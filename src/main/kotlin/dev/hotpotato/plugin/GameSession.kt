package dev.hotpotato.plugin

import dev.hotpotato.core.GameEvent
import dev.hotpotato.core.GameResult
import dev.hotpotato.core.GameRules
import dev.hotpotato.core.HotPotatoGame
import dev.hotpotato.core.HudWire
import dev.hotpotato.core.JoinResult
import dev.hotpotato.core.PassResult
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

/**
 * Одна идущая игра на арене. Переводит события ядра в то, что видит игрок:
 * телепорты, предметы, звуки, частицы, боссбар, а игрокам с модом — данные для HUD.
 */
class GameSession(
    val arena: Arena,
    private val rules: GameRules,
    private val hud: HudBridge,
    private val onFinished: (GameSession, GameResult, Map<UUID, String>) -> Unit,
) {
    private var game = HotPotatoGame(rules)
    private val snapshots = HashMap<UUID, PlayerSnapshot>()
    private val names = HashMap<UUID, String>()
    private val bossBar = BossBar.bossBar(
        Component.text("Ожидание игроков"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS,
    )

    /** Тиков до сброса арены после окончания игры. */
    private var resetTicksLeft = -1

    /** Тиков до следующей отправки состояния моду: раз в секунду. */
    private var hudSyncTicks = 0

    val playerCount: Int get() = game.players.size
    val phase: HotPotatoGame.Phase get() = game.phase

    fun contains(player: Player) = game.isPlaying(player.uniqueId)

    // ------------------------------------------------------------------ вход и выход

    fun join(player: Player): JoinResult {
        val result = game.join(player.uniqueId)
        if (result is JoinResult.Joined) {
            snapshots[player.uniqueId] = PlayerSnapshot.capture(player)
            names[player.uniqueId] = player.name
            prepareForLobby(player)
            player.showBossBar(bossBar)
            handle(result.events)
        }
        return result
    }

    fun leave(player: Player) {
        if (!contains(player)) return
        val events = game.leave(player.uniqueId)
        restore(player)
        handle(events)
    }

    /** @return true, если игра стартует (идёт отсчёт). */
    fun forceStart(): Boolean {
        handle(game.forceStart())
        return game.phase == HotPotatoGame.Phase.COUNTDOWN
    }

    /** Вернуть всех игроков в исходное состояние (конец игры или выключение сервера). */
    fun shutdown() {
        online().forEach(::restore)
        snapshots.clear()
        names.clear()
    }

    // ------------------------------------------------------------------ игровой цикл

    /** Вызывается каждый тик сервера. @return true, если сессию пора удалить. */
    fun tick(): Boolean {
        if (resetTicksLeft >= 0) {
            if (resetTicksLeft-- == 0) {
                shutdown()
                return true
            }
            return false
        }
        handle(game.tick())
        updateBossBar()
        if (--hudSyncTicks <= 0) syncHud()
        return false
    }

    fun onHit(attacker: Player, victim: Player) {
        when (val result = game.tryPass(attacker.uniqueId, victim.uniqueId)) {
            is PassResult.Passed -> handle(listOf(result.event))
            PassResult.JustReceived -> attacker.sendActionBar(text("Картошка ещё горячая, подожди…", NamedTextColor.GRAY))
            PassResult.NoTagBack -> attacker.sendActionBar(text("Нельзя сразу вернуть картошку!", NamedTextColor.RED))
            else -> Unit
        }
    }

    private fun handle(events: List<GameEvent>) {
        for (event in events) {
            when (event) {
                is GameEvent.PlayerJoined -> broadcast(text(
                    "${name(event.player)} присоединился (${event.players}/${event.needed} для старта)",
                    NamedTextColor.GRAY))

                is GameEvent.PlayerLeft -> broadcast(text("${name(event.player)} вышел", NamedTextColor.GRAY))

                is GameEvent.CountdownStarted -> broadcast(text("Игра начнётся через ${event.seconds} с", NamedTextColor.YELLOW))

                is GameEvent.CountdownTick -> if (event.secondsLeft <= 5) {
                    online().forEach {
                        it.sendActionBar(text("Старт через ${event.secondsLeft}…", NamedTextColor.YELLOW))
                        it.playSound(it.location, Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f)
                    }
                }

                GameEvent.CountdownCancelled -> broadcast(text("Недостаточно игроков, отсчёт остановлен", NamedTextColor.RED))

                is GameEvent.RoundStarted -> startRound(event)

                is GameEvent.FuseSecond -> if (event.secondsLeft <= 5) {
                    holders().forEach { it.playSound(it.location, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 1f, 2f) }
                }

                is GameEvent.PotatoPassed -> onPassed(event)

                is GameEvent.PotatoReassigned -> player(event.to)?.let {
                    givePotato(it)
                    it.sendMessage(text("Держатель сбежал — картошка теперь у тебя!", NamedTextColor.GOLD))
                }

                is GameEvent.Exploded -> onExploded(event)

                is GameEvent.IntermissionStarted -> broadcast(text("Следующий раунд через ${event.seconds} с", NamedTextColor.GRAY))

                is GameEvent.GameFinished -> onGameFinished(event.result)
            }
            forwardToHud(event)
        }
        // Что-то изменилось — мод получает новое состояние сразу, не дожидаясь секунды.
        if (events.isNotEmpty()) syncHud()
    }

    // ------------------------------------------------------------------ клиентский HUD

    private fun forwardToHud(event: GameEvent) {
        val bytes = when (event) {
            is GameEvent.RoundStarted -> HudWire.roundStarted(event.round, event.fuseSeconds, event.holders.size)
            is GameEvent.PotatoPassed -> HudWire.passed(name(event.from), name(event.to), event.clutch)
            is GameEvent.Exploded -> HudWire.exploded(event.players.map(::name), event.aliveLeft)
            is GameEvent.GameFinished -> HudWire.finished(event.result.winner?.let(::name), event.result.rounds)
            else -> return
        }
        hud.broadcast(online(), bytes)
    }

    /**
     * Состояние каждому игроку отдельно: у каждого своё «картошка у тебя».
     * Заодно переключаем боссбар: у кого мод — тому он не нужен.
     */
    private fun syncHud() {
        hudSyncTicks = 20
        val holders = game.potatoHolders.map(::name)
        for (player in online()) {
            if (!hud.hasMod(player)) {
                player.showBossBar(bossBar)
                continue
            }
            player.hideBossBar(bossBar)
            hud.send(player, HudWire.state(
                phase = game.phase,
                round = game.round,
                fuseTicksLeft = game.ticksLeft,
                fuseTotalTicks = game.fuseTotal,
                alive = game.alivePlayers.size,
                players = game.players.size,
                youHold = game.isHolder(player.uniqueId),
                holders = holders,
            ))
        }
    }

    // ------------------------------------------------------------------ обработчики событий

    private fun startRound(event: GameEvent.RoundStarted) {
        val spawns = arena.spawns
        event.alive.mapNotNull(::player).forEachIndexed { i, player ->
            prepareForRound(player)
            player.teleport(spawns[i % spawns.size])
        }
        event.holders.mapNotNull(::player).forEach(::givePotato)

        val potatoes = event.holders.size
        broadcast(text("Раунд ${event.round}: фитиль ${event.fuseSeconds} с, картошек: $potatoes. Бей игрока, чтобы передать!",
            NamedTextColor.GOLD))
        online().forEach { it.playSound(it.location, Sound.ENTITY_TNT_PRIMED, 1f, 1f) }
    }

    private fun onPassed(event: GameEvent.PotatoPassed) {
        val from = player(event.from)
        val to = player(event.to)
        from?.let(::takePotato)
        to?.let(::givePotato)
        from?.playSound(from.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.5f)
        to?.playSound(to.location, Sound.ENTITY_BLAZE_SHOOT, 1f, 1f)
        to?.sendActionBar(text("У тебя горячая картошка! Передай её ударом!", NamedTextColor.RED))
        if (event.clutch) {
            broadcast(text("КЛАТЧ! ${name(event.from)} спихнул картошку ${name(event.to)} в последний момент!",
                NamedTextColor.LIGHT_PURPLE))
        }
    }

    private fun onExploded(event: GameEvent.Exploded) {
        for (id in event.players) {
            val player = player(id) ?: continue
            val location = player.location
            location.world?.spawnParticle(Particle.EXPLOSION, location, 3)
            location.world?.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
            takePotato(player)
            player.gameMode = GameMode.SPECTATOR
            player.sendMessage(text("Бум! Ты выбыл.", NamedTextColor.RED))
        }
        val names = event.players.joinToString { name(it) }
        broadcast(text("Взорвались: $names. Осталось игроков: ${event.aliveLeft}", NamedTextColor.GOLD))
    }

    private fun onGameFinished(result: GameResult) {
        val winner = result.winner
        if (winner != null) {
            broadcast(text("Победитель: ${name(winner)}! Раундов сыграно: ${result.rounds}", NamedTextColor.GREEN))
            player(winner)?.let { it.playSound(it.location, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f) }
        } else {
            broadcast(text("Игра окончена без победителя", NamedTextColor.GRAY))
        }
        bossBar.name(text("Игра окончена", NamedTextColor.GREEN)).progress(1f)
        onFinished(this, result, names.toMap())
        resetTicksLeft = 5 * 20
    }

    // ------------------------------------------------------------------ состояние игроков

    private fun prepareForLobby(player: Player) {
        resetState(player)
        player.gameMode = GameMode.ADVENTURE
        arena.lobby?.let(player::teleport)
    }

    private fun prepareForRound(player: Player) {
        resetState(player)
        player.gameMode = GameMode.ADVENTURE
    }

    private fun resetState(player: Player) {
        player.inventory.clear()
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.isGlowing = false
        player.foodLevel = 20
    }

    private fun givePotato(player: Player) {
        player.inventory.helmet = ItemStack(Material.TNT)
        player.inventory.setItem(0, potatoItem())
        player.isGlowing = true
        // Держатель быстрее: так у него есть шанс догнать и передать картошку.
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, Int.MAX_VALUE, 1, false, false))
    }

    private fun takePotato(player: Player) {
        player.inventory.helmet = null
        player.inventory.setItem(0, null)
        player.isGlowing = false
        player.removePotionEffect(PotionEffectType.SPEED)
    }

    private fun potatoItem(): ItemStack = ItemStack(Material.BAKED_POTATO).also { item ->
        item.editMeta { it.displayName(text("Горячая картошка!", NamedTextColor.RED)) }
    }

    private fun restore(player: Player) {
        player.hideBossBar(bossBar)
        hud.send(player, HudWire.hide())
        snapshots.remove(player.uniqueId)?.restore(player)
    }

    private fun updateBossBar() {
        when (game.phase) {
            HotPotatoGame.Phase.ROUND -> bossBar
                .name(text("Раунд ${game.round} — взрыв через ${game.secondsLeft} с", NamedTextColor.RED))
                .progress(game.fuseProgress.coerceIn(0f, 1f))
                .color(if (game.fuseProgress < 0.25f) BossBar.Color.RED else BossBar.Color.YELLOW)

            HotPotatoGame.Phase.COUNTDOWN -> bossBar
                .name(text("Старт через ${game.secondsLeft} с", NamedTextColor.YELLOW)).progress(1f)

            HotPotatoGame.Phase.WAITING -> bossBar
                .name(text("Ожидание игроков: ${game.players.size}/${rules.minPlayers}", NamedTextColor.GRAY))
                .progress(1f)

            else -> Unit
        }
    }

    // ------------------------------------------------------------------ утилиты

    private fun player(id: UUID): Player? = Bukkit.getPlayer(id)
    private fun online(): List<Player> = game.players.mapNotNull(::player)
    private fun holders(): List<Player> = game.potatoHolders.mapNotNull(::player)
    private fun name(id: UUID): String = names[id] ?: "???"
    private fun broadcast(message: Component) = online().forEach { it.sendMessage(message) }
    private fun text(content: String, color: NamedTextColor): Component = Component.text(content, color)
}
