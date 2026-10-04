package dev.hotpotato.core

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * Сообщения для клиентского мода HotPotato HUD (канал `hotpotato:hud`).
 *
 * Формат описан в репозитории мода (HudProtocol.kt): version, type, поля big-endian.
 * Здесь только кодирование — сервер ничего не читает от клиента.
 * Совместимость проверяется тестом на тех же эталонных байтах, что и в моде.
 */
object HudWire {

    const val CHANNEL = "hotpotato:hud"
    private const val VERSION = 1
    private const val TYPE_STATE = 1
    private const val TYPE_EVENT = 2
    private const val MAX_NAMES = 16

    fun state(
        phase: HotPotatoGame.Phase,
        round: Int,
        fuseTicksLeft: Int,
        fuseTotalTicks: Int,
        alive: Int,
        players: Int,
        youHold: Boolean,
        holders: List<String>,
    ): ByteArray = write(TYPE_STATE) {
        writeByte(phase.ordinal)
        writeInt(round)
        writeInt(fuseTicksLeft)
        writeInt(fuseTotalTicks)
        writeInt(alive)
        writeInt(players)
        writeBoolean(youHold)
        writeNames(holders)
    }

    fun passed(from: String, to: String, clutch: Boolean): ByteArray = event(1) {
        writeUTF(from)
        writeUTF(to)
        writeBoolean(clutch)
    }

    fun exploded(players: List<String>, aliveLeft: Int): ByteArray = event(2) {
        writeNames(players)
        writeInt(aliveLeft)
    }

    fun roundStarted(round: Int, fuseSeconds: Int, potatoes: Int): ByteArray = event(3) {
        writeInt(round)
        writeInt(fuseSeconds)
        writeByte(potatoes)
    }

    fun finished(winner: String?, rounds: Int): ByteArray = event(4) {
        writeBoolean(winner != null)
        writeUTF(winner ?: "")
        writeInt(rounds)
    }

    /** Убрать HUD: игрок вышел из игры, но остался на сервере. */
    fun hide(): ByteArray = event(5) {}

    private fun event(kind: Int, body: DataOutputStream.() -> Unit) = write(TYPE_EVENT) {
        writeByte(kind)
        body()
    }

    private fun write(type: Int, body: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use {
            it.writeByte(VERSION)
            it.writeByte(type)
            it.body()
        }
        return bytes.toByteArray()
    }

    private fun DataOutputStream.writeNames(names: List<String>) {
        val limited = names.take(MAX_NAMES)
        writeByte(limited.size)
        limited.forEach(::writeUTF)
    }
}
