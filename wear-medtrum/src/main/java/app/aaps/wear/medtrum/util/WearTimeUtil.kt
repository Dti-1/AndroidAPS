package app.aaps.wear.medtrum.util

/**
 * WearTimeUtil
 *
 * Port de MedtrumTimeUtil.kt d'AAPS pour Wear OS.
 *
 * La pompe Medtrum utilise son propre référentiel temporel :
 * les timestamps sont exprimés en secondes depuis une epoch
 * propre à la pompe (basée sur la date d'activation du patch).
 *
 * Cette classe convertit les timestamps pompe en milliseconds
 * Unix standard utilisés partout ailleurs dans Android/AAPS.
 *
 * EPOCH MEDTRUM : 2000-01-01 00:00:00 UTC
 * (différente de l'epoch Unix 1970-01-01)
 */
class WearTimeUtil {

    companion object {
        // Offset entre l'epoch Medtrum (2000-01-01) et l'epoch Unix (1970-01-01)
        // = 30 années en secondes = 946 684 800 secondes
        private const val MEDTRUM_EPOCH_OFFSET_SECONDS = 946_684_800L
    }

    /**
     * Convertit un timestamp pompe (secondes depuis 2000-01-01)
     * en millisecondes Unix (depuis 1970-01-01).
     *
     * @param pumpTime  Timestamp en secondes, référentiel pompe Medtrum
     * @return          Timestamp en millisecondes, référentiel Unix
     */
    fun convertPumpTimeToSystemTimeMillis(pumpTime: Long): Long {
        return (pumpTime + MEDTRUM_EPOCH_OFFSET_SECONDS) * 1000L
    }

    /**
     * Convertit un timestamp Unix en millisecondes
     * vers le référentiel pompe Medtrum (secondes depuis 2000-01-01).
     *
     * @param systemTimeMillis  Timestamp Unix en millisecondes
     * @return                  Timestamp pompe en secondes
     */
    fun convertSystemTimeToPumpTime(systemTimeMillis: Long): Long {
        return (systemTimeMillis / 1000L) - MEDTRUM_EPOCH_OFFSET_SECONDS
    }

    /**
     * Retourne le temps courant en format pompe Medtrum.
     */
    fun currentPumpTime(): Long = convertSystemTimeToPumpTime(System.currentTimeMillis())
}
