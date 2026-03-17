package app.aaps.wear.medtrum

import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.BasalType
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.extension.toInt
import app.aaps.pump.medtrum.extension.toLong
import app.aaps.wear.medtrum.util.WearTimeUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MedtrumPacketDispatcher
 *
 * Port direct de NotificationPacket.kt d'AAPS pour Wear OS.
 *
 * Reçoit les paquets BLE bruts de WearBleService et les décode
 * selon le protocole binaire Medtrum (fieldMask + données concaténées).
 *
 * STRUCTURE D'UN PAQUET NOTIFICATION MEDTRUM :
 * ┌─────────┬───────────┬──────────────────────────────┐
 * │ Byte 0  │ Bytes 1-2 │ Bytes 3…n                    │
 * │  State  │ FieldMask │ Données (champs concaténés)  │
 * └─────────┴───────────┴──────────────────────────────┘
 *
 * Le FieldMask est un BitMask 16 bits qui indique quels champs
 * sont présents dans la suite du paquet. Chaque bit correspond
 * à un type de données (basal, bolus, réservoir, alarme…).
 *
 * Les données de chaque champ sont concaténées dans l'ordre
 * croissant des bits du masque.
 */
object MedtrumPacketDispatcher {

    // ─────────────────────────────────────────────────────────────────
    // Constantes du protocole Medtrum (identiques à NotificationPacket)
    // ─────────────────────────────────────────────────────────────────

    // Position de l'octet d'état
    private const val NOTIF_STATE_START = 0
    private const val NOTIF_STATE_END   = NOTIF_STATE_START + 1

    // Bits du FieldMask
    private const val MASK_SUSPEND                  = 0x01
    private const val MASK_NORMAL_BOLUS             = 0x02
    private const val MASK_EXTENDED_BOLUS           = 0x04
    private const val MASK_BASAL                    = 0x08
    private const val MASK_SETUP                    = 0x10
    private const val MASK_RESERVOIR               = 0x20
    private const val MASK_START_TIME              = 0x40
    private const val MASK_BATTERY                 = 0x80
    private const val MASK_STORAGE                 = 0x100
    private const val MASK_ALARM                   = 0x200
    private const val MASK_AGE                     = 0x400
    private const val MASK_MAGNETO_PLACE           = 0x800
    private const val MASK_UNUSED_CGM              = 0x1000
    private const val MASK_UNUSED_COMMAND_CONFIRM  = 0x2000
    private const val MASK_UNUSED_AUTO_STATUS      = 0x4000
    private const val MASK_UNUSED_LEGACY           = 0x8000

    // Tailles de chaque champ en octets
    private const val SIZE_FIELD_MASK              = 2
    private const val SIZE_SUSPEND                 = 4
    private const val SIZE_NORMAL_BOLUS            = 3
    private const val SIZE_EXTENDED_BOLUS          = 3
    private const val SIZE_BASAL                   = 12
    private const val SIZE_SETUP                   = 1
    private const val SIZE_RESERVOIR               = 2
    private const val SIZE_START_TIME              = 4
    private const val SIZE_BATTERY                 = 3
    private const val SIZE_STORAGE                 = 4
    private const val SIZE_ALARM                   = 4
    private const val SIZE_AGE                     = 4
    private const val SIZE_MAGNETO_PLACE           = 2
    private const val SIZE_UNUSED_CGM              = 5
    private const val SIZE_UNUSED_COMMAND_CONFIRM  = 2
    private const val SIZE_UNUSED_AUTO_STATUS      = 2
    private const val SIZE_UNUSED_LEGACY           = 2

    // Map mask → taille (pour calcul d'offset et validation)
    private val sizeMap = linkedMapOf(  // LinkedHashMap = ordre d'insertion garanti = ordre des bits
        MASK_SUSPEND                 to SIZE_SUSPEND,
        MASK_NORMAL_BOLUS            to SIZE_NORMAL_BOLUS,
        MASK_EXTENDED_BOLUS          to SIZE_EXTENDED_BOLUS,
        MASK_BASAL                   to SIZE_BASAL,
        MASK_SETUP                   to SIZE_SETUP,
        MASK_RESERVOIR               to SIZE_RESERVOIR,
        MASK_START_TIME              to SIZE_START_TIME,
        MASK_BATTERY                 to SIZE_BATTERY,
        MASK_STORAGE                 to SIZE_STORAGE,
        MASK_ALARM                   to SIZE_ALARM,
        MASK_AGE                     to SIZE_AGE,
        MASK_MAGNETO_PLACE           to SIZE_MAGNETO_PLACE,
        MASK_UNUSED_CGM              to SIZE_UNUSED_CGM,
        MASK_UNUSED_COMMAND_CONFIRM  to SIZE_UNUSED_COMMAND_CONFIRM,
        MASK_UNUSED_AUTO_STATUS      to SIZE_UNUSED_AUTO_STATUS,
        MASK_UNUSED_LEGACY           to SIZE_UNUSED_LEGACY
    )

    // ─────────────────────────────────────────────────────────────────
    // Dépendances injectées au démarrage du service
    // ─────────────────────────────────────────────────────────────────

    /** Modèle pompe — mis à jour par les parsers */
    var medtrumPump: MedtrumPump? = null

    /** Utilitaire de conversion temps pompe ↔ temps système */
    var timeUtil: WearTimeUtil? = null

    // ─────────────────────────────────────────────────────────────────
    // StateFlows exposés à l'UI Wear OS
    // ─────────────────────────────────────────────────────────────────

    /** Dernière alarme reçue (pour affichage immédiat sur la montre) */
    private val _lastAlarm = MutableStateFlow(AlarmState.NONE)
    val lastAlarm: StateFlow<AlarmState> = _lastAlarm

    /** Indique qu'un paquet vient d'être traité (pour debug/UI) */
    private val _packetCount = MutableStateFlow(0)
    val packetCount: StateFlow<Int> = _packetCount

    // Timestamp de démarrage d'un nouveau patch (utilisé dans handleStorage)
    private var newPatchStartTime = 0L

    // Callback pour résultat d'écriture BLE (synchronisation commandes)
    private var writeResultCallback: ((Boolean) -> Unit)? = null

    // ─────────────────────────────────────────────────────────────────
    // Point d'entrée principal — appelé par WearBleService
    // ─────────────────────────────────────────────────────────────────

    /**
     * Traite un paquet de notification reçu de la pompe.
     * Reproduit exactement la logique de NotificationPacket.handleNotification()
     */
    fun handleNotification(packet: ByteArray) {
        if (packet.isEmpty()) return
        val pump = medtrumPump ?: return

        // Byte 0 : état de la pompe
        val state = MedtrumPumpState.fromByte(packet[NOTIF_STATE_START])
        if (state != pump.pumpState) {
            pump.pumpState = state
        }

        // Bytes 1+ : données masquées (si présentes)
        if (packet.size > NOTIF_STATE_END + SIZE_FIELD_MASK) {
            handleMaskedMessage(packet.copyOfRange(NOTIF_STATE_END, packet.size))
        }

        _packetCount.value++
    }

    /**
     * Traite un message avec FieldMask.
     * Peut aussi être appelé par SynchronizePacket (réponse de synchronisation).
     *
     * @param data  Bytes à partir du FieldMask (sans l'octet d'état)
     * @return      true si le message est valide et traité
     */
    fun handleMaskedMessage(data: ByteArray): Boolean {
        if (data.size < SIZE_FIELD_MASK) return false

        val fieldMask = data.copyOfRange(0, 2).toInt()
        val expectedLength = calculateExpectedLength(fieldMask)

        if (data.size < expectedLength) return false
        if (!validateData(fieldMask, data)) return false

        // Dispatch de chaque champ présent dans le masque
        var offset = SIZE_FIELD_MASK
        for ((mask, _) in sizeMap) {
            if (fieldMask and mask != 0) {
                offset = when (mask) {
                    MASK_SUSPEND                -> handleSuspend(data, offset)
                    MASK_NORMAL_BOLUS           -> handleNormalBolus(data, offset)
                    MASK_EXTENDED_BOLUS         -> handleExtendedBolus(data, offset)
                    MASK_BASAL                  -> handleBasal(data, offset)
                    MASK_SETUP                  -> handleSetup(data, offset)
                    MASK_RESERVOIR              -> handleReservoir(data, offset)
                    MASK_START_TIME             -> handleStartTime(data, offset)
                    MASK_BATTERY                -> handleBattery(data, offset)
                    MASK_STORAGE                -> handleStorage(data, offset)
                    MASK_ALARM                  -> handleAlarm(data, offset)
                    MASK_AGE                    -> handleAge(data, offset)
                    MASK_MAGNETO_PLACE          -> offset + SIZE_MAGNETO_PLACE
                    MASK_UNUSED_CGM             -> offset + SIZE_UNUSED_CGM
                    MASK_UNUSED_COMMAND_CONFIRM -> offset + SIZE_UNUSED_COMMAND_CONFIRM
                    MASK_UNUSED_AUTO_STATUS     -> offset + SIZE_UNUSED_AUTO_STATUS
                    MASK_UNUSED_LEGACY          -> offset + SIZE_UNUSED_LEGACY
                    else                        -> offset
                }
            }
        }
        return true
    }

    /** Notifie le dispatcher du résultat d'une écriture BLE */
    fun onWriteResult(success: Boolean) {
        writeResultCallback?.invoke(success)
        writeResultCallback = null
    }

    // ─────────────────────────────────────────────────────────────────
    // Handlers de champs (port direct de NotificationPacket.kt)
    // ─────────────────────────────────────────────────────────────────

    /** MASK_SUSPEND (0x01) — 4 bytes : timestamp de suspension */
    private fun handleSuspend(data: ByteArray, offset: Int): Int {
        val pump = medtrumPump ?: return offset + SIZE_SUSPEND
        pump.suspendTime = timeUtil?.convertPumpTimeToSystemTimeMillis(
            data.copyOfRange(offset, offset + 4).toLong()
        ) ?: 0L
        return offset + SIZE_SUSPEND
    }

    /** MASK_NORMAL_BOLUS (0x02) — 3 bytes : type/état bolus + quantité délivrée */
    private fun handleNormalBolus(data: ByteArray, offset: Int): Int {
        val pump = medtrumPump ?: return offset + SIZE_NORMAL_BOLUS

        val bolusData      = data.copyOfRange(offset, offset + 1).toInt()
        val bolusType      = bolusData and 0x7F
        val bolusCompleted = ((bolusData shr 7) and 0x01) != 0
        val bolusDelivered = data.copyOfRange(offset + 1, offset + 3).toInt() * 0.05

        pump.handleBolusStatusUpdate(bolusType, bolusCompleted, bolusDelivered)
        return offset + SIZE_NORMAL_BOLUS
    }

    /** MASK_EXTENDED_BOLUS (0x04) — 3 bytes : non supporté, skip */
    private fun handleExtendedBolus(data: ByteArray, offset: Int): Int {
        // Bolus étendu non supporté par AAPS/Medtrum — on skip
        return offset + SIZE_EXTENDED_BOLUS
    }

    /**
     * MASK_BASAL (0x08) — 12 bytes :
     * [0]    BasalType (1 byte)
     * [1-2]  sequence number (2 bytes)
     * [3-4]  patchId (2 bytes)
     * [5-8]  startTime pompe (4 bytes)
     * [9-11] rate (12 bits) + delivered (12 bits) encodés sur 3 bytes
     */
    private fun handleBasal(data: ByteArray, offset: Int): Int {
        val pump = medtrumPump ?: return offset + SIZE_BASAL

        val basalType      = enumValues<BasalType>()[data.copyOfRange(offset, offset + 1).toInt()]
        val basalSequence  = data.copyOfRange(offset + 1, offset + 3).toInt()
        val basalPatchId   = data.copyOfRange(offset + 3, offset + 5).toLong()
        val basalStartTime = timeUtil?.convertPumpTimeToSystemTimeMillis(
            data.copyOfRange(offset + 5, offset + 9).toLong()
        ) ?: 0L

        // Rate et delivery sont encodés ensemble sur 3 bytes
        // bits [0-11]  = rate  (× 0.05 U/h)
        // bits [12-23] = total delivered (× 0.05 U)
        val basalRateAndDelivery = data.copyOfRange(offset + 9, offset + 12).toInt()
        val basalRate            = (basalRateAndDelivery and 0xFFF) * 0.05

        // Ne mettre à jour que si le débit ou l'heure de départ a changé
        // (évite le spam de mises à jour identiques)
        if (pump.lastBasalRate != basalRate || pump.lastBasalStartTime != basalStartTime) {
            pump.handleBasalStatusUpdate(basalType, basalRate, basalSequence, basalPatchId, basalStartTime)
        }
        return offset + SIZE_BASAL
    }

    /** MASK_SETUP (0x10) — 1 byte : progression du priming (0–100) */
    private fun handleSetup(data: ByteArray, offset: Int): Int {
        val pump = medtrumPump ?: return offset + SIZE_SETUP
        pump.primeProgress = data.copyOfRange(offset, offset + 1).toInt()
        return offset + SIZE_SETUP
    }

    /** MASK_RESERVOIR (0x20) — 2 bytes : niveau réservoir en unités × 0.05 */
    private fun handleReservoir(data: ByteArray, offset: Int): Int {
        val pump = medtrumPump ?: return offset + SIZE_RESERVOIR
        pump.reservoir = data.copyOfRange(offset, offset + 2).toInt() * 0.05
        return offset + SIZE_RESERVOIR
    }

    /** MASK_START_TIME (0x40) — 4 bytes : timestamp de démarrage du patch */
    private fun handleStartTime(data: ByteArray, offset: Int): Int {
        val pump = medtrumPump ?: return offset + SIZE_START_TIME
        newPatchStartTime = timeUtil?.convertPumpTimeToSystemTimeMillis(
            data.copyOfRange(offset, offset + 4).toLong()
        ) ?: 0L
        if (pump.patchStartTime != newPatchStartTime) {
            pump.patchStartTime = newPatchStartTime
        }
        return offset + SIZE_START_TIME
    }

    /**
     * MASK_BATTERY (0x80) — 3 bytes : tensions batterie A et B
     * Encodées sur 24 bits : bits[0-11] = voltageA, bits[12-23] = voltageB
     * Seuil critique : voltageB < 2.64V
     */
    private fun handleBattery(data: ByteArray, offset: Int): Int {
        val pump = medtrumPump ?: return offset + SIZE_BATTERY
        val parameter = data.copyOfRange(offset, offset + 3).toInt()
        pump.batteryVoltage_A = (parameter and 0xFFF) / 512.0
        pump.batteryVoltage_B = (parameter shr 12) / 512.0
        return offset + SIZE_BATTERY
    }

    /**
     * MASK_STORAGE (0x100) — 4 bytes :
     * [0-1] sequence number
     * [2-3] patchId
     * Détecte les nouveaux patches activés sans ACK (cas de coupure réseau)
     */
    private fun handleStorage(data: ByteArray, offset: Int): Int {
        val pump = medtrumPump ?: return offset + SIZE_STORAGE
        val sequence = data.copyOfRange(offset, offset + 2).toInt()
        if (sequence > pump.currentSequenceNumber) {
            pump.currentSequenceNumber = sequence
        }
        val patchId = data.copyOfRange(offset + 2, offset + 4).toLong()
        if (patchId != pump.patchId && newPatchStartTime != 0L) {
            // Fallback : le patch a été activé mais l'ACK n'a pas été reçu
            // On enregistre quand même le nouveau patch
            pump.handleNewPatch(patchId, sequence, newPatchStartTime)
        }
        return offset + SIZE_STORAGE
    }

    /**
     * MASK_ALARM (0x200) — 4 bytes :
     * [0-1] alarmFlags  (BitMask des alarmes actives)
     * [2-3] alarmParameter
     *
     * Les 4 premiers bits correspondent aux AlarmState entries[0..3]
     */
    private fun handleAlarm(data: ByteArray, offset: Int): Int {
        val pump       = medtrumPump ?: return offset + SIZE_ALARM
        val alarmFlags = data.copyOfRange(offset, offset + 2).toInt()

        if (alarmFlags == 0 && pump.activeAlarms.isNotEmpty()) {
            // Aucune alarme active → vider la liste
            pump.clearAlarmState()
            _lastAlarm.value = AlarmState.NONE
        } else if (alarmFlags != 0) {
            // Vérifier chacun des 4 premiers bits d'alarme
            for (i in 0..3) {
                val alarmState = AlarmState.entries[i]
                if ((alarmFlags shr i) and 1 != 0) {
                    if (!pump.activeAlarms.contains(alarmState)) {
                        pump.addAlarm(alarmState)
                        pump.pumpWarning = alarmState
                        _lastAlarm.value = alarmState
                    }
                } else if (pump.activeAlarms.contains(alarmState)) {
                    pump.removeAlarm(alarmState)
                }
            }
        }
        return offset + SIZE_ALARM
    }

    /** MASK_AGE (0x400) — 4 bytes : âge du patch en secondes */
    private fun handleAge(data: ByteArray, offset: Int): Int {
        val pump = medtrumPump ?: return offset + SIZE_AGE
        pump.patchAge = data.copyOfRange(offset, offset + 4).toLong()
        return offset + SIZE_AGE
    }

    // ─────────────────────────────────────────────────────────────────
    // Validation et calculs d'offset
    // ─────────────────────────────────────────────────────────────────

    /** Calcule la longueur minimale attendue d'un message selon son fieldMask */
    private fun calculateExpectedLength(fieldMask: Int): Int {
        var length = SIZE_FIELD_MASK
        for ((mask, size) in sizeMap) {
            if (fieldMask and mask != 0) length += size
        }
        return length
    }

    /**
     * Calcule l'offset d'un champ cible à partir du fieldMask.
     * Utilisé pour la validation (accès direct à un champ sans parcourir tout le message).
     */
    private fun calculateOffset(fieldMask: Int, targetMask: Int): Int {
        var offset = SIZE_FIELD_MASK
        for ((mask, size) in sizeMap) {
            if (mask == targetMask) return offset
            if (fieldMask and mask != 0) offset += size
        }
        throw IllegalArgumentException("targetMask $targetMask absent du fieldMask $fieldMask")
    }

    /**
     * Valide les données critiques d'un message avant parsing complet.
     * Port direct de checkDataValidity() de NotificationPacket.
     */
    private fun validateData(fieldMask: Int, data: ByteArray): Boolean {
        val pump = medtrumPump ?: return false

        // Valider le bolus normal
        if (fieldMask and MASK_NORMAL_BOLUS != 0) {
            val off = calculateOffset(fieldMask, MASK_NORMAL_BOLUS)
            val bolusDelivered = data.copyOfRange(off + 1, off + 3).toInt() * 0.05
            if (bolusDelivered < 0 || bolusDelivered > 50) return false
        }

        // Valider le basal
        if (fieldMask and MASK_BASAL != 0) {
            val off         = calculateOffset(fieldMask, MASK_BASAL)
            val basalPatchId = data.copyOfRange(off + 3, off + 5).toLong()
            val rateAndDel   = data.copyOfRange(off + 9, off + 12).toInt()
            val basalRate    = (rateAndDel and 0xFFF) * 0.05
            if (pump.patchId != 0L && basalPatchId != pump.patchId) return false
            if (basalRate < 0 || basalRate > 40) return false
        }

        // Valider le réservoir
        if (fieldMask and MASK_RESERVOIR != 0) {
            val off           = calculateOffset(fieldMask, MASK_RESERVOIR)
            val reservoirValue = data.copyOfRange(off, off + SIZE_RESERVOIR).toInt() * 0.05
            if (reservoirValue < 0 || reservoirValue > 400) return false
        }

        // Valider le patchId dans storage
        if (fieldMask and MASK_STORAGE != 0) {
            val off     = calculateOffset(fieldMask, MASK_STORAGE)
            val patchId = data.copyOfRange(off + 2, off + 4).toLong()
            if (pump.patchId != 0L && patchId != pump.patchId
                && newPatchStartTime == 0L) return false
        }

        return true
    }
}
