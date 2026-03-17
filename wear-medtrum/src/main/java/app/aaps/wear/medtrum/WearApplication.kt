package app.aaps.wear.medtrum

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.TemporaryBasalStorage
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.wear.medtrum.util.WearTimeUtil
import app.aaps.wear.medtrum.util.WearPreferences
import app.aaps.wear.medtrum.util.WearResourceHelper
import app.aaps.wear.medtrum.util.WearDateUtil
import app.aaps.wear.medtrum.util.WearTemporaryBasalStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * WearApplication
 *
 * Point d'entrée de l'application Wear OS.
 * Instancie et relie tous les composants sans Dagger/Hilt
 * (trop lourd pour Wear OS — on utilise un simple singleton manuel).
 *
 * GRAPHE DE DÉPENDANCES :
 *
 *   WearApplication
 *       ├── WearPreferences      (SharedPreferences adapté)
 *       ├── WearDateUtil         (DateUtil simplifié)
 *       ├── WearResourceHelper   (ResourceHelper simplifié)
 *       ├── WearTimeUtil         (conversion temps pompe)
 *       ├── PumpSyncWear         (base Room locale)
 *       ├── WearTemporaryBasalStorage
 *       ├── MedtrumPump          (logique métier — port direct AAPS)
 *       │       └── injecté dans MedtrumPacketDispatcher
 *       └── WearBleService       (démarré via startForegroundService)
 *
 * USAGE depuis n'importe où dans l'app :
 *   val pump = WearApplication.instance.medtrumPump
 *   val pumpSync = WearApplication.instance.pumpSync
 */
class WearApplication : Application() {

    companion object {
        /** Singleton — accessible partout dans l'app */
        lateinit var instance: WearApplication
            private set

        private const val PREFS_NAME    = "aaps_wear_prefs"
        const val KEY_PUMP_SERIAL       = "pump_serial"
        const val KEY_CGM_SOURCE        = "cgm_source"
        const val KEY_UNITS_MMOL        = "units_mmol"
        const val KEY_ONBOARDING_DONE   = "onboarding_done"
    }

    // ─────────────────────────────────────────────────────────────────
    // Composants du graphe
    // ─────────────────────────────────────────────────────────────────

    /** SharedPreferences brutes (pour settings utilisateur) */
    lateinit var sharedPrefs: SharedPreferences
        private set

    /** Adaptateur Preferences compatible avec MedtrumPump */
    lateinit var wearPreferences: WearPreferences
        private set

    /** Utilitaire dates */
    lateinit var dateUtil: WearDateUtil
        private set

    /** Utilitaire ressources (strings) */
    lateinit var resourceHelper: WearResourceHelper
        private set

    /** Conversion temps pompe ↔ Unix */
    lateinit var timeUtil: WearTimeUtil
        private set

    /** Base de données locale Wear OS */
    lateinit var pumpSync: PumpSyncWear
        private set

    /** Stockage temporaire des basals (pour reconciliation) */
    lateinit var temporaryBasalStorage: WearTemporaryBasalStorage
        private set

    /** Modèle pompe Medtrum — port direct de AAPS */
    lateinit var medtrumPump: MedtrumPump
        private set

    /** Scope coroutines pour l'application */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ─────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        initComponents()
        wireComponents()
        startBleServiceIfConfigured()
        observePumpState()
    }

    /**
     * Instancie tous les composants dans l'ordre des dépendances.
     */
    private fun initComponents() {
        sharedPrefs           = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        wearPreferences       = WearPreferences(sharedPrefs)
        dateUtil              = WearDateUtil()
        resourceHelper        = WearResourceHelper(this)
        timeUtil              = WearTimeUtil()
        pumpSync              = PumpSyncWear(this)
        temporaryBasalStorage = WearTemporaryBasalStorage()

        // MedtrumPump : port direct d'AAPS, utilise nos adaptateurs légers
        medtrumPump = MedtrumPump(
            aapsLogger            = WearAAPSLogger(),
            rh                    = resourceHelper,
            preferences           = wearPreferences,
            dateUtil              = dateUtil,
            pumpSync              = pumpSync,
            temporaryBasalStorage = temporaryBasalStorage
        )

        // Charger l'état persisté depuis les préférences
        medtrumPump.loadVarsFromSP()
    }

    /**
     * Branche les composants entre eux.
     * MedtrumPacketDispatcher reçoit MedtrumPump + WearTimeUtil
     * pour pouvoir traiter les paquets BLE dès leur arrivée.
     */
    private fun wireComponents() {
        MedtrumPacketDispatcher.medtrumPump = medtrumPump
        MedtrumPacketDispatcher.timeUtil    = timeUtil
    }

    /**
     * Démarre WearBleService si un numéro de série de pompe est configuré.
     */
    private fun startBleServiceIfConfigured() {
        val pumpSerial = sharedPrefs.getString(KEY_PUMP_SERIAL, "") ?: ""
        if (pumpSerial.isNotEmpty()) {
            WearBleService.start(this, pumpSerial)
        }
    }

    /**
     * Observe l'état de connexion BLE et met à jour la notification
     * du service en conséquence.
     */
    private fun observePumpState() {
        // Observer l'état de connexion pour logging / debug
        WearBleService.connectionState
            .onEach { state ->
                // L'état est déjà reflété dans la notification du ForegroundService
                // On peut ici déclencher des actions supplémentaires si besoin
                if (state == BleConnectionState.READY) {
                    // Pompe connectée et prête → synchronisation initiale
                    // (à implémenter : SynchronizePacket)
                }
            }
            .launchIn(appScope)

        // Observer les alarmes critiques
        MedtrumPacketDispatcher.lastAlarm
            .onEach { alarm ->
                if (alarm != app.aaps.pump.medtrum.comm.enums.AlarmState.NONE) {
                    // Déclencher une vibration / notification alarme sur la montre
                    triggerAlarmNotification(alarm.name)
                }
            }
            .launchIn(appScope)
    }

    /** Sauvegarde le numéro de série de la pompe et redémarre le service BLE */
    fun configurePumpSerial(serial: String) {
        sharedPrefs.edit().putString(KEY_PUMP_SERIAL, serial).apply()
        WearBleService.start(this, serial)
    }

    private fun triggerAlarmNotification(alarmName: String) {
        // TODO : vibration + notification alarme Wear OS
        // android.os.Vibrator.vibrate(...)
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Adaptateurs légers — remplacent les interfaces AAPS lourdes
// ─────────────────────────────────────────────────────────────────────────

/**
 * Logger minimal — redirige vers Android Log.
 * Remplace AAPSLogger (qui dépend de Timber + DI AAPS).
 */
class WearAAPSLogger : AAPSLogger {
    override fun debug(tag: app.aaps.core.interfaces.logging.LTag, message: String) =
        android.util.Log.d(tag.tag, message)
    override fun info(tag: app.aaps.core.interfaces.logging.LTag, message: String) =
        android.util.Log.i(tag.tag, message)
    override fun warn(tag: app.aaps.core.interfaces.logging.LTag, message: String) =
        android.util.Log.w(tag.tag, message)
    override fun error(tag: app.aaps.core.interfaces.logging.LTag, message: String) =
        android.util.Log.e(tag.tag, message)
    override fun error(tag: app.aaps.core.interfaces.logging.LTag, message: String, throwable: Throwable) =
        android.util.Log.e(tag.tag, message, throwable)
}
