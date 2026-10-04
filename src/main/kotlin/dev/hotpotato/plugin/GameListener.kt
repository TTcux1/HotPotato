package dev.hotpotato.plugin

import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent

/**
 * Удар по игроку = попытка передать картошку. Всё остальное, что могло бы
 * сломать игру (урон, голод, выбрасывание предметов, стройка), в игре запрещено.
 */
class GameListener(private val sessions: SessionManager) : Listener {

    /** EntityDamageByEntityEvent наследует EntityDamageEvent, поэтому один обработчик ловит оба. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity as? Player ?: return
        val session = sessions.sessionOf(victim) ?: return
        event.isCancelled = true

        if (event is EntityDamageByEntityEvent) {
            val attacker = event.damager as? Player ?: return
            if (sessions.sessionOf(attacker) === session) {
                session.onHit(attacker, victim)
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHunger(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        if (sessions.sessionOf(player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        if (sessions.sessionOf(event.player) != null) event.isCancelled = true
    }

    /** Чтобы картошку нельзя было снять с головы или переложить. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (sessions.sessionOf(player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        if (sessions.sessionOf(event.player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        if (sessions.sessionOf(event.player) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        sessions.leave(event.player)
    }
}
