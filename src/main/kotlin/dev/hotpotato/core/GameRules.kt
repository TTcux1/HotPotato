package dev.hotpotato.core

const val TICKS_PER_SECOND = 20

/**
 * Правила одной игры. Все длительности — в секундах или тиках (1 с = 20 тиков).
 *
 * @property minPlayers минимум игроков для старта отсчёта
 * @property maxPlayers вместимость арены
 * @property countdownSeconds отсчёт в лобби перед началом
 * @property initialFuseSeconds длина фитиля в первом раунде
 * @property fuseStepSeconds на сколько фитиль короче в каждом следующем раунде
 * @property minFuseSeconds фитиль не бывает короче
 * @property playersPerPotato одна картошка на столько живых игроков
 * @property receiveCooldownTicks сколько тиков после получения нельзя передать дальше
 * @property noTagBackTicks сколько тиков нельзя вернуть картошку тому, кто её дал
 * @property intermissionSeconds пауза между раундами
 * @property clutchWindowTicks передача в последние N тиков фитиля считается «клатчем»
 */
data class GameRules(
    val minPlayers: Int = 2,
    val maxPlayers: Int = 16,
    val countdownSeconds: Int = 15,
    val initialFuseSeconds: Int = 30,
    val fuseStepSeconds: Int = 3,
    val minFuseSeconds: Int = 10,
    val playersPerPotato: Int = 4,
    val receiveCooldownTicks: Int = 10,
    val noTagBackTicks: Int = 40,
    val intermissionSeconds: Int = 3,
    val clutchWindowTicks: Int = 20,
) {
    init {
        require(minPlayers >= 2) { "minPlayers должен быть не меньше 2" }
        require(maxPlayers >= minPlayers) { "maxPlayers меньше minPlayers" }
        require(countdownSeconds >= 1) { "countdownSeconds должен быть положительным" }
        require(minFuseSeconds >= 1) { "minFuseSeconds должен быть положительным" }
        require(initialFuseSeconds >= minFuseSeconds) { "initialFuseSeconds меньше minFuseSeconds" }
        require(fuseStepSeconds >= 0) { "fuseStepSeconds не может быть отрицательным" }
        require(playersPerPotato >= 2) { "playersPerPotato должен быть не меньше 2" }
        require(receiveCooldownTicks >= 0 && noTagBackTicks >= 0 && clutchWindowTicks >= 0)
        require(intermissionSeconds >= 0)
    }

    /** Длина фитиля в раунде [round] (нумерация с 1), в тиках. */
    fun fuseTicks(round: Int): Int {
        val seconds = (initialFuseSeconds - fuseStepSeconds * (round - 1)).coerceAtLeast(minFuseSeconds)
        return seconds * TICKS_PER_SECOND
    }

    /**
     * Сколько картошек в раунде при [alive] живых игроках.
     * Всегда хотя бы одна и всегда меньше числа игроков, чтобы кто-то пережил взрыв.
     */
    fun potatoCount(alive: Int): Int {
        if (alive < 2) return 0
        return (alive / playersPerPotato).coerceIn(1, alive - 1)
    }
}
