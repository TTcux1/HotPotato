package dev.hotpotato.plugin

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect

/**
 * Состояние игрока до входа в игру. Восстанавливается при выходе,
 * чтобы мини-игра не съела инвентарь, если она стоит на общем сервере.
 */
class PlayerSnapshot private constructor(
    private val location: Location,
    private val contents: Array<ItemStack?>,
    private val gameMode: GameMode,
    private val effects: Collection<PotionEffect>,
    private val level: Int,
    private val exp: Float,
) {
    fun restore(player: Player) {
        player.activePotionEffects.forEach { player.removePotionEffect(it.type) }
        player.inventory.contents = contents
        player.gameMode = gameMode
        player.isGlowing = false
        player.addPotionEffects(effects)
        player.level = level
        player.exp = exp
        player.teleport(location)
    }

    companion object {
        fun capture(player: Player) = PlayerSnapshot(
            location = player.location.clone(),
            contents = player.inventory.contents.map { it?.clone() }.toTypedArray(),
            gameMode = player.gameMode,
            effects = player.activePotionEffects.toList(),
            level = player.level,
            exp = player.exp,
        )
    }
}
