package app.aaps.wear.medtrum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * BootReceiver
 *
 * Redémarre automatiquement WearBleService après :
 * - Un reboot de la montre
 * - Une mise à jour de l'application
 *
 * Sans ce receiver, l'utilisateur devrait ouvrir l'app manuellement
 * après chaque redémarrage pour reconnecter la pompe.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Récupérer le numéro de série sauvegardé
                val prefs: SharedPreferences = context.getSharedPreferences(
                    "aaps_wear_prefs", Context.MODE_PRIVATE
                )
                val pumpSerial = prefs.getString("pump_serial", "") ?: ""

                if (pumpSerial.isNotEmpty()) {
                    WearBleService.start(context, pumpSerial)
                }
            }
        }
    }
}
