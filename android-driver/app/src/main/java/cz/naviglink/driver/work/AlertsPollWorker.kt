package cz.naviglink.driver.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import cz.naviglink.driver.NaviglinkApp
import java.util.concurrent.TimeUnit

/**
 * Periodicky volá `GET /alerts` a pokud server vrátí nové subjekty pokrývající
 * parkovanou polohu driveru, zobrazí heads-up notifikaci pro každý.
 *
 * Frekvence: 15 min (minimum pro WorkManager `PeriodicWorkRequest`). Systém
 * batchuje běhy, takže reálná latence je 15–30 min. Pro blokové čištění
 * (vyhlášené typicky den předem) je to OK; last-minute změny by chtěly FCM push,
 * což je v plánu na další iteraci.
 *
 * Constraint: `NetworkType.CONNECTED` — pokud telefon nemá net, worker se
 * neudělá a další pokus přijde s dalším okénkem.
 *
 * Dedup: notifikace má stable ID (hash subject_id), takže opakované runs pro
 * stejný subjekt notifikaci jen aktualizují.
 */
class AlertsPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? NaviglinkApp ?: return Result.failure()

        return try {
            val response = app.client.getAlerts()
            Log.i(TAG, "alerts check: ${response.alerts.size} matches (reason=${response.reason})")

            for (subject in response.alerts) {
                AlertNotifier.show(applicationContext, subject)
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "alerts poll failed: ${e.message}", e)
            // Retry — síťová chyba je dočasná, příští okno to nahradí
            Result.retry()
        }
    }

    companion object {
        const val TAG = "AlertsPollWorker"
        const val UNIQUE_NAME = "naviglink_alerts_poll"

        /**
         * Zaplánuj nebo aktualizuj periodicky worker.
         *
         * Volá se ze startu Application — `KEEP` znamená, že pokud worker už
         * běží, nebudeme ho replanovat (zachová cadenci).
         */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AlertsPollWorker>(
                15, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Manual trigger: zařadit jeden OneTimeWorkRequest mimo periodic cycle.
         *
         * Použití: driver klikne "Zkontrolovat upozornění" → worker poběží do
         * pár sekund (záleží na constraints + scheduler). Nepřepíše plánovaný
         * periodic — používá samostatnou unique work `naviglink_alerts_oneshot`.
         */
        fun runOnce(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<AlertsPollWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "naviglink_alerts_oneshot",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
