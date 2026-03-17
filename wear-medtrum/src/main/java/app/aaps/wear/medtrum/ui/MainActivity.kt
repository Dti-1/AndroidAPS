package app.aaps.wear.medtrum.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import app.aaps.wear.medtrum.WearApplication

/**
 * MainActivity — Point d'entrée UI de l'app Wear OS.
 *
 * Navigation par SwipeDismissableNavHost (swipe gauche = retour arrière
 * natif Wear OS, compatible bouton latéral de la Galaxy Watch).
 *
 * Routes :
 *   "main"   → MainWearScreen   (écran principal)
 *   "bolus"  → BolusScreen      (saisie bolus)
 *   "tbr"    → TbrScreen        (basal temporaire)
 *   "setup"  → SetupScreen      (premier démarrage / config pompe)
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs       = WearApplication.instance.sharedPrefs
        val isOnboarded = prefs.getBoolean(WearApplication.KEY_ONBOARDING_DONE, false)
        val startRoute  = if (isOnboarded) "main" else "setup"

        setContent {
            WearAppTheme {
                WearNavGraph(startRoute = startRoute)
            }
        }
    }
}

@Composable
private fun WearAppTheme(content: @Composable () -> Unit) {
    MaterialTheme(content = content)
}

@Composable
private fun WearNavGraph(startRoute: String) {
    val navController = rememberSwipeDismissableNavController()

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = startRoute
    ) {
        // ── Écran principal ───────────────────────────────────────────
        composable("main") {
            MainWearScreen(
                onBolusClick     = { navController.navigate("bolus") },
                onTempBasalClick = { navController.navigate("tbr")   }
            )
        }

        // ── Saisie bolus ──────────────────────────────────────────────
        composable("bolus") {
            BolusScreen(
                onConfirm = { amount ->
                    // TODO : envoyer SetBolusPacket via WearBleService
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() }
            )
        }

        // ── Basal temporaire ──────────────────────────────────────────
        composable("tbr") {
            TbrScreen(
                onConfirm = { rate, durationMin ->
                    // TODO : envoyer SetTempBasalPacket via WearBleService
                    navController.popBackStack()
                },
                onCancel = { navController.popBackStack() }
            )
        }

        // ── Configuration initiale ────────────────────────────────────
        composable("setup") {
            SetupScreen(
                onComplete = { serial ->
                    WearApplication.instance.configurePumpSerial(serial)
                    WearApplication.instance.sharedPrefs
                        .edit()
                        .putBoolean(WearApplication.KEY_ONBOARDING_DONE, true)
                        .apply()
                    navController.navigate("main") {
                        popUpTo("setup") { inclusive = true }
                    }
                }
            )
        }
    }
}
