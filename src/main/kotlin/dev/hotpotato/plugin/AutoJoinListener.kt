package dev.hotpotato.plugin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/**
 * Режим выделенного сервера: игрок сразу попадает в игру, как на крупных сетях,
 * где под каждую мини-игру отдельный сервер за прокси.
 *
 * Кроме входа на сервер, раз в несколько секунд всех свободных игроков пробуем
 * поставить в следующую игру: так после окончания матча новый начинается сам.
 */
class AutoJoinListener(
    private val plugin: HotPotatoPlugin,
    private val arenaName: String,
) : Listener {

    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            plugin.server.onlinePlayers.forEach { tryJoin(it, notify = false) }
        }, RETRY_TICKS, RETRY_TICKS)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        // Тик спустя игрок полностью загружен, телепорт и инвентарь применятся корректно.
        plugin.server.scheduler.runTask(plugin, Runnable { tryJoin(player, notify = true) })
    }

    private fun tryJoin(player: Player, notify: Boolean) {
        if (!player.isOnline || plugin.sessions.sessionOf(player) != null) return
        val arena = plugin.arenas[arenaName]
        if (arena == null) {
            if (notify) plugin.logger.warning("Арена для автовхода '$arenaName' не найдена")
            return
        }
        val outcome = plugin.sessions.join(player, arena)
        if (notify && outcome !is SessionManager.JoinOutcome.Joined) {
            player.sendMessage(Component.text(
                "Сейчас идёт игра — ты попадёшь в следующую автоматически", NamedTextColor.YELLOW))
        }
    }

    private companion object {
        const val RETRY_TICKS = 5L * 20
    }
}
