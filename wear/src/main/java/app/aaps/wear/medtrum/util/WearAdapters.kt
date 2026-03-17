package app.aaps.wear.medtrum.util

import android.content.Context
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.core.keys.interfaces.BooleanComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.ComposedKey
import app.aaps.core.keys.interfaces.DoubleComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.DoubleNonPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.LongComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.LongNonPreferenceKey
import app.aaps.core.keys.interfaces.LongPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.StringComposedNonPreferenceKey
import app.aaps.core.keys.interfaces.StringNonPreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import java.util.concurrent.ConcurrentHashMap

// ── WearPreferences ───────────────────────────────────────────────────────

class WearPreferences(private val context: Context) : Preferences {

    private val prefs = context.getSharedPreferences("aaps_wear_prefs", Context.MODE_PRIVATE)

    override val simpleMode: Boolean get() = false
    override val apsMode: Boolean get() = true
    override val nsclientMode: Boolean get() = false
    override val pumpControlMode: Boolean get() = false

    override fun get(key: BooleanNonPreferenceKey): Boolean = prefs.getBoolean(key.key, key.defaultValue)
    override fun getIfExists(key: BooleanNonPreferenceKey): Boolean? = if (prefs.contains(key.key)) prefs.getBoolean(key.key, key.defaultValue) else null
    override fun put(key: BooleanNonPreferenceKey, value: Boolean) = prefs.edit().putBoolean(key.key, value).apply()
    override fun get(key: BooleanPreferenceKey): Boolean = prefs.getBoolean(key.key, key.defaultValue)
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean = prefs.getBoolean(key.key.format(*arguments), key.defaultValue)
    override fun get(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, defaultValue: Boolean): Boolean = prefs.getBoolean(key.key.format(*arguments), defaultValue)
    override fun getIfExists(key: BooleanComposedNonPreferenceKey, vararg arguments: Any): Boolean? { val k = key.key.format(*arguments); return if (prefs.contains(k)) prefs.getBoolean(k, key.defaultValue) else null }
    override fun put(key: BooleanComposedNonPreferenceKey, vararg arguments: Any, value: Boolean) = prefs.edit().putBoolean(key.key.format(*arguments), value).apply()

    override fun get(key: StringNonPreferenceKey): String = prefs.getString(key.key, key.defaultValue) ?: key.defaultValue
    override fun getIfExists(key: StringNonPreferenceKey): String? = prefs.getString(key.key, null)
    override fun put(key: StringNonPreferenceKey, value: String) = prefs.edit().putString(key.key, value).apply()
    override fun get(key: StringPreferenceKey): String = prefs.getString(key.key, key.defaultValue) ?: key.defaultValue
    override fun get(key: StringComposedNonPreferenceKey, vararg arguments: Any): String = prefs.getString(key.key.format(*arguments), key.defaultValue) ?: key.defaultValue
    override fun getIfExists(key: StringComposedNonPreferenceKey, vararg arguments: Any): String? = prefs.getString(key.key.format(*arguments), null)
    override fun put(key: StringComposedNonPreferenceKey, vararg arguments: Any, value: String) = prefs.edit().putString(key.key.format(*arguments), value).apply()

    override fun get(key: DoubleNonPreferenceKey): Double = prefs.getFloat(key.key, key.defaultValue.toFloat()).toDouble()
    override fun get(key: DoublePreferenceKey): Double = prefs.getFloat(key.key, key.defaultValue.toFloat()).toDouble()
    override fun getIfExists(key: DoublePreferenceKey): Double? = if (prefs.contains(key.key)) prefs.getFloat(key.key, 0f).toDouble() else null
    override fun put(key: DoubleNonPreferenceKey, value: Double) = prefs.edit().putFloat(key.key, value.toFloat()).apply()
    override fun get(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double = prefs.getFloat(key.key.format(*arguments), key.defaultValue.toFloat()).toDouble()
    override fun getIfExists(key: DoubleComposedNonPreferenceKey, vararg arguments: Any): Double? { val k = key.key.format(*arguments); return if (prefs.contains(k)) prefs.getFloat(k, 0f).toDouble() else null }
    override fun put(key: DoubleComposedNonPreferenceKey, vararg arguments: Any, value: Double) = prefs.edit().putFloat(key.key.format(*arguments), value.toFloat()).apply()

    override fun get(key: UnitDoublePreferenceKey): Double = prefs.getFloat(key.key, key.defaultValue.toFloat()).toDouble()
    override fun getIfExists(key: UnitDoublePreferenceKey): Double? = if (prefs.contains(key.key)) prefs.getFloat(key.key, 0f).toDouble() else null
    override fun put(key: UnitDoublePreferenceKey, value: Double) = prefs.edit().putFloat(key.key, value.toFloat()).apply()

    override fun get(key: IntNonPreferenceKey): Int = prefs.getInt(key.key, key.defaultValue)
    override fun getIfExists(key: IntNonPreferenceKey): Int? = if (prefs.contains(key.key)) prefs.getInt(key.key, 0) else null
    override fun put(key: IntNonPreferenceKey, value: Int) = prefs.edit().putInt(key.key, value).apply()
    override fun put(key: IntComposedNonPreferenceKey, vararg arguments: Any, value: Int) = prefs.edit().putInt(key.key.format(*arguments), value).apply()
    override fun inc(key: IntNonPreferenceKey) = prefs.edit().putInt(key.key, prefs.getInt(key.key, 0) + 1).apply()
    override fun get(key: IntComposedNonPreferenceKey, vararg arguments: Any): Int = prefs.getInt(key.key.format(*arguments), key.defaultValue)
    override fun get(key: IntPreferenceKey): Int = prefs.getInt(key.key, key.defaultValue)

    override fun get(key: LongNonPreferenceKey): Long = prefs.getLong(key.key, key.defaultValue)
    override fun getIfExists(key: LongNonPreferenceKey): Long? = if (prefs.contains(key.key)) prefs.getLong(key.key, 0L) else null
    override fun put(key: LongNonPreferenceKey, value: Long) = prefs.edit().putLong(key.key, value).apply()
    override fun get(key: LongPreferenceKey): Long = prefs.getLong(key.key, key.defaultValue)
    override fun inc(key: LongNonPreferenceKey) = prefs.edit().putLong(key.key, prefs.getLong(key.key, 0L) + 1L).apply()
    override fun get(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long = prefs.getLong(key.key.format(*arguments), key.defaultValue)
    override fun getIfExists(key: LongComposedNonPreferenceKey, vararg arguments: Any): Long? { val k = key.key.format(*arguments); return if (prefs.contains(k)) prefs.getLong(k, 0L) else null }
    override fun put(key: LongComposedNonPreferenceKey, vararg arguments: Any, value: Long) = prefs.edit().putLong(key.key.format(*arguments), value).apply()

    override fun remove(key: NonPreferenceKey) = prefs.edit().remove(key.key).apply()
    override fun remove(key: ComposedKey, vararg arguments: Any) = prefs.edit().remove(key.key.format(*arguments)).apply()

    override fun isUnitDependent(key: String): Boolean = false
    override fun get(key: String): NonPreferenceKey? = null
    override fun getIfExists(key: String): NonPreferenceKey? = null
    override fun getDependingOn(key: String): List<PreferenceKey> = emptyList()
    override fun registerPreferences(clazz: Class<out NonPreferenceKey>) {}
    override fun allMatchingStrings(key: ComposedKey): List<String> = emptyList()
    override fun allMatchingInts(key: ComposedKey): List<Int> = emptyList()
    override fun isExportableKey(key: String): Boolean = false
}

// ── WearTemporaryBasalStorage ─────────────────────────────────────────────
// Utilise PumpSync.PumpState.TemporaryBasal — la vraie signature de l'interface

class WearTemporaryBasalStorage : TemporaryBasalStorage {

    private val storage = ConcurrentHashMap<Long, PumpSync.PumpState.TemporaryBasal>()

    override fun add(temporaryBasal: PumpSync.PumpState.TemporaryBasal) {
        storage[temporaryBasal.timestamp] = temporaryBasal
        // Nettoyer les entrées de plus de 2h
        val cutoff = System.currentTimeMillis() - 2 * 60 * 60 * 1000L
        storage.entries.removeIf { it.key < cutoff }
    }

    override fun findTemporaryBasal(time: Long, rate: Double): PumpSync.PumpState.TemporaryBasal? =
        storage.values.firstOrNull { tbr ->
            kotlin.math.abs(tbr.timestamp - time) < 60_000L &&
            kotlin.math.abs(tbr.rate - rate) < 0.01
        }?.also { storage.remove(it.timestamp) }
}
