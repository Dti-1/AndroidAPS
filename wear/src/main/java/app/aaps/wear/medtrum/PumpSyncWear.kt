package app.aaps.wear.medtrum

import android.content.Context
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.PumpSync.TemporaryBasalType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * PumpSyncWear
 *
 * Implémentation légère de PumpSync pour Wear OS.
 * Stocke l'état en mémoire — pas de Room DB pour éviter les dépendances.
 * Seules les méthodes utilisées par MedtrumPump sont implémentées fonctionnellement.
 * Toutes les autres retournent false/Unit (non utilisées par le driver Medtrum).
 */
class PumpSyncWear(private val context: Context) : PumpSync {

    // État en mémoire — partagé avec l'UI via StateFlow
    private val _temporaryBasal = MutableStateFlow<PumpSync.PumpState.TemporaryBasal?>(null)
    val temporaryBasalFlow: StateFlow<PumpSync.PumpState.TemporaryBasal?> = _temporaryBasal

    private val _lastBolus = MutableStateFlow<PumpSync.PumpState.Bolus?>(null)

    // ── Méthodes utilisées par MedtrumPump ────────────────────────────────

    override fun connectNewPump(endRunning: Boolean) {
        if (endRunning) _temporaryBasal.value = null
    }

    override fun verifyPumpIdentification(type: PumpType, serialNumber: String): Boolean = true

    override fun expectedPumpState(): PumpSync.PumpState = PumpSync.PumpState(
        temporaryBasal = _temporaryBasal.value,
        extendedBolus  = null,
        bolus          = _lastBolus.value,
        profile        = null,
        serialNumber   = ""
    )

    override fun syncTemporaryBasalWithPumpId(
        timestamp: Long, rate: Double, duration: Long, isAbsolute: Boolean,
        type: TemporaryBasalType?, pumpId: Long, pumpType: PumpType, pumpSerial: String
    ): Boolean {
        _temporaryBasal.value = PumpSync.PumpState.TemporaryBasal(
            timestamp  = timestamp,
            duration   = duration,
            rate       = rate,
            isAbsolute = isAbsolute,
            type       = type ?: TemporaryBasalType.NORMAL,
            pumpId     = pumpId,
            pumpType   = pumpType,
            pumpSerial = pumpSerial
        )
        return true
    }

    override fun syncStopTemporaryBasalWithPumpId(
        timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String,
        ignorePumpIds: Boolean
    ): Boolean {
        _temporaryBasal.value = null
        return true
    }

    override fun invalidateTemporaryBasalWithPumpId(
        pumpId: Long, pumpType: PumpType, pumpSerial: String
    ): Boolean {
        if (_temporaryBasal.value?.pumpId == pumpId) _temporaryBasal.value = null
        return true
    }

    override fun syncBolusWithPumpId(
        timestamp: Long, amount: Double, type: BS.Type?, pumpId: Long,
        pumpType: PumpType, pumpSerial: String
    ): Boolean {
        _lastBolus.value = PumpSync.PumpState.Bolus(
            timestamp  = timestamp,
            amount     = amount,
            pumpId     = pumpId,
            pumpType   = pumpType,
            pumpSerial = pumpSerial
        )
        return true
    }

    override fun insertTherapyEventIfNewWithTimestamp(
        timestamp: Long, type: TE.Type, note: String?,
        pumpId: Long?, pumpType: PumpType, pumpSerial: String
    ): Boolean = true  // Enregistrement minimal — pas de DB sur Wear OS

    // ── Méthodes NON utilisées par MedtrumPump — stubs ────────────────────

    override fun addBolusWithTempId(timestamp: Long, amount: Double, temporaryId: Long, type: BS.Type, pumpType: PumpType, pumpSerial: String): Boolean = false
    override fun syncBolusWithTempId(timestamp: Long, amount: Double, temporaryId: Long, type: BS.Type?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean = false
    override fun syncCarbsWithTimestamp(timestamp: Long, amount: Double, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean = false
    override fun insertFingerBgIfNewWithTimestamp(timestamp: Long, glucose: Double, glucoseUnit: GlucoseUnit, note: String?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean = false
    override fun insertAnnouncement(error: String, pumpId: Long?, pumpType: PumpType, pumpSerial: String) {}
    override fun addTemporaryBasalWithTempId(timestamp: Long, rate: Double, duration: Long, isAbsolute: Boolean, tempId: Long, type: TemporaryBasalType, pumpType: PumpType, pumpSerial: String): Boolean = false
    override fun syncTemporaryBasalWithTempId(timestamp: Long, rate: Double, duration: Long, isAbsolute: Boolean, temporaryId: Long, type: TemporaryBasalType?, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean = false
    override fun invalidateTemporaryBasal(id: Long, sources: Sources, timestamp: Long): Boolean = false
    override fun invalidateTemporaryBasalWithTempId(temporaryId: Long): Boolean = false
    override fun syncExtendedBolusWithPumpId(timestamp: Long, amount: Double, duration: Long, isEmulatingTB: Boolean, pumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean = false
    override fun syncStopExtendedBolusWithPumpId(timestamp: Long, endPumpId: Long, pumpType: PumpType, pumpSerial: String): Boolean = false
    override fun createOrUpdateTotalDailyDose(timestamp: Long, bolusAmount: Double, basalAmount: Double, totalAmount: Double, pumpId: Long?, pumpType: PumpType, pumpSerial: String): Boolean = false
}
