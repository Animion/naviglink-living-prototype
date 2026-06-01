package cz.naviglink.driver.work

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import cz.naviglink.driver.MainActivity
import cz.naviglink.driver.NaviglinkApp
import cz.naviglink.driver.R
import cz.naviglink.driver.data.SignedSubject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Zobrazuje heads-up notifikaci pro nový alert.
 *
 * Stable ID notifikace = hash subject_id, takže opakovaný worker run pro stejný
 * subjekt notifikaci jen aktualizuje (ne vyrobí duplikát).
 */
object AlertNotifier {

    private const val DT_PATTERN = "EEEE d. MMMM, HH:mm"
    private val PRAGUE: ZoneId = ZoneId.of("Europe/Prague")

    /**
     * @return true pokud notifikace byla zobrazena (nebo se *pokusila*); false
     *         pokud uživatel notifikační oprávnění odepřel a nic se neudálo.
     */
    fun show(context: Context, subject: SignedSubject): Boolean {
        // Android 13+: POST_NOTIFICATIONS runtime permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        val street = subject.payload["ulice"]?.jsonPrimitive?.contentOrNull
            ?: subject.payload["street"]?.jsonPrimitive?.contentOrNull
            ?: "neznámá ulice"
        val typ = subject.payload["typ"]?.jsonPrimitive?.contentOrNull
            ?: subject.kind

        val from = runCatching { Instant.parse(subject.validFrom) }.getOrNull()
        val to = subject.validTo?.let { runCatching { Instant.parse(it) }.getOrNull() }

        val whenText = formatTimeRange(from, to)

        val title = "Blokové čištění: $street"
        val body = if (whenText.isNotEmpty()) "$typ · $whenText" else typ

        // Deep link na detail subjektu v MainActivity.
        val openIntent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("naviglink://alert/${subject.id}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(
            context,
            subject.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, NaviglinkApp.CHANNEL_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()

        NotificationManagerCompat.from(context).notify(subject.id.hashCode(), notif)
        return true
    }

    private fun formatTimeRange(from: Instant?, to: Instant?): String {
        if (from == null) return ""
        val fmt = DateTimeFormatter.ofPattern(DT_PATTERN, Locale.forLanguageTag("cs"))
        val fromText = from.atZone(PRAGUE).format(fmt)
        val toText = to?.atZone(PRAGUE)?.format(DateTimeFormatter.ofPattern("HH:mm"))
        return if (toText != null) "$fromText–$toText" else fromText
    }
}
