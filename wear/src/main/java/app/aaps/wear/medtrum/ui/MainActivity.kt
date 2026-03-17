package app.aaps.wear.medtrum.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import app.aaps.wear.medtrum.WearApplication

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val isOnboarded = WearApplication.instance.sharedPrefs
            .getBoolean(WearApplication.KEY_ONBOARDING_DONE, false)
        setContent {
            MaterialTheme { WearNavGraph(if (isOnboarded) "main" else "setup") }
        }
    }
}

@Composable
private fun WearNavGraph(startRoute: String) {
    val nav = rememberSwipeDismissableNavController()
    SwipeDismissableNavHost(navController = nav, startDestination = startRoute) {
        composable("main")  { MainWearScreen(
            onBolusClick     = { nav.navigate("bolus") },
            onTempBasalClick = { nav.navigate("tbr") }
        )}
        composable("bolus") { BolusScreen(
            onConfirm = { nav.popBackStack() },
            onCancel  = { nav.popBackStack() }
        )}
        composable("tbr")   { TbrScreen(
            onConfirm = { _, _ -> nav.popBackStack() },
            onCancel  = { nav.popBackStack() }
        )}
        composable("setup") { SetupScreen(onComplete = { serial ->
            WearApplication.instance.configurePumpSerial(serial)
            WearApplication.instance.sharedPrefs.edit()
                .putBoolean(WearApplication.KEY_ONBOARDING_DONE, true).apply()
            nav.navigate("main") { popUpTo("setup") { inclusive = true } }
        })}
    }
}
