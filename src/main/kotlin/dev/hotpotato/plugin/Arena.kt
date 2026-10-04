package dev.hotpotato.plugin

import org.bukkit.Location
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.Locale
import java.util.logging.Logger

/** Арена: точка ожидания и точки появления игроков в раунде. */
class Arena(
    val name: String,
    var lobby: Location? = null,
    val spawns: MutableList<Location> = mutableListOf(),
) {
    val isReady: Boolean get() = lobby != null && spawns.isNotEmpty()
}

/** Хранение арен в arenas.yml. */
class ArenaStore(private val file: File, private val logger: Logger) {

    private val arenas = LinkedHashMap<String, Arena>()

    val all: Collection<Arena> get() = arenas.values

    operator fun get(name: String): Arena? = arenas[key(name)]

    fun create(name: String): Arena? {
        if (key(name) in arenas) return null
        return Arena(name).also { arenas[key(name)] = it }
    }

    fun delete(name: String): Boolean = arenas.remove(key(name)) != null

    fun load() {
        arenas.clear()
        if (!file.exists()) return
        val yaml = YamlConfiguration.loadConfiguration(file)
        val root = yaml.getConfigurationSection("arenas") ?: return
        for (name in root.getKeys(false)) {
            val section = root.getConfigurationSection(name) ?: continue
            val arena = Arena(name, section.getLocation("lobby"))
            section.getConfigurationSection("spawns")?.let { spawns ->
                spawns.getKeys(false).sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                    .mapNotNullTo(arena.spawns) { spawns.getLocation(it) }
            }
            arenas[key(name)] = arena
        }
        logger.info("Загружено арен: ${arenas.size}")
    }

    fun save() {
        val yaml = YamlConfiguration()
        for (arena in arenas.values) {
            val path = "arenas.${arena.name}"
            yaml.set("$path.lobby", arena.lobby)
            arena.spawns.forEachIndexed { i, location -> yaml.set("$path.spawns.$i", location) }
        }
        yaml.save(file)
    }

    private fun key(name: String) = name.lowercase(Locale.ROOT)
}
