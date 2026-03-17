package app.aaps.wear.medtrum

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * WearBleService
 *
 * ForegroundService permanent pour maintenir la connexion BLE GATT
 * avec la pompe Medtrum sur Wear OS.
 *
 * POURQUOI UN FOREGROUND SERVICE ?
 * Wear OS (comme Android) tue agressivement les processus en arrière-plan.
 * Un ForegroundService avec notification persistante + WakeLock est le SEUL
 * moyen garanti de maintenir une connexion BLE active en permanence.
 *
 * CYCLE DE VIE :
 * START_STICKY → si le système tue le service, il est redémarré automatiquement.
 *
 * USAGE :
 *   // Démarrer le service
 *   WearBleService.start(context, pumpSerialHex)
 *   // Arrêter proprement
 *   WearBleService.stop(context)
 *   // Observer l'état depuis l'UI
 *   WearBleService.connectionState.collect { state -> ... }
 */
class WearBleService : Service() {

    // ─────────────────────────────────────────────────────────────────
    // UUIDs Medtrum (identiques au driver AAPS existant)
    // ─────────────────────────────────────────────────────────────────
    companion object {
        // Service UUID Medtrum
        val MEDTRUM_SERVICE_UUID: UUID = UUID.fromString("669A0C20-0008-968F-E311-6050405558B3")
        // Caractéristique de commande (écriture vers pompe)
        val MEDTRUM_WRITE_UUID: UUID  = UUID.fromString("669A0C20-0008-968F-E311-6050405558B3")
        // Caractéristique de notification (données reçues de la pompe)
        val MEDTRUM_NOTIFY_UUID: UUID = UUID.fromString("669A0C21-0008-968F-E311-6050405558B3")
        // Descriptor standard BLE pour activer les notifications
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Constantes du service
        private const val NOTIF_CHANNEL_ID   = "medtrum_ble_channel"
        private const val NOTIF_ID           = 1001
        private const val ACTION_START       = "app.aaps.wear.medtrum.START"
        private const val ACTION_STOP        = "app.aaps.wear.medtrum.STOP"
        private const val EXTRA_PUMP_SERIAL  = "pump_serial"

        // Délais de reconnexion (backoff exponentiel)
        private const val RECONNECT_DELAY_INITIAL_MS = 2_000L
        private const val RECONNECT_DELAY_MAX_MS     = 60_000L

        // État de connexion partagé (observable depuis l'UI Wear OS)
        private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
        val connectionState: StateFlow<BleConnectionState> = _connectionState

        // Dernière donnée reçue (partagée avec l'UI)
        private val _lastPacket = MutableStateFlow<ByteArray?>(null)
        val lastPacket: StateFlow<ByteArray?> = _lastPacket

        // Helper : démarrer le service
        fun start(context: Context, pumpSerialHex: String) {
            val intent = Intent(context, WearBleService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PUMP_SERIAL, pumpSerialHex)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        // Helper : arrêter le service
        fun stop(context: Context) {
            context.startService(Intent(context, WearBleService::class.java).apply {
                action = ACTION_STOP
            })
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // État interne
    // ─────────────────────────────────────────────────────────────────
    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var pumpSerialHex: String = ""
    private var reconnectDelayMs = RECONNECT_DELAY_INITIAL_MS
    private var isScanning = false

    // WakeLock : empêche Wear OS de suspendre le CPU pendant les échanges BLE
    private lateinit var wakeLock: PowerManager.WakeLock

    // Coroutine scope pour les opérations asynchrones
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reconnectJob: Job? = null

    // ─────────────────────────────────────────────────────────────────
    // Cycle de vie Service
    // ─────────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()
        acquireWakeLock()
        // Initialiser le dispatcher avec WearTimeUtil
        // MedtrumPump est fourni par WearApplication (DI ou singleton)
        MedtrumPacketDispatcher.timeUtil = app.aaps.wear.medtrum.util.WearTimeUtil()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Démarrage immédiat en foreground — OBLIGATOIRE sur Wear OS / Android 8+
        startForeground(NOTIF_ID, buildNotification("Connexion pompe Medtrum…"))

        when (intent?.action) {
            ACTION_START -> {
                pumpSerialHex = intent.getStringExtra(EXTRA_PUMP_SERIAL) ?: ""
                if (pumpSerialHex.isNotEmpty()) {
                    startScan()
                }
            }
            ACTION_STOP -> {
                disconnect()
                stopSelf()
            }
        }

        // START_STICKY : si le système tue le service, il est redémarré avec le dernier Intent
        return START_STICKY
    }

    override fun onDestroy() {
        disconnect()
        serviceScope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─────────────────────────────────────────────────────────────────
    // Scan BLE — trouve la pompe par son numéro de série
    // ─────────────────────────────────────────────────────────────────
    private fun startScan() {
        if (isScanning) return
        isScanning = true
        _connectionState.value = BleConnectionState.SCANNING
        updateNotification("Scan pompe ${pumpSerialHex}…")

        val scanner: BluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner ?: run {
            scheduleReconnect(); return
        }

        // Filtre sur le Service UUID Medtrum pour limiter les résultats
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(MEDTRUM_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(listOf(filter), settings, scanCallback)

        // Timeout scan : si rien trouvé après 30s → reconnexion différée
        serviceScope.launch {
            delay(30_000)
            if (isScanning) {
                stopScan()
                scheduleReconnect()
            }
        }
    }

    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Vérifier que c'est bien notre pompe via le nom du device
            // Le nom BLE Medtrum contient le numéro de série en hex
            val deviceName = result.device.name ?: return
            if (!deviceName.contains(pumpSerialHex, ignoreCase = true)) return

            stopScan()
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            _connectionState.value = BleConnectionState.ERROR
            scheduleReconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Connexion GATT
    // ─────────────────────────────────────────────────────────────────
    private fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = BleConnectionState.CONNECTING
        updateNotification("Connexion à la pompe…")

        // autoConnect=true : le système Android gère les reconnexions BLE de bas niveau
        // C'est important sur Wear OS pour survivre aux courtes interruptions BLE
        bluetoothGatt = device.connectGatt(
            this,
            true, // autoConnect
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    private fun disconnect() {
        stopScan()
        reconnectJob?.cancel()
        bluetoothGatt?.let {
            it.disconnect()
            it.close()
        }
        bluetoothGatt = null
        writeCharacteristic = null
        _connectionState.value = BleConnectionState.DISCONNECTED
    }

    // ─────────────────────────────────────────────────────────────────
    // Callbacks GATT — reçoit tous les événements BLE
    // ─────────────────────────────────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectDelayMs = RECONNECT_DELAY_INITIAL_MS // Reset backoff
                    _connectionState.value = BleConnectionState.CONNECTED
                    updateNotification("Pompe connectée ✓")
                    // Découvrir les services GATT de la pompe
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    writeCharacteristic = null
                    _connectionState.value = BleConnectionState.DISCONNECTED
                    updateNotification("Pompe déconnectée — reconnexion…")
                    scheduleReconnect()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                scheduleReconnect(); return
            }

            val medtrumService: BluetoothGattService = gatt.getService(MEDTRUM_SERVICE_UUID)
                ?: run { scheduleReconnect(); return }

            writeCharacteristic = medtrumService.getCharacteristic(MEDTRUM_WRITE_UUID)

            // Activer les notifications sur la caractéristique de réception
            val notifyChar = medtrumService.getCharacteristic(MEDTRUM_NOTIFY_UUID) ?: return
            gatt.setCharacteristicNotification(notifyChar, true)

            // Écrire le descriptor CCCD pour activer les notifications côté pompe
            val descriptor = notifyChar.getDescriptor(CCCD_UUID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }

            _connectionState.value = BleConnectionState.READY
            updateNotification("Pompe prête ✓")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Données reçues de la pompe → dispatcher complet (décode fieldMask)
            if (characteristic.uuid == MEDTRUM_NOTIFY_UUID) {
                _lastPacket.value = value
                MedtrumPacketDispatcher.handleNotification(value)
            }
        }

        // Fallback API < Android 13
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == MEDTRUM_NOTIFY_UUID) {
                val value = characteristic.value ?: return
                _lastPacket.value = value
                MedtrumPacketDispatcher.handleNotification(value)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            MedtrumPacketDispatcher.onWriteResult(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Envoi de commandes vers la pompe
    // ─────────────────────────────────────────────────────────────────

    /**
     * Envoie un paquet de commande à la pompe Medtrum.
     * Doit être appelé depuis une coroutine (suspend function).
     *
     * @param data  Payload binaire à envoyer (format Medtrum)
     * @return      true si l'écriture a été acceptée par le stack BLE
     */
    fun sendCommand(data: ByteArray): Boolean {
        val gatt   = bluetoothGatt ?: return false
        val char   = writeCharacteristic ?: return false
        val state  = _connectionState.value
        if (state != BleConnectionState.READY) return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Reconnexion avec backoff exponentiel
    // ─────────────────────────────────────────────────────────────────
    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(reconnectDelayMs)
            // Backoff exponentiel : double le délai à chaque échec, max 60s
            reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_DELAY_MAX_MS)
            if (pumpSerialHex.isNotEmpty()) startScan()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // WakeLock — empêche Wear OS de suspendre le CPU
    // ─────────────────────────────────────────────────────────────────
    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AAPSWear:MedtrumBle"
        ).apply {
            // WakeLock à durée indéterminée — libéré uniquement dans onDestroy()
            // Note : utiliser PARTIAL_WAKE_LOCK (CPU actif, écran peut s'éteindre)
            acquire()
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Notification persistante (obligatoire pour ForegroundService)
    // ─────────────────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Medtrum BLE",
            NotificationManager.IMPORTANCE_LOW // LOW = pas de son, mais visible
        ).apply {
            description = "Connexion permanente à la pompe Medtrum"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(message: String): Notification {
        // Intent pour ouvrir l'UI Wear principale depuis la notification
        val openUiIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openUiIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle("AAPS Wear — Medtrum")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Remplacer par icône AAPS
            .setOngoing(true) // Non-dismissable par l'utilisateur
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(message: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(message))
    }
}

// ─────────────────────────────────────────────────────────────────────────
// États de connexion BLE
// ─────────────────────────────────────────────────────────────────────────
enum class BleConnectionState {
    DISCONNECTED,   // Pas de connexion
    SCANNING,       // Scan BLE en cours
    CONNECTING,     // Tentative de connexion GATT
    CONNECTED,      // GATT connecté, services en cours de découverte
    READY,          // Prêt à envoyer/recevoir des commandes
    ERROR           // Erreur non récupérable
}


