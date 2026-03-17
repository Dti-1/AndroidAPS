package app.aaps.wear.medtrum

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val serial = context.getSharedPreferences("aaps_wear_prefs", Context.MODE_PRIVATE)
                    .getString("pump_serial", "") ?: ""
                if (serial.isNotEmpty()) WearBleService.start(context, serial)
            }
        }
    }
}
