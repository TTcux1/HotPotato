package dev.hotpotato.plugin

import dev.hotpotato.core.PlayerStats
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import java.util.Locale

/**
 * /hotpotato join <арена> | leave | start | stats [ник] | top
 * /hotpotato create | setlobby | addspawn | delete <арена> | list   — для админов
 */
class HotPotatoCommand(private val plugin: HotPotatoPlugin) : TabExecutor {

    private val playerSubcommands = listOf("join", "leave", "stats", "top")
    private val adminSubcommands = listOf("start", "create", "setlobby", "addspawn", "delete", "list")

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val sub = args.getOrNull(0)?.lowercase(Locale.ROOT)
        if (sub in adminSubcommands && !sender.hasPermission(ADMIN_PERMISSION)) {
            sender.error("Недостаточно прав.")
            return true
        }
        when (sub) {
            "join" -> join(sender, args.getOrNull(1))
            "leave" -> leave(sender)
            "stats" -> stats(sender, args.getOrNull(1))
            "top" -> top(sender)
            "start" -> start(sender)
            "create" -> withArenaName(sender, args) { create(sender, it) }
            "setlobby" -> withArenaName(sender, args) { setLobby(sender, it) }
            "addspawn" -> withArenaName(sender, args) { addSpawn(sender, it) }
            "delete" -> withArenaName(sender, args) { delete(sender, it) }
            "list" -> list(sender)
            else -> usage(sender, label)
        }
        return true
    }

    // ------------------------------------------------------------------ игроки

    private fun join(sender: CommandSender, arenaName: String?) {
        val player = sender.asPlayer() ?: return
        val arena = arenaName?.let { plugin.arenas[it] } ?: plugin.arenas.all.firstOrNull { it.isReady }
        if (arena == null) {
            player.error(if (arenaName == null) "Нет готовых арен." else "Арена $arenaName не найдена.")
            return
        }
        when (plugin.sessions.join(player, arena)) {
            is SessionManager.JoinOutcome.Joined -> player.info("Ты в игре на арене ${arena.name}!")
            SessionManager.JoinOutcome.ArenaNotReady -> player.error("Арена не настроена: нужны лобби и хотя бы одна точка появления.")
            SessionManager.JoinOutcome.AlreadyInGame -> player.error("Ты уже в игре. /hotpotato leave — выйти.")
            SessionManager.JoinOutcome.Full -> player.error("Арена заполнена.")
            SessionManager.JoinOutcome.InProgress -> player.error("Игра уже идёт, подожди следующую.")
        }
    }

    private fun leave(sender: CommandSender) {
        val player = sender.asPlayer() ?: return
        if (plugin.sessions.leave(player)) player.info("Ты вышел из игры.") else player.error("Ты не в игре.")
    }

    private fun stats(sender: CommandSender, name: String?) {
        val target = name ?: (sender as? Player)?.name
        if (target == null) {
            sender.error("Укажи ник.")
            return
        }
        plugin.async({ plugin.stats.findByName(target) }) { stats ->
            if (stats == null) sender.error("Статистики по $target пока нет.") else showStats(sender, stats)
        }
    }

    private fun top(sender: CommandSender) {
        plugin.async({ plugin.stats.top(10) }) { top ->
            if (top.isEmpty()) {
                sender.info("Пока никто не играл.")
                return@async
            }
            sender.sendMessage(Component.text("Топ-10 по победам:", NamedTextColor.GOLD))
            top.forEachIndexed { i, s ->
                sender.sendMessage(Component.text(
                    "${i + 1}. ${s.name} — побед: ${s.wins}, игр: ${s.games}, передач: ${s.passes}",
                    NamedTextColor.GRAY))
            }
        }
    }

    private fun showStats(sender: CommandSender, s: PlayerStats) {
        val lines = listOf(
            "Игр: ${s.games}, побед: ${s.wins} (${"%.0f".format(s.winRate * 100)}%)",
            "Передач: ${s.passes}, из них клатчей: ${s.clutchPasses}",
            "Взрывов: ${s.explosions}, раундов пережито: ${s.roundsSurvived}",
        )
        sender.sendMessage(Component.text("Статистика ${s.name}:", NamedTextColor.GOLD))
        lines.forEach { sender.sendMessage(Component.text(it, NamedTextColor.GRAY)) }
    }

    // ------------------------------------------------------------------ админы

    private fun start(sender: CommandSender) {
        val player = sender.asPlayer() ?: return
        val session = plugin.sessions.sessionOf(player)
        when {
            session == null -> player.error("Зайди в игру, которую хочешь запустить.")
            session.forceStart() -> player.info("Игра стартует через несколько секунд.")
            else -> player.error("Нужно хотя бы 2 игрока.")
        }
    }

    private fun create(sender: CommandSender, name: String) {
        if (plugin.arenas.create(name) == null) {
            sender.error("Арена $name уже существует.")
            return
        }
        plugin.arenas.save()
        sender.info("Арена $name создана. Теперь /hotpotato setlobby $name и /hotpotato addspawn $name.")
    }

    private fun setLobby(sender: CommandSender, name: String) {
        val player = sender.asPlayer() ?: return
        val arena = plugin.arenas[name] ?: return player.error("Арена $name не найдена.")
        arena.lobby = player.location.clone()
        plugin.arenas.save()
        player.info("Лобби арены ${arena.name} установлено.")
    }

    private fun addSpawn(sender: CommandSender, name: String) {
        val player = sender.asPlayer() ?: return
        val arena = plugin.arenas[name] ?: return player.error("Арена $name не найдена.")
        arena.spawns += player.location.clone()
        plugin.arenas.save()
        player.info("Точка появления #${arena.spawns.size} добавлена на арену ${arena.name}.")
    }

    private fun delete(sender: CommandSender, name: String) {
        if (plugin.sessions.isArenaBusy(name)) {
            sender.error("На арене идёт игра.")
            return
        }
        if (plugin.arenas.delete(name)) {
            plugin.arenas.save()
            sender.info("Арена $name удалена.")
        } else {
            sender.error("Арена $name не найдена.")
        }
    }

    private fun list(sender: CommandSender) {
        val arenas = plugin.arenas.all
        if (arenas.isEmpty()) return sender.info("Арен пока нет. /hotpotato create <имя>")
        arenas.forEach {
            val status = if (it.isReady) "готова" else "не настроена"
            sender.info("${it.name}: $status, точек появления: ${it.spawns.size}")
        }
    }

    // ------------------------------------------------------------------ утилиты

    private inline fun withArenaName(sender: CommandSender, args: Array<String>, action: (String) -> Unit) {
        val name = args.getOrNull(1)
        if (name == null) sender.error("Укажи название арены.") else action(name)
    }

    private fun usage(sender: CommandSender, label: String) {
        sender.sendMessage(Component.text("Горячая картошка", NamedTextColor.GOLD))
        listOf(
            "/$label join [арена] — войти в игру",
            "/$label leave — выйти",
            "/$label stats [ник] — статистика",
            "/$label top — лучшие игроки",
        ).forEach { sender.sendMessage(Component.text(it, NamedTextColor.GRAY)) }
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(Component.text(
                "/$label create|setlobby|addspawn|delete <арена>, list, start", NamedTextColor.DARK_GRAY))
        }
    }

    private fun CommandSender.asPlayer(): Player? =
        (this as? Player) ?: run { error("Команда только для игроков."); null }

    private fun CommandSender.info(text: String) = sendMessage(Component.text(text, NamedTextColor.GREEN))
    private fun CommandSender.error(text: String) = sendMessage(Component.text(text, NamedTextColor.RED))

    override fun onTabComplete(
        sender: CommandSender, command: Command, alias: String, args: Array<String>,
    ): List<String> {
        val subs = if (sender.hasPermission(ADMIN_PERMISSION)) playerSubcommands + adminSubcommands else playerSubcommands
        return when (args.size) {
            1 -> subs.filter { it.startsWith(args[0].lowercase(Locale.ROOT)) }
            2 -> when (args[0].lowercase(Locale.ROOT)) {
                "join", "setlobby", "addspawn", "delete" ->
                    plugin.arenas.all.map { it.name }.filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
            else -> emptyList()
        }
    }

    companion object {
        const val ADMIN_PERMISSION = "hotpotato.admin"
    }
}
