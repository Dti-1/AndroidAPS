package app.aaps.wear.medtrum.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.pump.medtrum.comm.enums.AlarmState
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.wear.medtrum.BleConnectionState
import app.aaps.wear.medtrum.MedtrumPacketDispatcher
import app.aaps.wear.medtrum.WearApplication
import app.aaps.wear.medtrum.WearBleService
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

// ─────────────────────────────────────────────────────────────────────────
// ViewModel
// ─────────────────────────────────────────────────────────────────────────

/**
 * MainWearViewModel
 *
 * Expose les StateFlows de MedtrumPump et WearBleService
 * sous forme collectible par Compose for Wear OS.
 *
 * Pas de logique ici — tout vient directement des StateFlows
 * déjà définis dans MedtrumPump (port AAPS).
 */
class MainWearViewModel : ViewModel() {

    private val pump: MedtrumPump get() = WearApplication.instance.medtrumPump

    // ── Connexion ─────────────────────────────────────────────────────
    val connectionState: StateFlow<BleConnectionState>  = WearBleService.connectionState

    // ── État pompe ────────────────────────────────────────────────────
    val pumpState: StateFlow<MedtrumPumpState>          = pump.pumpStateFlow
    val reservoir: StateFlow<Double>                    = pump.reservoirFlow
    val batteryVoltage: StateFlow<Double>               = pump.batteryVoltage_BFlow

    // ── Basal ─────────────────────────────────────────────────────────
    val lastBasalType                                   = pump.lastBasalTypeFlow
    val lastBasalRate: StateFlow<Double>                = pump.lastBasalRateFlow

    // ── Bolus en cours ────────────────────────────────────────────────
    val bolusDelivered: StateFlow<Double>               = pump.bolusAmountDeliveredFlow

    // ── Alarmes ───────────────────────────────────────────────────────
    val lastAlarm: StateFlow<AlarmState>                = MedtrumPacketDispatcher.lastAlarm

    // ── Prime (activation patch) ──────────────────────────────────────
    val primeProgress: StateFlow<Int>                   = pump.primeProgressFlow
    val pumpWarning: StateFlow<AlarmState>              = pump.pumpWarningFlow

    // ── Helpers ───────────────────────────────────────────────────────

    /** Basal de base (depuis le profil actif) */
    val baseBasalRate: Double get() = pump.baseBasalRate

    /** TBR en cours ? */
    val tempBasalInProgress: Boolean get() = pump.tempBasalInProgress

    /** Pourcentage TBR vs basal de base */
    val tbrPercent: Int get() {
        if (!tempBasalInProgress || baseBasalRate == 0.0) return 100
        return ((pump.tempBasalAbsoluteRate / baseBasalRate) * 100).roundToInt()
    }

    /** Tension batterie faible ? */
    val isBatteryLow: Boolean get() = pump.batteryVoltage_B < 2.64

    /** Réservoir faible ? (<20U) */
    val isReservoirLow: Boolean get() = pump.reservoir < 20.0
}

// ─────────────────────────────────────────────────────────────────────────
// Écran principal
// ─────────────────────────────────────────────────────────────────────────

/**
 * MainWearScreen
 *
 * Écran principal de l'app Wear OS AAPS Medtrum.
 * Affiche en temps réel :
 *   - Glycémie (depuis Juggluco via complication)
 *   - Statut de la boucle
 *   - IOB / COB
 *   - Débit basal actuel (standard ou TBR)
 *   - Réservoir + batterie
 *   - Alarmes actives
 *   - Actions rapides : bolus, basal temporaire
 */
@Composable
fun MainWearScreen(
    onBolusClick: () -> Unit = {},
    onTempBasalClick: () -> Unit = {},
    vm: MainWearViewModel = viewModel()
) {
    // Collecter tous les states
    val connectionState by vm.connectionState.collectAsState()
    val pumpState       by vm.pumpState.collectAsState()
    val reservoir       by vm.reservoir.collectAsState()
    val battery         by vm.batteryVoltage.collectAsState()
    val basalRate       by vm.lastBasalRate.collectAsState()
    val bolus           by vm.bolusDelivered.collectAsState()
    val alarm           by vm.lastAlarm.collectAsState()
    val primeProgress   by vm.primeProgress.collectAsState()
    val pumpWarning     by vm.pumpWarning.collectAsState()

    Scaffold(
        timeText = { TimeText() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {

            // ── Indicateur connexion ──────────────────────────────────
            ConnectionBadge(connectionState)

            Spacer(modifier = Modifier.height(2.dp))

            // ── Glycémie (complication Juggluco) ──────────────────────
            // Note : la valeur BG vient de Juggluco via le système de
            // complications Wear OS. On affiche un placeholder ici —
            // brancher sur la complication BG de Juggluco dans le manifest.
            GlucoseDisplay()

            Spacer(modifier = Modifier.height(2.dp))

            // ── Rangée centrale : IOB | Basal | Réservoir ─────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // IOB (Insulin On Board) — calculé par OpenAPS
                // Pour l'instant affiche le dernier bolus en cours
                StatusTile(
                    label = "IOB",
                    value = if (bolus > 0) "%.2fU".format(bolus) else "—",
                    color = if (bolus > 0) ColorScheme.insulinActive else ColorScheme.muted
                )

                // Basal actuel
                BasalTile(
                    rate      = basalRate,
                    baseRate  = vm.baseBasalRate,
                    isTbr     = vm.tempBasalInProgress,
                    tbrPct    = vm.tbrPercent
                )

                // Réservoir
                StatusTile(
                    label = "Res",
                    value = "%.0fU".format(reservoir),
                    color = when {
                        reservoir < 10 -> ColorScheme.danger
                        reservoir < 20 -> ColorScheme.warning
                        else           -> ColorScheme.normal
                    }
                )
            }

            Spacer(modifier = Modifier.height(2.dp))

            // ── Batterie + État pompe ─────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusTile(
                    label = "Bat",
                    value = "%.2fV".format(battery),
                    color = if (vm.isBatteryLow) ColorScheme.danger else ColorScheme.normal
                )
                PumpStateBadge(pumpState)
            }

            // ── Alarme active ─────────────────────────────────────────
            if (alarm != AlarmState.NONE) {
                AlarmBanner(alarm)
            }

            // ── Priming en cours ──────────────────────────────────────
            if (primeProgress in 1..99) {
                PrimingProgress(primeProgress)
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Actions rapides ───────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Bolus rapide
                ActionButton(
                    label   = "Bolus",
                    color   = ColorScheme.insulinActive,
                    enabled = connectionState == BleConnectionState.READY
                               && pumpState == MedtrumPumpState.ACTIVE,
                    onClick = onBolusClick
                )
                // Basal temporaire
                ActionButton(
                    label   = "TBR",
                    color   = ColorScheme.basalColor,
                    enabled = connectionState == BleConnectionState.READY
                               && pumpState == MedtrumPumpState.ACTIVE,
                    onClick = onTempBasalClick
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Composants UI réutilisables
// ─────────────────────────────────────────────────────────────────────────

/**
 * Badge de connexion BLE en haut de l'écran.
 * Vert = connecté, orange = scan/connexion, rouge = erreur.
 */
@Composable
private fun ConnectionBadge(state: BleConnectionState) {
    val (label, color) = when (state) {
        BleConnectionState.READY        -> "Connecté"    to ColorScheme.normal
        BleConnectionState.CONNECTED    -> "Services…"   to ColorScheme.warning
        BleConnectionState.CONNECTING   -> "Connexion…"  to ColorScheme.warning
        BleConnectionState.SCANNING     -> "Scan…"       to ColorScheme.warning
        BleConnectionState.DISCONNECTED -> "Déconnecté"  to ColorScheme.danger
        BleConnectionState.ERROR        -> "Erreur BLE"  to ColorScheme.danger
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text  = label,
            fontSize = 10.sp,
            color = color
        )
    }
}

/**
 * Affichage glycémie — placeholder branché sur complication Juggluco.
 * La valeur réelle vient du ComplicationDataSourceService de Juggluco.
 */
@Composable
private fun GlucoseDisplay() {
    // TODO : collecter la valeur depuis le ComplicationDataSource Juggluco
    // Pour l'instant : affichage statique "—" indiquant l'attente
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text       = "— mg/dL",   // Remplacer par valeur complication Juggluco
            fontSize   = 32.sp,
            fontWeight = FontWeight.Bold,
            color      = ColorScheme.glucoseInRange,  // Vert si en range
            textAlign  = TextAlign.Center
        )
        Text(
            text     = "↗",  // Tendance : → ↗ ↑ ↘ ↓ (depuis Juggluco)
            fontSize = 20.sp,
            color    = ColorScheme.glucoseInRange
        )
    }
}

/** Tuile de statut : label + valeur colorée */
@Composable
private fun StatusTile(label: String, value: String, color: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(60.dp)
    ) {
        Text(
            text     = label,
            fontSize = 9.sp,
            color    = ColorScheme.muted
        )
        Text(
            text       = value,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Medium,
            color      = color,
            textAlign  = TextAlign.Center
        )
    }
}

/** Tuile basal avec indication TBR si actif */
@Composable
private fun BasalTile(rate: Double, baseRate: Double, isTbr: Boolean, tbrPct: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(70.dp)
    ) {
        Text(
            text     = "Basal",
            fontSize = 9.sp,
            color    = ColorScheme.muted
        )
        Text(
            text       = "%.2f U/h".format(rate),
            fontSize   = 13.sp,
            fontWeight = FontWeight.Medium,
            color      = if (isTbr) ColorScheme.tbrColor else ColorScheme.basalColor,
            textAlign  = TextAlign.Center
        )
        if (isTbr) {
            Text(
                text     = "TBR $tbrPct%",
                fontSize = 9.sp,
                color    = ColorScheme.tbrColor
            )
        }
    }
}

/** Badge état interne de la pompe */
@Composable
private fun PumpStateBadge(state: MedtrumPumpState) {
    val (label, color) = when (state) {
        MedtrumPumpState.ACTIVE          -> "Active"    to ColorScheme.normal
        MedtrumPumpState.SUSPENDED       -> "Suspendu"  to ColorScheme.warning
        MedtrumPumpState.PAUSED          -> "Pause"     to ColorScheme.warning
        MedtrumPumpState.LOW_BG_SUSPENDED -> "Suspendu"  to ColorScheme.danger
        MedtrumPumpState.NONE            -> "Inactif"   to ColorScheme.muted
        else                             -> state.name  to ColorScheme.muted
    }
    StatusTile(label = "Pompe", value = label, color = color)
}

/** Bannière d'alarme rouge */
@Composable
private fun AlarmBanner(alarm: AlarmState) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorScheme.danger.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text      = "⚠ ${alarm.name.replace('_', ' ').lowercase()}",
            fontSize  = 10.sp,
            color     = ColorScheme.danger,
            textAlign = TextAlign.Center
        )
    }
}

/** Barre de progression du priming du patch */
@Composable
private fun PrimingProgress(progress: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text     = "Amorçage $progress%",
            fontSize = 10.sp,
            color    = ColorScheme.warning
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(4.dp)
                .background(ColorScheme.muted.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress / 100f)
                    .height(4.dp)
                    .background(ColorScheme.warning)
            )
        }
    }
}

/** Bouton d'action rond (bolus, TBR) */
@Composable
private fun ActionButton(
    label:   String,
    color:   Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick  = onClick,
        enabled  = enabled,
        modifier = Modifier.size(52.dp),
        colors   = ButtonDefaults.buttonColors(
            backgroundColor = if (enabled) color.copy(alpha = 0.85f) else ColorScheme.muted.copy(alpha = 0.3f)
        )
    ) {
        Text(
            text       = label,
            fontSize   = 11.sp,
            fontWeight = FontWeight.Medium,
            color      = if (enabled) Color.White else ColorScheme.muted,
            textAlign  = TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Palette de couleurs — s'adapte au thème Wear OS sombre
// ─────────────────────────────────────────────────────────────────────────
object ColorScheme {
    val normal         = Color(0xFF4CAF50)   // Vert — état normal
    val warning        = Color(0xFFFF9800)   // Orange — attention
    val danger         = Color(0xFFF44336)   // Rouge — alarme
    val muted          = Color(0xFF9E9E9E)   // Gris — inactif
    val insulinActive  = Color(0xFF2196F3)   // Bleu — insuline/bolus
    val basalColor     = Color(0xFF00BCD4)   // Cyan — basal standard
    val tbrColor       = Color(0xFFFF5722)   // Orange vif — TBR actif
    val glucoseInRange = Color(0xFF4CAF50)   // Vert — glycémie en cible
    val glucoseLow     = Color(0xFFF44336)   // Rouge — hypoglycémie
    val glucoseHigh    = Color(0xFFFF9800)   // Orange — hyperglycémie
}
