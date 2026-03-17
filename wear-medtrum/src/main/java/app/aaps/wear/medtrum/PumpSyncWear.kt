package app.aaps.wear.medtrum

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.PumpSync.TemporaryBasalType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * PumpSyncWear
 *
 * Remplacement allégé de l'interface PumpSync d'AAPS pour Wear OS.
 *
 * POURQUOI PAS LA BASE ORIGINALE D'AAPS ?
 * La base Room d'AAPS (AppDatabase) a des dizaines de tables et dépend
 * de l'ensemble du framework AAPS (plugins, DI Hilt, etc.).
 * Sur Wear OS, on n'a besoin que d'un sous-ensemble minimal :
 *   - Basals temporaires en cours
 *   - Historique bolus récent (24h)
 *   - État courant de la pompe
 *
 * Cette implémentation utilise une base Room indépendante, légère,
 * qui n'a aucune dépendance vers le reste d'AAPS.
 *
 * SYNCHRONISATION AVEC LE TÉLÉPHONE :
 * Si l'utilisateur a aussi l'app AAPS sur son téléphone, les données
 * peuvent être synchronisées via le Wearable DataLayer API.
 * (WearDataSync.kt — à implémenter séparément)
 */
class PumpSyncWear(private val context: Context) : PumpSync {

    // ─────────────────────────────────────────────────────────────────
    // Base de données Room locale (Wear OS)
    // ─────────────────────────────────────────────────────────────────
    private val db: WearDatabase by lazy {
        Room.databaseBuilder(context, WearDatabase::class.java, "aaps_wear.db")
            .fallbackToDestructiveMigration() // Pour le développement — à remplacer par migrations en prod
            .build()
    }

    // ─────────────────────────────────────────────────────────────────
    // État courant de la pompe (observable par l'UI)
    // ─────────────────────────────────────────────────────────────────
    private val _pumpState = MutableStateFlow<WearPumpState?>(null)
    val pumpStateFlow: StateFlow<WearPumpState?> = _pumpState

    // ─────────────────────────────────────────────────────────────────
    // Implémentation PumpSync
    // ─────────────────────────────────────────────────────────────────

    /**
     * Synchronise un basal temporaire avec la base locale.
     * Appelé par MedtrumPump.handleBasalStatusUpdate()
     */
    override fun syncTemporaryBasalWithPumpId(
        timestamp: Long,
        rate: Double,
        duration: Long,
        isAbsolute: Boolean,
        type: TemporaryBasalType?,
        pumpId: Long,
        pumpType: PumpType,
        pumpSerial: String
    ): Boolean {
        val entity = TempBasalEntity(
            pumpId       = pumpId,
            timestamp    = timestamp,
            rate         = rate,
            duration     = duration,
            isAbsolute   = isAbsolute,
            type         = type?.name ?: "NORMAL",
            pumpType     = pumpType.name,
            pumpSerial   = pumpSerial,
            isActive     = true
        )
        db.tempBasalDao().insertOrReplace(entity)
        refreshPumpState()
        return true
    }

    /**
     * Marque la fin d'un basal temporaire.
     * Appelé quand la pompe repasse au basal standard.
     */
    override fun syncStopTemporaryBasalWithPumpId(
        timestamp: Long,
        endPumpId: Long,
        pumpType: PumpType,
        pumpSerial: String
    ): Boolean {
        db.tempBasalDao().deactivateAll()
        refreshPumpState()
        return true
    }

    /**
     * Invalide un basal temporaire (annulation).
     */
    override fun invalidateTemporaryBasalWithPumpId(
        pumpId: Long,
        pumpType: PumpType,
        pumpSerial: String
    ): Boolean {
        db.tempBasalDao().deleteByPumpId(pumpId)
        refreshPumpState()
        return true
    }

    /**
     * Retourne l'état attendu de la pompe (TBR en cours, bolus en cours…).
     * Appelé par MedtrumPump pour vérifier la cohérence des états.
     */
    override fun expectedPumpState(): PumpSync.PumpState {
        val activeTbr = db.tempBasalDao().getActive()
        val lastBolus = db.bolusDao().getLast()

        return PumpSync.PumpState(
            temporaryBasal = activeTbr?.toPumpSyncTbr(),
            bolus = lastBolus?.toPumpSyncBolus(),
            profile = null,     // Le profil est géré séparément via WearProfileStore
            serialNumber = ""
        )
    }

    /**
     * Synchronise un bolus avec la base locale.
     * Appelé après livraison d'un bolus.
     */
    override fun syncBolusWithPumpId(
        timestamp: Long,
        amount: Double,
        type: TE.Type?,
        pumpId: Long,
        pumpType: PumpType,
        pumpSerial: String
    ): Boolean {
        val entity = BolusEntity(
            pumpId     = pumpId,
            timestamp  = timestamp,
            amount     = amount,
            type       = type?.name ?: "NORMAL",
            pumpType   = pumpType.name,
            pumpSerial = pumpSerial
        )
        db.bolusDao().insertOrReplace(entity)
        return true
    }

    /**
     * Insère un événement thérapeutique (changement de canule, d'insuline…).
     * Appelé par MedtrumPump.handleNewPatch()
     */
    override fun insertTherapyEventIfNewWithTimestamp(
        timestamp: Long,
        type: TE.Type,
        note: String?,
        pumpType: PumpType,
        pumpSerial: String
    ): Boolean {
        val entity = TherapyEventEntity(
            timestamp  = timestamp,
            type       = type.name,
            note       = note ?: "",
            pumpType   = pumpType.name,
            pumpSerial = pumpSerial
        )
        db.therapyEventDao().insertIfNew(entity)
        return true
    }

    // ─────────────────────────────────────────────────────────────────
    // Méthodes de lecture pour l'UI
    // ─────────────────────────────────────────────────────────────────

    /** Flow du basal temporaire actif (pour l'affichage Wear OS) */
    fun activeTempBasalFlow(): Flow<TempBasalEntity?> =
        db.tempBasalDao().observeActive()

    /** Flow des 10 derniers bolus (pour l'historique) */
    fun recentBolusFlow(): Flow<List<BolusEntity>> =
        db.bolusDao().observeRecent(limit = 10)

    /** Nettoie les données de plus de 24h (à appeler périodiquement) */
    fun pruneOldData() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        db.bolusDao().deleteOlderThan(cutoff)
        db.therapyEventDao().deleteOlderThan(cutoff)
    }

    // ─────────────────────────────────────────────────────────────────
    // Interne
    // ─────────────────────────────────────────────────────────────────
    private fun refreshPumpState() {
        val activeTbr = db.tempBasalDao().getActive()
        _pumpState.value = WearPumpState(
            activeTempBasal = activeTbr,
            lastUpdated = System.currentTimeMillis()
        )
    }

    // Méthodes PumpSync non utilisées sur Wear OS — implémentations vides
    override fun connectNewPump(notify: Boolean) {}
    override fun announceBolusToEventLoop(amount: Double) {}
    override fun applyBolus(pumpId: Long, pumpType: PumpType, pumpSerial: String, amount: Double): Boolean = false
    override fun syncTemporaryBasalWithTBRType(timestamp: Long, rate: Double, duration: Long, isAbsolute: Boolean, type: TemporaryBasalType?, pumpType: PumpType, pumpSerial: String): Boolean = false
}

// ─────────────────────────────────────────────────────────────────────────
// Entités Room
// ─────────────────────────────────────────────────────────────────────────

/** Basal temporaire en cours ou passé */
@Entity(tableName = "temp_basals")
data class TempBasalEntity(
    @PrimaryKey val pumpId: Long,
    val timestamp:  Long,
    val rate:       Double,
    val duration:   Long,
    val isAbsolute: Boolean,
    val type:       String,
    val pumpType:   String,
    val pumpSerial: String,
    val isActive:   Boolean = false
) {
    fun toPumpSyncTbr(): PumpSync.PumpState.TemporaryBasal = PumpSync.PumpState.TemporaryBasal(
        timestamp  = timestamp,
        duration   = duration,
        rate       = rate,
        isAbsolute = isAbsolute,
        type       = TemporaryBasalType.valueOf(type),
        pumpId     = pumpId,
        pumpType   = PumpType.valueOf(pumpType),
        pumpSerial = pumpSerial
    )
}

/** Bolus délivré */
@Entity(tableName = "boluses")
data class BolusEntity(
    @PrimaryKey val pumpId: Long,
    val timestamp:  Long,
    val amount:     Double,
    val type:       String,
    val pumpType:   String,
    val pumpSerial: String
) {
    fun toPumpSyncBolus(): PumpSync.PumpState.Bolus = PumpSync.PumpState.Bolus(
        timestamp  = timestamp,
        amount     = amount,
        pumpId     = pumpId,
        pumpType   = PumpType.valueOf(pumpType),
        pumpSerial = pumpSerial
    )
}

/** Événement thérapeutique (changement canule, insuline…) */
@Entity(tableName = "therapy_events")
data class TherapyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp:  Long,
    val type:       String,
    val note:       String,
    val pumpType:   String,
    val pumpSerial: String
)

// ─────────────────────────────────────────────────────────────────────────
// DAOs Room
// ─────────────────────────────────────────────────────────────────────────

@Dao
interface TempBasalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(entity: TempBasalEntity)

    @Query("SELECT * FROM temp_basals WHERE isActive = 1 LIMIT 1")
    fun getActive(): TempBasalEntity?

    @Query("SELECT * FROM temp_basals WHERE isActive = 1 LIMIT 1")
    fun observeActive(): Flow<TempBasalEntity?>

    @Query("UPDATE temp_basals SET isActive = 0")
    fun deactivateAll()

    @Query("DELETE FROM temp_basals WHERE pumpId = :pumpId")
    fun deleteByPumpId(pumpId: Long)
}

@Dao
interface BolusDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertOrReplace(entity: BolusEntity)

    @Query("SELECT * FROM boluses ORDER BY timestamp DESC LIMIT 1")
    fun getLast(): BolusEntity?

    @Query("SELECT * FROM boluses ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BolusEntity>>

    @Query("DELETE FROM boluses WHERE timestamp < :cutoff")
    fun deleteOlderThan(cutoff: Long)
}

@Dao
interface TherapyEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfNew(entity: TherapyEventEntity)

    @Query("DELETE FROM therapy_events WHERE timestamp < :cutoff")
    fun deleteOlderThan(cutoff: Long)
}

// ─────────────────────────────────────────────────────────────────────────
// Base de données Room Wear OS
// ─────────────────────────────────────────────────────────────────────────

@Database(
    entities = [TempBasalEntity::class, BolusEntity::class, TherapyEventEntity::class],
    version  = 1,
    exportSchema = false
)
abstract class WearDatabase : RoomDatabase() {
    abstract fun tempBasalDao(): TempBasalDao
    abstract fun bolusDao(): BolusDao
    abstract fun therapyEventDao(): TherapyEventDao
}

// ─────────────────────────────────────────────────────────────────────────
// État courant de la pompe (pour l'UI Wear OS)
// ─────────────────────────────────────────────────────────────────────────

data class WearPumpState(
    val activeTempBasal: TempBasalEntity?,
    val lastUpdated: Long
)
