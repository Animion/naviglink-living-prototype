package cz.naviglink.driver.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import cz.naviglink.driver.MainActivity
import cz.naviglink.driver.NaviglinkApp
import cz.naviglink.driver.R
import cz.naviglink.driver.data.NaviglinkClient
import cz.naviglink.driver.data.SignedSubject
import cz.naviglink.driver.work.AlertNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit

/**
 * Foreground service udržující SSE spojení s backendem.
 *
 * Driver dostane real-time push, jakmile magistrát vyhlásí subjekt pokrývající
 * jeho aktivní park_snapshot. Bez tohoto service by si driver musel pravidelně
 * stahovat /alerts (WorkManager 15 min interval), což byla limita.
 *
 * Service je foreground (notif "Naviglink — sleduji upozornění"), aby Android
 * neukončil proces přes Doze / standby restrictions. Driver ho může kdykoliv
 * vypnout přes "Konec sledování" tlačítko v aplikaci.
 *
 * Reconnect: pokud spojení spadne (network change, server restart), nový
 * EventSource je vytvořen s exponential backoff (max 30 s).
 */
class EventStreamService : Service() {

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)
    private var eventSource: EventSource? = null
    private var reconnectJob: Job? = null
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)  // SSE = bezlimitní čtení
            .connectTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Připojuji se k serveru…"))
        connectStream()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        eventSource?.cancel()
        reconnectJob?.cancel()
        job.cancel()
    }

    private fun connectStream() {
        val app = applicationContext as? NaviglinkApp ?: return
        val pubHex = app.keystore.publicKeyHex
        val url = "${NaviglinkClient.BACKEND_URL}/events?author=$pubHex"

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "text/event-stream")
            .build()

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.i(TAG, "SSE connected to $url")
                updateNotification("Připojeno · sleduji upozornění")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.i(TAG, "SSE event: $data")
                handleEvent(data)
            }

            override fun onClosed(eventSource: EventSource) {
                Log.w(TAG, "SSE closed by server, will reconnect")
                updateNotification("Odpojeno · pokouším se znovu připojit")
                scheduleReconnect()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.w(TAG, "SSE failure: ${t?.message} status=${response?.code}", t)
                updateNotification("Bez spojení · zkouším znovu")
                scheduleReconnect()
            }
        }

        eventSource = EventSources.createFactory(httpClient).newEventSource(request, listener)
    }

    private fun scheduleReconnect() {
        // Simple linear retry 5 s — v reálném použití by stálo zato exp backoff,
        // ale pro pilot s desítkami minut běhu je 5 s constant jednoduché a OK.
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(5_000)
            connectStream()
        }
    }

    private fun handleEvent(rawData: String) {
        val app = applicationContext as? NaviglinkApp ?: return
        val parsed = runCatching { json.parseToJsonElement(rawData) }.getOrNull() as? JsonObject ?: return
        val type = parsed["type"]?.jsonPrimitive?.contentOrNull
        if (type != "alert") return  // hello / ping ignorujeme

        val subjectId = parsed["subject_id"]?.jsonPrimitive?.contentOrNull ?: return
        val ulice = parsed["ulice"]?.jsonPrimitive?.contentOrNull ?: "(bez popisu)"
        val validFrom = parsed["valid_from"]?.jsonPrimitive?.contentOrNull ?: ""
        val validTo = parsed["valid_to"]?.jsonPrimitive?.contentOrNull

        // Build minimal SignedSubject stub pro AlertNotifier (potřebuje payload.ulice
        // + validFrom/To pro formátování). Skutečný subjekt si app stáhne přes
        // checkNow flow, až driver klepne notifikaci.
        val stubSubject = SignedSubject(
            id = subjectId,
            kind = "subject",
            authors = emptyList(),
            signatures = emptyList(),
            validFrom = validFrom,
            validTo = validTo?.takeIf { it.isNotEmpty() },
            references = emptyMap(),
            payload = buildPayload(ulice),
            sigScheme = "ed25519",
        )

        AlertNotifier.show(applicationContext, stubSubject)
    }

    private fun buildPayload(ulice: String): JsonObject {
        return JsonObject(mapOf(
            "ulice" to kotlinx.serialization.json.JsonPrimitive(ulice),
            "typ" to kotlinx.serialization.json.JsonPrimitive("blokove_cisteni"),
        ))
    }

    private fun buildNotification(statusText: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NaviglinkApp.CHANNEL_LOCATION_SERVICE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Naviglink — sleduji upozornění")
            .setContentText(statusText)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notif = buildNotification(statusText)
        val nm = androidx.core.app.NotificationManagerCompat.from(this)
        if (nm.areNotificationsEnabled()) {
            nm.notify(NOTIFICATION_ID, notif)
        }
    }

    companion object {
        const val TAG = "EventStreamService"
        const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            val i = Intent(context, EventStreamService::class.java)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EventStreamService::class.java))
        }
    }
}
