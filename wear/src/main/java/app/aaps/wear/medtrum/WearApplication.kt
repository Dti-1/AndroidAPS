package app.aaps.wear.medtrum

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.pump.medtrum.MedtrumPump
import app.aaps.wear.medtrum.util.WearPreferences
import app.aaps.wear.medtrum.util.WearResourceHelper
import app.aaps.wear.medtrum.util.WearTemporaryBasalStorage
import app.aaps.wear.medtrum.util.WearTimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class WearApplication : Application() {

    companion object {
        lateinit var instance: WearApplication
            private set
        private const val PREFS_NAME  = "aaps_wear_prefs"
        const val KEY_PUMP_SERIAL     = "pump_serial"
        const val KEY_ONBOARDING_DONE = "onboarding_done"
    }

    lateinit var sharedPrefs: SharedPreferences              private set
    lateinit var wearPreferences: WearPreferences            private set
    lateinit var resourceHelper: WearResourceHelper          private set
    lateinit var timeUtil: WearTimeUtil                      private set
    lateinit var pumpSync: PumpSyncWear                      private set
    lateinit var temporaryBasalStorage: WearTemporaryBasalStorage private set
    lateinit var medtrumPump: MedtrumPump                    private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        instance = this
        initComponents()
        wireComponents()
        startBleServiceIfConfigured()
        observeAlarms()
    }

    private fun initComponents() {
        sharedPrefs           = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        wearPreferences       = WearPreferences(this)
        resourceHelper        = WearResourceHelper(this)
        timeUtil              = WearTimeUtil()
        pumpSync              = PumpSyncWear(this)
        temporaryBasalStorage = WearTemporaryBasalStorage()
        medtrumPump = MedtrumPump(
            aapsLogger            = WearAAPSLogger(),
            rh                    = resourceHelper,
            preferences           = wearPreferences,
            dateUtil              = WearDateUtilSimple(),
            pumpSync              = pumpSync,
            temporaryBasalStorage = temporaryBasalStorage
        )
        medtrumPump.loadVarsFromSP()
    }

    private fun wireComponents() {
        MedtrumPacketDispatcher.medtrumPump = medtrumPump
        MedtrumPacketDispatcher.timeUtil    = timeUtil
    }

    private fun startBleServiceIfConfigured() {
        val serial = sharedPrefs.getString(KEY_PUMP_SERIAL, "") ?: ""
        if (serial.isNotEmpty()) WearBleService.start(this, serial)
    }

    private fun observeAlarms() {
        MedtrumPacketDispatcher.lastAlarm
            .onEach { alarm ->
                if (alarm != app.aaps.pump.medtrum.comm.enums.AlarmState.NONE) {
                    // TODO: vibration alarme
                }
            }
            .launchIn(appScope)
    }

    fun configurePumpSerial(serial: String) {
        sharedPrefs.edit().putString(KEY_PUMP_SERIAL, serial).apply()
        WearBleService.start(this, serial)
    }
}

class WearAAPSLogger : AAPSLogger {
    override fun debug(message: String) {
        Log.d("AAPS-Wear", message)
    }
    override fun debug(enable: Boolean, tag: LTag, message: String) {
        if (enable) Log.d(tag.tag, message)
    }
    override fun debug(tag: LTag, message: String) {
        Log.d(tag.tag, message)
    }
    override fun debug(tag: LTag, accessor: () -> String) {
        Log.d(tag.tag, accessor())
    }
    override fun debug(tag: LTag, format: String, vararg arguments: Any?) {
        Log.d(tag.tag, format.format(*arguments))
    }
    override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {
        Log.d(tag.tag, "[$className.$methodName:$lineNumber] $message")
    }
    override fun info(tag: LTag, message: String) {
        Log.i(tag.tag, message)
    }
    override fun info(tag: LTag, format: String, vararg arguments: Any?) {
        Log.i(tag.tag, format.format(*arguments))
    }
    override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {
        Log.i(tag.tag, "[$className.$methodName:$lineNumber] $message")
    }
    override fun warn(tag: LTag, message: String) {
        Log.w(tag.tag, message)
    }
    override fun warn(tag: LTag, format: String, vararg arguments: Any?) {
        Log.w(tag.tag, format.format(*arguments))
    }
    override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {
        Log.w(tag.tag, "[$className.$methodName:$lineNumber] $message")
    }
    override fun error(tag: LTag, message: String) {
        Log.e(tag.tag, message)
    }
    override fun error(tag: LTag, message: String, throwable: Throwable) {
        Log.e(tag.tag, message, throwable)
    }
    override fun error(tag: LTag, format: String, vararg arguments: Any?) {
        Log.e(tag.tag, format.format(*arguments))
    }
    override fun error(message: String) {
        Log.e("AAPS-Wear", message)
    }
    override fun error(message: String, throwable: Throwable) {
        Log.e("AAPS-Wear", message, throwable)
    }
    override fun error(format: String, vararg arguments: Any?) {
        Log.e("AAPS-Wear", format.format(*arguments))
    }
    override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) {
        Log.e(tag.tag, "[$className.$methodName:$lineNumber] $message")
    }
}

class WearDateUtilSimple : app.aaps.core.interfaces.utils.DateUtil {
    override fun now(): Long = System.currentTimeMillis()
    override fun nowWithoutMilliseconds(): Long = System.currentTimeMillis() / 1000 * 1000
    override fun dateAndTimeString(mills: Long): String =
        java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(mills))
    override fun fromISODateString(isoDateString: String): Long = 0L
    override fun toISOString(date: Long): String = ""
    override fun toISOAsUTC(timestamp: Long): String = ""
    override fun toISONoZone(timestamp: Long): String = ""
    override fun secondsOfTheDayToMillisecondsOfHoursAndMinutes(seconds: Int): Long = seconds * 1000L
    override fun secondsOfTheDayToMilliseconds(seconds: Int): Long = seconds * 1000L
    override fun toSeconds(hhColonMm: String): Int = 0
    override fun dateString(mills: Long): String = ""
    override fun dateStringRelative(mills: Long, rh: ResourceHelper): String = ""
    override fun dateStringShort(mills: Long): String = ""
    override fun timeString(): String = ""
    override fun timeString(mills: Long): String = ""
    override fun secondString(): String = ""
    override fun secondString(mills: Long): String = ""
    override fun minuteString(): String = ""
    override fun minuteString(mills: Long): String = ""
    override fun hourString(): String = ""
    override fun hourString(mills: Long): String = ""
    override fun amPm(): String = ""
    override fun amPm(mills: Long): String = ""
    override fun dayNameString(format: String): String = ""
    override fun dayNameString(mills: Long, format: String): String = ""
    override fun dayString(mills: Long): String = ""
    override fun monthString(format: String): String = ""
    override fun monthString(mills: Long, format: String): String = ""
    override fun weekString(): String = ""
    override fun weekString(mills: Long): String = ""
    override fun timeStringWithSeconds(mills: Long): String = ""
    override fun dateAndTimeRangeString(start: Long, end: Long): String = ""
    override fun timeRangeString(start: Long, end: Long): String = ""
    override fun dateAndTimeStringNullable(mills: Long?): String? = null
    override fun dateAndTimeAndSecondsString(mills: Long): String = ""
    override fun minAgo(rh: ResourceHelper, time: Long?): String = ""
    override fun minOrSecAgo(rh: ResourceHelper, time: Long?): String = ""
    override fun minAgoShort(time: Long?): String = ""
    override fun minAgoLong(rh: ResourceHelper, time: Long?): String = ""
    override fun hourAgo(time: Long, rh: ResourceHelper): String = ""
    override fun dayAgo(time: Long, rh: ResourceHelper, round: Boolean): String = ""
    override fun beginOfDay(mills: Long): Long = 0L
    override fun timeStringFromSeconds(seconds: Int): String = ""
    override fun timeFrameString(timeInMillis: Long, rh: ResourceHelper): String = ""
    override fun sinceString(timestamp: Long, rh: ResourceHelper): String = ""
    override fun untilString(timestamp: Long, rh: ResourceHelper): String = ""
    override fun isOlderThan(date: Long, minutes: Long): Boolean = System.currentTimeMillis() - date > minutes * 60000
    override fun getTimeZoneOffsetMs(): Long = 0L
    override fun getTimeZoneOffsetMsWithDST(): Long = 0L
    override fun getTimeZoneOffsetMinutes(timestamp: Long): Int = 0
    override fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean = false
    override fun isAfterNoon(): Boolean = false
    override fun isSameDayGroup(timestamp1: Long, timestamp2: Long): Boolean = false
    override fun computeDiff(date1: Long, date2: Long): Map<java.util.concurrent.TimeUnit, Long> = emptyMap()
    override fun age(milliseconds: Long, useShortText: Boolean, rh: ResourceHelper): String = ""
    override fun timeAgoFullString(milliseconds: Long, rh: ResourceHelper): String = ""
    override fun niceTimeScalar(time: Long, rh: ResourceHelper): String = ""
    override fun qs(x: Double, numDigits: Int): String = "%.${if (numDigits < 0) 2 else numDigits}f".format(x)
    override fun formatHHMM(timeAsSeconds: Int): String = ""
    override fun timeZoneByOffset(offsetInMilliseconds: Long): String = "UTC"
    override fun timeStampToUtcDateMillis(timestamp: Long): Long = 0L
    override fun getTimestampWithCurrentTimeOfDay(timestamp: Long): Long = 0L
    override fun mergeUtcDateToTimestamp(timestamp: Long, dateUtcMillis: Long): Long = 0L
    override fun mergeHourMinuteToTimestamp(timestamp: Long, hour: Int, minute: Int, randomSecond: Boolean): Long = 0L
}
