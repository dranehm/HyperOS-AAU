package com.xiaomi.unlock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ntp.NTPUDPClient
import org.json.JSONObject
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.time.Duration
import java.util.TimeZone
import kotlin.math.max

class UnlockViewModel : ViewModel() {
    var cookie by mutableStateOf("")
    var isRunning by mutableStateOf(false)
    var isTestingCookie by mutableStateOf(false)
    var isTestingProxy by mutableStateOf(false)
    var caffeineMode by mutableStateOf(false)
    var trustDeviceClock by mutableStateOf(true)   // Skip NTP; trust Android's auto-synced clock
    var maxTriggers by mutableStateOf("4")
    var proxyAddress by mutableStateOf("")        // e.g. "socks5://127.0.0.1:1080"
    var timingOffsetMsStr by mutableStateOf("0")  // ±ms manual calibration offset
    var bracketWidthMsStr by mutableStateOf("50") // wave spread window in ms

    var latencyMs by mutableStateOf<Long?>(null)
    var ntpOffsetMs by mutableStateOf<Long?>(null)
    var proxyRttMs by mutableStateOf<Long?>(null)
    var countdownText by mutableStateOf("Ready")

    val logs = mutableStateListOf<String>()
    val waves = mutableStateListOf<WaveStatus>()

    private var appContext: Context? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Built with HTTP/2 + optional proxy — rebuilt via rebuildClient() before each process run.
    private var client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .build()

    private fun rebuildClient() {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        parseProxy(proxyAddress)?.let { builder.proxy(it) }
        client = builder.build()
    }

    private fun parseProxy(address: String): java.net.Proxy? {
        if (address.isBlank()) return null
        return try {
            val uri = java.net.URI(address.trim())
            val type = when (uri.scheme?.lowercase()) {
                "socks5", "socks" -> java.net.Proxy.Type.SOCKS
                "http", "https"   -> java.net.Proxy.Type.HTTP
                else -> return null
            }
            java.net.Proxy(type, java.net.InetSocketAddress(uri.host, uri.port))
        } catch (e: Exception) { null }
    }

    private val userAgent = "okhttp/4.12.0"
    private val unlockUrl = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth"

    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")

    companion object {
        private const val CHANNEL_ID = "unlock_result_channel"
        private const val NOTIFICATION_ID = 1001
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        createNotificationChannel()
        loadPreferences()
    }

    private fun loadPreferences() {
        val prefs = appContext?.getSharedPreferences("unlock_prefs", Context.MODE_PRIVATE) ?: return
        proxyAddress      = prefs.getString("proxyAddress", "") ?: ""
        timingOffsetMsStr = prefs.getString("timingOffsetMs", "0") ?: "0"
        bracketWidthMsStr = prefs.getString("bracketWidthMs", "50") ?: "50"
        maxTriggers       = prefs.getString("maxTriggers", "4") ?: "4"
        trustDeviceClock  = prefs.getBoolean("trustDeviceClock", true)
    }

    private fun savePreferences() {
        val prefs = appContext?.getSharedPreferences("unlock_prefs", Context.MODE_PRIVATE) ?: return
        prefs.edit()
            .putString("proxyAddress", proxyAddress)
            .putString("timingOffsetMs", timingOffsetMsStr)
            .putString("bracketWidthMs", bracketWidthMsStr)
            .putString("maxTriggers", maxTriggers)
            .putBoolean("trustDeviceClock", trustDeviceClock)
            .apply()
    }

    private fun createNotificationChannel() {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Unlock Results",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for bootloader unlock attempt results"
                enableVibration(true)
            }
            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showSuccessNotification(message: String) {
        val ctx = appContext ?: return
        try {
            val notification = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("✅ Unlock Approved!")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .build()

            val notificationManager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            log("[Notify] Permission denied — could not show notification")
        }
    }

    private fun acquireWakeLock() {
        val ctx = appContext ?: return
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "HyperOSAAU::UnlockWakeLock"
            ).apply {
                acquire(4 * 60 * 60 * 1000L) // 4 hour timeout safety
            }
            log("[WakeLock] Acquired — CPU will stay active")
        } catch (e: Exception) {
            log("[WakeLock] Error: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    log("[WakeLock] Released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            log("[WakeLock] Release error: ${e.message}")
        }
    }

    private fun log(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            logs.add(message)
        }
    }

    fun testProxy() {
        viewModelScope.launch(Dispatchers.IO) {
            isTestingProxy = true
            rebuildClient()
            val label = proxyAddress.ifBlank { "direct (no proxy)" }
            log("[Proxy] Testing via $label ...")
            val times = mutableListOf<Long>()
            for (i in 1..3) {
                try {
                    val t0 = System.currentTimeMillis()
                    val req = Request.Builder().url(unlockUrl).head().build()
                    client.newCall(req).execute().close()
                    times.add(System.currentTimeMillis() - t0)
                } catch (e: Exception) {
                    log("[Proxy] Attempt $i failed: ${e.message}")
                }
            }
            withContext(Dispatchers.Main) {
                if (times.isNotEmpty()) {
                    val minRtt = times.minOrNull()!!
                    proxyRttMs = minRtt
                    log("[Proxy] ✅ OK! Min RTT: ${minRtt}ms | avg: ${times.average().toLong()}ms")
                } else {
                    proxyRttMs = null
                    log("[Proxy] ❌ All requests failed — check proxy address")
                }
                isTestingProxy = false
            }
        }
    }

    fun startProcess() {
        if (cookie.isBlank()) {
            log("[!] Cookie cannot be empty.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            isRunning = true
            isTestingCookie = true
            acquireWakeLock()
            rebuildClient()
            savePreferences()
            log("=" * 40)
            log("Starting Xiaomi BL Unlock Automator (Pro Mode)...")

            // 1. Test Cookie — also captures real POST RTT to the backend
            log("[Test] Verifying cookie...")
            val (isValid, cookieRttMs) = testCookie()
            isTestingCookie = false

            if (!isValid) {
                log("[!] Cookie rejected (need login). It may have expired. Please paste a new one.")
                releaseWakeLock()
                isRunning = false
                return@launch
            }
            log("[✓] Cookie is valid! Setting up... (baseline POST RTT: ${cookieRttMs}ms)")

            // 2. Clock sync
            // NTP through cellular/asymmetric networks gives wrong offsets (e.g. +465ms when
            // the device clock is actually accurate). Android auto-syncs the device clock to
            // within ~50ms. "Trust Device Clock" uses offset=0 which is safer on 4G.
            if (trustDeviceClock) {
                ntpOffsetMs = 0L
                log("[Clock] Trusting device clock (NTP skipped) — offset = 0ms")
            } else {
                log("[NTP] Syncing against 3 servers...")
                val offset = getNtpOffset()
                ntpOffsetMs = offset
                if (kotlin.math.abs(offset) > 200L) {
                    log("[⚠️ NTP] Large offset detected (${offset}ms)! On cellular/4G this is likely")
                    log("[⚠️ NTP] measurement error from asymmetric latency — NOT true clock drift.")
                    log("[⚠️ NTP] This would cause requests to arrive ${offset}ms OFF from midnight.")
                    log("[⚠️ NTP] Consider enabling 'Trust Device Clock' to use offset=0 instead.")
                } else {
                    log("[NTP] Clock offset: ${offset}ms ✓")
                }
            }

            // Calculate target time (Next Beijing Midnight)
            val targetUtcMs = getNextBeijingMidnightMs()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'CST'", Locale.US).apply { timeZone = beijingTz }
            log("[Target] ${sdf.format(Date(targetUtcMs))} (Beijing Midnight)")

            // 3. Wait until exactly 23:59:50 Beijing Time to accurately measure ping.
            //    Also fires a connection pre-warm at T-30s with keepalive pings every 5s.
            val targetPingTimeUtcMs = targetUtcMs - 10_000L
            var warmupFired = false
            var nextKeepalivePingMs = Long.MAX_VALUE

            while (isRunning) {
                val nowAccurate = System.currentTimeMillis() + (ntpOffsetMs ?: 0L)
                val remainingToPing = targetPingTimeUtcMs - nowAccurate

                if (remainingToPing <= 0) break

                // Fire pre-warm at T-30s; then keepalive pings every 5s until T-2s
                if (!warmupFired && remainingToPing <= 30_000) {
                    warmupFired = true
                    log("[Warmup] T-30s: Establishing TLS connection + keepalive pings every 5s...")
                    launch(Dispatchers.IO) {
                        try {
                            client.newCall(Request.Builder().url(unlockUrl).head().build()).execute().close()
                        } catch (e: Exception) { /* ignore */ }
                    }
                    nextKeepalivePingMs = System.currentTimeMillis() + 5_000L
                }
                if (warmupFired && remainingToPing > 2_000 && System.currentTimeMillis() >= nextKeepalivePingMs) {
                    launch(Dispatchers.IO) {
                        try {
                            client.newCall(Request.Builder().url(unlockUrl).head().build()).execute().close()
                        } catch (e: Exception) { /* ignore */ }
                    }
                    nextKeepalivePingMs = System.currentTimeMillis() + 5_000L
                }

                if (remainingToPing > 60_000) {
                    val h = remainingToPing / 3600_000
                    val m = (remainingToPing % 3600_000) / 60_000
                    val s = (remainingToPing % 60_000) / 1000
                    withContext(Dispatchers.Main) {
                        countdownText = String.format("Ping in %02dh %02dm %02ds", h, m, s)
                    }
                    delay(1000)
                } else if (remainingToPing > 3000) {
                    withContext(Dispatchers.Main) {
                        countdownText = String.format("Ping in %.2fs", remainingToPing / 1000.0)
                    }
                    delay(50)
                } else {
                    withContext(Dispatchers.Main) {
                        countdownText = String.format("Ping in %.3fs", remainingToPing / 1000.0)
                    }
                    delay(remainingToPing.coerceAtLeast(0L))
                    break
                }
            }

            if(!isRunning) { releaseWakeLock(); return@launch } // cancelled

            // 4. Measure JIT Latency (HEAD to actual API path at 23:59:50)
            log("[Latency] 23:59:50 reached! Measuring final latency...")
            withContext(Dispatchers.Main) { countdownText = "Pinging..." }
            val headLat = measureLatency()
            // Use the best available one-way latency estimate:
            //   - cookieRttMs/2: real POST RTT measured earlier (includes CDN→backend)
            //   - headLat/2: fresh HEAD RTT measured just now (CDN path only, but fresher)
            // Take the larger of the two to avoid arriving too early.
            val lat = maxOf(cookieRttMs, headLat) / 2
            latencyMs = lat * 2 // Display the full RTT to the user
            log("[Latency] HEAD min RTT: ${headLat}ms | POST RTT: ${cookieRttMs}ms → one-way estimate: ${lat}ms")

            // 5. Calculate Spam Bracket Timings
            val triggerCount = (maxTriggers.toIntOrNull() ?: 4).coerceAtLeast(1)
            val bracketMs = bracketWidthMsStr.toLongOrNull()?.coerceIn(10L, 1000L) ?: 50L
            val timingOffset = timingOffsetMsStr.toLongOrNull() ?: 0L
            log("[Config] Firing $triggerCount trigger(s) over ${bracketMs}ms bracket")
            if (timingOffset != 0L) log("[Config] Manual timing offset: ${if (timingOffset > 0) "+" else ""}${timingOffset}ms")

            // Send at (midnight - one-way latency + user offset) so the request ARRIVES at/after midnight.
            val baseSendTimeUtcMs = targetUtcMs - lat + timingOffset

            // Spread waves from 0ms to +bracketMs AFTER midnight arrival time.
            // All waves are biased to arrive AFTER the slot opens (never before midnight),
            // maximising the chance of hitting the quota window.
            val offsets = if (triggerCount == 1) {
                listOf(0L)
            } else {
                (0 until triggerCount).map { i ->
                    (bracketMs * i) / (triggerCount - 1)
                }
            }
            
            val wave1SendTimeUtcMs = baseSendTimeUtcMs + offsets.first()

            withContext(Dispatchers.Main) {
                waves.clear()
                offsets.forEachIndexed { idx, offsetMs ->
                    val label = "+${offsetMs}ms"
                    waves.add(WaveStatus(idx + 1, label))
                }
            }

            // Wait exactly for Wave 1
            while (isRunning) {
                val nowAccurate = System.currentTimeMillis() + (ntpOffsetMs ?: 0L)
                val remainingToFire = wave1SendTimeUtcMs - nowAccurate

                if (remainingToFire <= 0) break

                if (remainingToFire > 2000) {
                    withContext(Dispatchers.Main) { countdownText = String.format("Fire in %.2fs", remainingToFire / 1000.0) }
                    delay(50)
                } else {
                    withContext(Dispatchers.Main) { countdownText = String.format("Fire in %.3fs", remainingToFire / 1000.0) }
                    delay(remainingToFire.coerceAtLeast(0L))
                    break
                }
            }
            if(!isRunning) { releaseWakeLock(); return@launch }

            withContext(Dispatchers.Main) { countdownText = "FIRING" }
            log("===")

            // Fire all waves dynamically
            offsets.forEachIndexed { idx, offsetMs ->
                if (idx > 0) {
                    val gapMs = offsets[idx] - offsets[idx - 1]
                    delay(gapMs)
                }
                val waveId = idx + 1
                launch(Dispatchers.IO) {
                    val sendTs = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = beijingTz }.format(Date(System.currentTimeMillis() + (ntpOffsetMs ?: 0L)))
                    val estArrivalMs = System.currentTimeMillis() + (ntpOffsetMs ?: 0L) + lat
                    val arrivalTs = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = beijingTz }.format(Date(estArrivalMs))
                    log("[Wave $waveId] Sent $sendTs → est. arrival $arrivalTs CST (+${offsetMs}ms bracket)")
                    withContext(Dispatchers.Main) { if (idx in waves.indices) waves[idx].state = WaveState.SENDING }
                    sendWave(waveId, 0)
                }
            }

            delay(3000) // Wait for responses
            log("[Done] Process Complete.")
            releaseWakeLock()
            isRunning = false
            withContext(Dispatchers.Main) { countdownText = "Done" }
        }
    }

    fun stopProcess() {
        isRunning = false
        releaseWakeLock()
        log("[!] User aborted.")
    }

    override fun onCleared() {
        super.onCleared()
        releaseWakeLock()
    }

    private operator fun String.times(n: Int): String {
        return this.repeat(max(0, n))
    }

    private fun buildHeaders(reqBuilder: Request.Builder): Request.Builder {
        return reqBuilder
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip")
            .header("Connection", "Keep-Alive")
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Cookie", cookie)
            .header("Host", "sgp-api.buy.mi.com")
            .header("User-Agent", userAgent)
    }

    // Returns (isValid, rttMs). The RTT is measured on the actual POST to the unlock URL,
    // giving a true end-to-end latency that includes CDN→backend forwarding.
    private fun testCookie(): Pair<Boolean, Long> {
        return try {
            val t0 = System.currentTimeMillis()
            val reqBody = "{\"is_retry\":false}".toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = buildHeaders(Request.Builder().url(unlockUrl).post(reqBody)).build()
            val resp = client.newCall(req).execute()
            val rttMs = System.currentTimeMillis() - t0
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)
            val msg = json.optString("msg", "")
            val data = json.optJSONObject("data")
            val result = data?.optInt("apply_result", -1) ?: -1

            val meaning = getResultMeaning(result)
            log("[Test] HTTP ${resp.code} | msg=$msg | result=$result $meaning | RTT=${rttMs}ms")

            Pair(msg != "need login", rttMs)
        } catch (e: Exception) {
            log("[Test] Error: ${e.message}")
            Pair(false, 0L)
        }
    }

    private fun getNtpOffset(): Long {
        val servers = listOf("pool.ntp.org", "time.cloudflare.com", "time.google.com")
        val offsets = mutableListOf<Long>()
        for (server in servers) {
            try {
                val ntpClient = NTPUDPClient()
                ntpClient.setDefaultTimeout(Duration.ofMillis(3000))
                ntpClient.open()
                val info = ntpClient.getTime(InetAddress.getByName(server))
                info.computeDetails()
                ntpClient.close()
                info.offset?.let { offsets.add(it) }
            } catch (e: Exception) {
                log("[NTP] $server unavailable: ${e.message?.take(40)}")
            }
        }
        return if (offsets.isEmpty()) {
            log("[NTP] All servers failed — using 0 offset")
            0L
        } else {
            offsets.sorted()[offsets.size / 2].also {
                log("[NTP] ${offsets.size}/3 servers responded → median offset: ${it}ms")
            }
        }
    }

    private fun measureLatency(): Long {
        // Target the actual API path (not root) so the measurement reflects
        // the full CDN→backend round trip, not just the CDN edge response.
        val times = mutableListOf<Long>()
        for (i in 1..5) {
            try {
                val t0 = System.currentTimeMillis()
                val req = Request.Builder().url(unlockUrl).head().build()
                client.newBuilder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().close()
                times.add(System.currentTimeMillis() - t0)
            } catch (e: Exception) {
                // Ignore failure
            }
        }
        // Use minimum (best-case RTT, closest to true network latency without queuing jitter)
        return if (times.isNotEmpty()) {
            times.minOrNull()!!
        } else {
            log("[Latency] Could not measure — defaulting to 300ms")
            300L
        }
    }

    private fun getNextBeijingMidnightMs(): Long {
        val cal = java.util.Calendar.getInstance(beijingTz)
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private suspend fun sendWave(waveId: Int, delayMs: Long) {
        if (delayMs > 0) delay(delayMs)
        val waveIndex = waveId - 1
        try {
            val reqBody = "{\"is_retry\":false}".toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = buildHeaders(Request.Builder().url(unlockUrl).post(reqBody)).build()
            val resp = client.newCall(req).execute()

            val body = resp.body?.string() ?: ""
            val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = beijingTz }.format(Date())

            try {
                val json = JSONObject(body)
                val msg = json.optString("msg", "?")
                val data = json.optJSONObject("data")
                val result = data?.optInt("apply_result", -1) ?: -1
                val meaning = getResultMeaning(result)
                log("[Wave $waveId] $ts CST | HTTP ${resp.code} | $msg | result=$result $meaning")

                // Notify on success
                if (result == 1) {
                    showSuccessNotification("Bootloader unlock slot secured successfully! (Wave $waveId at $ts CST)")
                } else if (result == 2) {
                    showSuccessNotification("Bootloader unlock was already approved. You're all set!")
                }

                withContext(Dispatchers.Main) {
                    if (waveIndex in waves.indices) {
                        if (result == 1 || result == 2) waves[waveIndex].state = WaveState.SUCCESS
                        else if (result == 6) waves[waveIndex].state = WaveState.FULL
                        else waves[waveIndex].state = WaveState.ERROR
                        waves[waveIndex].resultText = "Res $result"
                    }
                }
            } catch (e: Exception) {
                log("[Wave $waveId] $ts CST | HTTP ${resp.code} | ${body.take(100)}...")
                withContext(Dispatchers.Main) {
                    if (waveIndex in waves.indices) {
                        waves[waveIndex].state = WaveState.ERROR
                        waves[waveIndex].resultText = "HTTP ${resp.code}"
                    }
                }
            }

        } catch (e: Exception) {
            log("[Wave $waveId] ERROR: ${e.message}")
            withContext(Dispatchers.Main) {
                if (waveIndex in waves.indices) {
                    waves[waveIndex].state = WaveState.ERROR
                    waves[waveIndex].resultText = "Error"
                }
            }
        }
    }

    private fun getResultMeaning(code: Int): String {
        return when (code) {
            1 -> "✅ APPROVED!"
            2 -> "✅ Already approved"
            6 -> "❌ Quota full - try tomorrow"
            else -> ""
        }
    }
}

enum class WaveState { IDLE, SENDING, SUCCESS, FULL, ERROR }

class WaveStatus(
    val id: Int,
    val offset: String
) {
    var state by mutableStateOf(WaveState.IDLE)
    var resultText by mutableStateOf("Pending")
}
