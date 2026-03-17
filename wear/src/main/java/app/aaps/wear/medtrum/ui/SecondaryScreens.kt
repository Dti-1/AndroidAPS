package app.aaps.wear.medtrum.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

// ─────────────────────────────────────────────────────────────────────────
// BolusScreen
//
// Saisie du bolus en unités d'insuline.
// Incrément : 0.05 U (résolution Medtrum)
// Plage : 0.05 – 25.0 U
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun BolusScreen(
    onConfirm: (Double) -> Unit,
    onCancel:  () -> Unit
) {
    var amount by remember { mutableStateOf(0.5) }  // Valeur initiale : 0.5 U

    val minAmount = 0.05
    val maxAmount = 25.0
    val step      = 0.05

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text     = "Bolus insuline",
                fontSize = 13.sp,
                color    = ColorScheme.muted
            )

            // ── Valeur centrale ───────────────────────────────────────
            Text(
                text       = "%.2f U".format(amount),
                fontSize   = 28.sp,
                fontWeight = FontWeight.Bold,
                color      = ColorScheme.insulinActive,
                textAlign  = TextAlign.Center
            )

            // ── Contrôles +/- ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // Bouton − 0.05
                StepButton(label = "-", color = ColorScheme.muted) {
                    amount = maxOf(minAmount, (amount - step).roundTo2())
                }
                // Bouton + 0.05
                StepButton(label = "+", color = ColorScheme.insulinActive) {
                    amount = minOf(maxAmount, (amount + step).roundTo2())
                }
            }

            // Bouton − 0.5 / + 0.5 (pas rapide)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StepButton(label = "-0.5", color = ColorScheme.muted, small = true) {
                    amount = maxOf(minAmount, (amount - 0.5).roundTo2())
                }
                StepButton(label = "+0.5", color = ColorScheme.insulinActive, small = true) {
                    amount = minOf(maxAmount, (amount + 0.5).roundTo2())
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Confirmation / Annulation ─────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick  = onCancel,
                    modifier = Modifier.size(44.dp),
                    colors   = ButtonDefaults.buttonColors(
                        backgroundColor = ColorScheme.danger.copy(alpha = 0.7f)
                    )
                ) {
                    Text("✕", fontSize = 16.sp, color = Color.White)
                }
                Button(
                    onClick  = { onConfirm(amount) },
                    modifier = Modifier.size(44.dp),
                    colors   = ButtonDefaults.buttonColors(
                        backgroundColor = ColorScheme.normal
                    )
                ) {
                    Text("✓", fontSize = 16.sp, color = Color.White)
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// TbrScreen
//
// Saisie du basal temporaire.
// Paramètres : débit (U/h) + durée (minutes)
// Plages : débit 0–10 U/h, durée 15–480 min
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun TbrScreen(
    onConfirm: (rate: Double, durationMin: Int) -> Unit,
    onCancel:  () -> Unit
) {
    var rate        by remember { mutableStateOf(1.0) }
    var durationMin by remember { mutableStateOf(30) }

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text     = "Basal temporaire",
                fontSize = 12.sp,
                color    = ColorScheme.muted
            )

            // ── Débit ─────────────────────────────────────────────────
            Text(
                text     = "Débit",
                fontSize = 10.sp,
                color    = ColorScheme.muted
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepButton(label = "-", color = ColorScheme.muted) {
                    rate = maxOf(0.0, (rate - 0.05).roundTo2())
                }
                Text(
                    text       = "%.2f U/h".format(rate),
                    fontSize   = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color      = ColorScheme.tbrColor
                )
                StepButton(label = "+", color = ColorScheme.tbrColor) {
                    rate = minOf(10.0, (rate + 0.05).roundTo2())
                }
            }

            // ── Durée ─────────────────────────────────────────────────
            Text(
                text     = "Durée",
                fontSize = 10.sp,
                color    = ColorScheme.muted
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StepButton(label = "-15", color = ColorScheme.muted, small = true) {
                    durationMin = maxOf(15, durationMin - 15)
                }
                Text(
                    text       = formatDuration(durationMin),
                    fontSize   = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color      = ColorScheme.basalColor
                )
                StepButton(label = "+15", color = ColorScheme.basalColor, small = true) {
                    durationMin = minOf(480, durationMin + 15)
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // ── Confirmation ──────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(
                    onClick  = onCancel,
                    modifier = Modifier.size(44.dp),
                    colors   = ButtonDefaults.buttonColors(
                        backgroundColor = ColorScheme.danger.copy(alpha = 0.7f)
                    )
                ) { Text("✕", fontSize = 16.sp, color = Color.White) }

                Button(
                    onClick  = { onConfirm(rate, durationMin) },
                    modifier = Modifier.size(44.dp),
                    colors   = ButtonDefaults.buttonColors(
                        backgroundColor = ColorScheme.normal
                    )
                ) { Text("✓", fontSize = 16.sp, color = Color.White) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// SetupScreen
//
// Premier démarrage : saisie du numéro de série de la pompe Medtrum.
// Le numéro de série est en hexadécimal (ex: "1A2B3C").
// ─────────────────────────────────────────────────────────────────────────

@Composable
fun SetupScreen(onComplete: (serial: String) -> Unit) {
    // Sur Wear OS, la saisie de texte se fait via le clavier vocal ou
    // le RemoteInput. Ici on affiche un écran d'attente avec instruction.
    // L'utilisateur peut aussi transférer le SN depuis l'app téléphone
    // via Wearable DataLayer.

    Scaffold(timeText = { TimeText() }) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text       = "AAPS Wear",
                fontSize   = 16.sp,
                fontWeight = FontWeight.Bold,
                color      = Color.White,
                textAlign  = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text      = "Configurez depuis l'app téléphone\nou dites le N° de série :",
                fontSize  = 11.sp,
                color     = ColorScheme.muted,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Bouton saisie vocale (RemoteInput Wear OS)
            Button(
                onClick  = { /* TODO: lancer RemoteInput pour saisie vocale du SN */ },
                modifier = Modifier.fillMaxWidth(0.7f),
                colors   = ButtonDefaults.buttonColors(
                    backgroundColor = ColorScheme.insulinActive
                )
            ) {
                Text("🎤  Saisir N° série", fontSize = 12.sp, color = Color.White)
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Bouton de démo (à retirer en production)
            Button(
                onClick  = { onComplete("1A2B3C") },
                modifier = Modifier.fillMaxWidth(0.7f),
                colors   = ButtonDefaults.buttonColors(
                    backgroundColor = ColorScheme.muted.copy(alpha = 0.5f)
                )
            ) {
                Text("Démo", fontSize = 12.sp, color = ColorScheme.muted)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Composants partagés
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun StepButton(
    label:   String,
    color:   Color,
    small:   Boolean = false,
    onClick: () -> Unit
) {
    val size = if (small) 36.dp else 40.dp
    Button(
        onClick  = onClick,
        modifier = Modifier.size(size),
        colors   = ButtonDefaults.buttonColors(
            backgroundColor = color.copy(alpha = 0.25f)
        )
    ) {
        Text(
            text      = label,
            fontSize  = if (small) 9.sp else 14.sp,
            color     = color,
            textAlign = TextAlign.Center
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────

/** Arrondit à 2 décimales pour éviter les erreurs flottantes (0.1 + 0.2 = 0.30000004) */
private fun Double.roundTo2(): Double = (this * 100).toLong() / 100.0

/** Formate une durée en minutes : "30 min" ou "1h30" */
private fun formatDuration(minutes: Int): String =
    if (minutes < 60) "$minutes min"
    else {
        val h = minutes / 60
        val m = minutes % 60
        if (m == 0) "${h}h" else "${h}h${m.toString().padStart(2, '0')}"
    }
