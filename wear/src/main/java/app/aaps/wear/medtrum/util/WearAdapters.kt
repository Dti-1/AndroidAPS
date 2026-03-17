package app.aaps.wear.medtrum.util
import app.aaps.core.interfaces.pump.PumpSync

import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.core.interfaces.pump.TemporaryBasalStorage.PumpSync.PumpState.TemporaryBasal
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// ─────────────────────────────────────────────────────────────────────────
// WearPreferences
//
// Adaptateur léger de l'interface Preferences d'AAPS.
// MedtrumPump utilise cette interface pour lire/écrire ses settings.
// On stocke tout dans SharedPreferences standard.
// ─────────────────────────────────────────────────────────────────────────
class WearPreferences(private val prefs: SharedPreferences) : Preferences {

    override fun <T : Any> get(key: app.aaps.core.keys.interfaces.Key<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when (key.defaultValue) {
            is Long    -> (prefs.getLong(key.key, key.defaultValue as Long)    as T)
            is Int     -> (prefs.getInt(key.key, key.defaultValue as Int)      as T)
            is Double  -> (prefs.getFloat(key.key, (key.defaultValue as Double).toFloat()).toDouble() as T)
            is Boolean -> (prefs.getBoolean(key.key, key.defaultValue as Boolean) as T)
            is String  -> (prefs.getString(key.key, key.defaultValue as String) ?: "" as T)
            is Byte    -> (prefs.getInt(key.key, (key.defaultValue as Byte).toInt()).toByte() as T)
            else       -> key.defaultValue
        }
    }

    override fun <T : Any> put(key: app.aaps.core.keys.interfaces.Key<T>, value: T) {
        prefs.edit().apply {
            when (value) {
                is Long    -> putLong(key.key, value)
                is Int     -> putInt(key.key, value)
                is Double  -> putFloat(key.key, value.toFloat())
                is Boolean -> putBoolean(key.key, value)
                is String  -> putString(key.key, value)
                is Byte    -> putInt(key.key, value.toInt())
            }
        }.apply()
    }

    override fun <T : Any> isSet(key: app.aaps.core.keys.interfaces.Key<T>): Boolean =
        prefs.contains(key.key)

    override fun <T : Any> remove(key: app.aaps.core.keys.interfaces.Key<T>) {
        prefs.edit().remove(key.key).apply()
    }
}

// ─────────────────────────────────────────────────────────────────────────
// WearDateUtil
//
// Port minimal de DateUtil d'AAPS.
// MedtrumPump l'utilise pour formater les dates et obtenir l'heure courante.
// ─────────────────────────────────────────────────────────────────────────
class WearDateUtil : DateUtil {

    private val dtFormatter = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

    override fun now(): Long = System.currentTimeMillis()

    override fun dateAndTimeString(mills: Long): String =
        dtFormatter.format(Date(mills))

    override fun timeString(mills: Long): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(mills))

    override fun dateString(mills: Long): String =
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(mills))

    override fun secondsOfTheDayToMilliseconds(seconds: Int): Long =
        seconds * 1000L

    override fun toISOString(mills: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date(mills))
}

// ─────────────────────────────────────────────────────────────────────────
// WearResourceHelper
//
// Port minimal de ResourceHelper d'AAPS.
// MedtrumPump l'utilise pour les strings d'alarme (alarmStateToString).
// ─────────────────────────────────────────────────────────────────────────
class WearResourceHelper(private val context: Context) : ResourceHelper {

    override fun gs(id: Int, vararg args: Any?): String =
        try { context.getString(id) } catch (_: Exception) { "[$id]" }

    override fun gs(id: Int, vararg args: Any): String =
        try { context.getString(id, *args) } catch (_: Exception) { "[$id]" }

    override fun gq(id: Int, quantity: Int, vararg args: Any): String =
        try { context.resources.getQuantityString(id, quantity, *args) } catch (_: Exception) { "[$id]" }

    override fun gb(id: Int): Boolean =
        try { context.resources.getBoolean(id) } catch (_: Exception) { false }
}

// ─────────────────────────────────────────────────────────────────────────
// WearTemporaryBasalStorage
//
// Stockage en mémoire des basals temporaires en attente de reconciliation.
// Utilisé par MedtrumPump.handleBasalStatusUpdate() pour retrouver
// la durée d'un TBR à partir de son timestamp et de son débit.
// ─────────────────────────────────────────────────────────────────────────
class WearTemporaryBasalStorage : TemporaryBasalStorage {

    // Map timestamp → PumpSync.PumpState.TemporaryBasal (en mémoire, thread-safe)
    private val storage = ConcurrentHashMap<Long, PumpSync.PumpState.TemporaryBasal>()

    override fun add(tbr: PumpSync.PumpState.TemporaryBasal) {
        storage[tbr.timestamp] = tbr
        // Nettoyer les entrées de plus de 2h (ne peuvent plus être reconciliées)
        val cutoff = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        storage.keys.filter { it < cutoff }.forEach { storage.remove(it) }
    }

    /**
     * Trouve un TBR correspondant au timestamp et au débit donnés.
     * Tolérance : ±1 minute sur le timestamp, ±0.01 U/h sur le débit.
     */
    override fun findTemporaryBasal(timestamp: Long, rate: Double): PumpSync.PumpState.TemporaryBasal? {
        val toleranceMs   = 60_000L   // ±1 minute
        val toleranceRate = 0.01      // ±0.01 U/h

        return storage.values.firstOrNull { tbr ->
            kotlin.math.abs(tbr.timestamp - timestamp) < toleranceMs &&
            kotlin.math.abs(tbr.rate - rate) < toleranceRate
        }?.also { found ->
            // Supprimer après récupération (usage unique)
            storage.remove(found.timestamp)
        }
    }

    fun reset() = storage.clear()
}
