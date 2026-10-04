package dev.hotpotato.plugin

import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.Sorts
import com.mongodb.client.model.UpdateOptions
import com.mongodb.client.model.Updates
import dev.hotpotato.core.PlayerGameStats
import dev.hotpotato.core.PlayerStats
import dev.hotpotato.core.StatsRepository
import org.bson.Document
import java.util.Locale
import java.util.UUID

/**
 * Статистика в MongoDB, один документ на игрока:
 *
 * ```json
 * { "_id": "uuid", "name": "Steve", "nameLower": "steve",
 *   "games": 12, "wins": 3, "passes": 41, "clutchPasses": 2, "explosions": 9, "roundsSurvived": 27 }
 * ```
 *
 * Обновление идёт через атомарные `$inc` с upsert: если несколько серверов сети
 * одновременно допишут результаты одного игрока, ни одно значение не потеряется.
 */
class MongoStatsRepository(uri: String, database: String) : StatsRepository {

    private val client: MongoClient = MongoClients.create(uri)
    private val collection: MongoCollection<Document> =
        client.getDatabase(database).getCollection("player_stats")

    init {
        collection.createIndex(Indexes.ascending("nameLower"))
        collection.createIndex(Indexes.descending("wins", "passes"))
    }

    override fun record(id: UUID, name: String, game: PlayerGameStats, won: Boolean) {
        collection.updateOne(
            Filters.eq("_id", id.toString()),
            Updates.combine(
                Updates.set("name", name),
                Updates.set("nameLower", name.lowercase(Locale.ROOT)),
                Updates.inc("games", 1),
                Updates.inc("wins", if (won) 1 else 0),
                Updates.inc("passes", game.passes),
                Updates.inc("clutchPasses", game.clutchPasses),
                Updates.inc("explosions", if (game.exploded) 1 else 0),
                Updates.inc("roundsSurvived", game.roundsSurvived),
            ),
            UpdateOptions().upsert(true),
        )
    }

    override fun findByName(name: String): PlayerStats? =
        collection.find(Filters.eq("nameLower", name.lowercase(Locale.ROOT))).first()?.toStats()

    override fun top(limit: Int): List<PlayerStats> =
        collection.find()
            .sort(Sorts.orderBy(Sorts.descending("wins"), Sorts.descending("passes")))
            .limit(limit)
            .map { it.toStats() }
            .toList()

    override fun close() = client.close()

    private fun Document.toStats() = PlayerStats(
        id = UUID.fromString(getString("_id")),
        name = getString("name"),
        games = getInteger("games", 0),
        wins = getInteger("wins", 0),
        passes = getInteger("passes", 0),
        clutchPasses = getInteger("clutchPasses", 0),
        explosions = getInteger("explosions", 0),
        roundsSurvived = getInteger("roundsSurvived", 0),
    )
}
