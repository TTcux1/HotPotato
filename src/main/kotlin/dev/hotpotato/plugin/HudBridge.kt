package dev.hotpotato.plugin

import dev.hotpotato.core.HudWire
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Отправка данных клиентскому моду HotPotato HUD.
 *
 * Мод при входе регистрирует канал `hotpotato:hud`, поэтому сервер точно знает,
 * у кого он установлен. Остальным игрокам ничего не отправляется,
 * они видят обычный боссбар.
 */
class HudBridge(private val plugin: Plugin) {

    fun register() {
        plugin.server.messenger.registerOutgoingPluginChannel(plugin, HudWire.CHANNEL)
    }

    fun hasMod(player: Player): Boolean = HudWire.CHANNEL in player.listeningPluginChannels

    fun send(player: Player, bytes: ByteArray) {
        if (hasMod(player)) player.sendPluginMessage(plugin, HudWire.CHANNEL, bytes)
    }

    fun broadcast(players: Collection<Player>, bytes: ByteArray) {
        players.forEach { send(it, bytes) }
    }
}
