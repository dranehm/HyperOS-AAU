package com.xiaomi.unlock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.commons.net.ntp.NTPUDPClient
import org.json.JSONObject
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

class UnlockViewModel : ViewModel() {
    var cookie by mutableStateOf("")
    var isRunning by mutableStateOf(false)
    var isTestingCookie by mutableStateOf(false)
    
    var latencyMs by mutableStateOf<Long?>(null)
    var ntpOffsetMs by mutableStateOf<Long?>(null)
    var countdownText by mutableStateOf("Ready")
    
    val logs = mutableStateListOf<String>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val userAgent = "okhttp/4.12.0"
    private val unlockUrl = "https://sgp-api.buy.mi.com/bbs/api/global/apply/bl-auth"
    private val ntpServer = "pool.ntp.org"

    private val beijingTz = TimeZone.getTimeZone("Asia/Shanghai")

    private fun log(message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            logs.add(message)
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
            log("=" * 40)
            log("Starting Xiaomi BL Unlock Automator (Pro Mode)...")
            
            // 1. Test Cookie
            log("[Test] Verifying cookie...")
            val isValid = testCookie()
            isTestingCookie = false
            
            if (!isValid) {
                log("[!] Cookie rejected (need login). It may have expired. Please paste a new one.")
                isRunning = false
                return@launch
            }
            log("[✓] Cookie is valid! Setting up...")

            // 2. Measure initial NTP
            log("[NTP] Syncing clock initially...")
            val offset = getNtpOffset()
            ntpOffsetMs = offset
            log("[NTP] Clock offset: ${offset}ms")

            // Calculate target time (Next Beijing Midnight)
            val targetUtcMs = getNextBeijingMidnightMs()
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'CST'", Locale.US).apply { timeZone = beijingTz }
            log("[Target] ${sdf.format(Date(targetUtcMs))} (Beijing Midnight)")
            
            // 3. Wait until exactly 23:59:50 Beijing Time to accurately measure ping
            val targetPingTimeUtcMs = targetUtcMs - 10_000L
            
            while (isRunning) {
                val nowAccurate = System.currentTimeMillis() + (ntpOffsetMs ?: 0L)
                val remainingToPing = targetPingTimeUtcMs - nowAccurate

                if (remainingToPing <= 0) break

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
                    delay(remainingToPing)
                    break
                }
            }
            
            if(!isRunning) return@launch // cancelled
            
            // 4. Measure JIT Latency
            log("[Latency] 23:59:50 reached! Measuring final latency...")
            withContext(Dispatchers.Main) { countdownText = "Pinging..." }
            val lat = measureLatency()
            latencyMs = lat
            log("[Latency] Final measured latency: ${lat}ms")
            
            // 5. Calculate Spam Bracket Timings
            // Base arrival time = targetUtcMs
            // We want arrival at 00:00:00, so we send at Base Send Time = target - latency
            val baseSendTimeUtcMs = targetUtcMs - lat
            
            // Brackets: -50ms before perfection, +50ms after perfection.
            val wave1SendTimeUtcMs = baseSendTimeUtcMs - 50L
            
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
                    delay(remainingToFire)
                    break
                }
            }
            if(!isRunning) return@launch
            
            withContext(Dispatchers.Main) { countdownText = "FIRING" }
            log("===")
            
            // Fire Wave 1 (Bracket -50ms)
            launch(Dispatchers.IO) {
                val w1Ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = beijingTz }.format(Date(System.currentTimeMillis() + (ntpOffsetMs ?: 0L)))
                log("[Spam 1] Launched at $w1Ts CST (-50ms bracket)")
                sendWave(1, 0)
            }
            
            // Wait 100ms exactly for Wave 2
            delay(100)
            
            // Fire Wave 2 (Bracket +50ms)
            launch(Dispatchers.IO) {
                val w2Ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply { timeZone = beijingTz }.format(Date(System.currentTimeMillis() + (ntpOffsetMs ?: 0L)))
                log("[Spam 2] Launched at $w2Ts CST (+50ms bracket)")
                sendWave(2, 0)
            }
            
            delay(2000) // Wait for responses
            log("[Done] Process Complete.")
            isRunning = false
            withContext(Dispatchers.Main) { countdownText = "Done" }
        }
    }
    
    fun stopProcess() {
        isRunning = false
        log("[!] User aborted.")
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

    private fun testCookie(): Boolean {
        return try {
            val reqBody = "{\"is_retry\":false}".toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = buildHeaders(Request.Builder().url(unlockUrl).post(reqBody)).build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: ""
            val json = JSONObject(body)
            val msg = json.optString("msg", "")
            val data = json.optJSONObject("data")
            val result = data?.optInt("apply_result", -1) ?: -1
            
            val meaning = getResultMeaning(result)
            log("[Test] HTTP ${resp.code} | msg=$msg | result=$result $meaning")
            
            msg != "need login"
        } catch (e: Exception) {
            log("[Test] Error: ${e.message}")
            false
        }
    }

    private fun getNtpOffset(): Long {
        return try {
            val ntpClient = NTPUDPClient()
            ntpClient.defaultTimeout = 5000
            ntpClient.open()
            val info = ntpClient.getTime(InetAddress.getByName(ntpServer))
            info.computeDetails()
            ntpClient.close()
            info.offset ?: 0L
        } catch (e: Exception) {
            log("[NTP Error] ${e.message} - Using 0 offset")
            0L
        }
    }

    private fun measureLatency(): Long {
        val times = mutableListOf<Long>()
        for (i in 1..5) {
            try {
                val t0 = System.currentTimeMillis()
                val req = Request.Builder().url("https://sgp-api.buy.mi.com").head().build()
                client.newBuilder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().close()
                times.add(System.currentTimeMillis() - t0)
            } catch (e: Exception) {
                // Ignore failure
            }
        }
        return if (times.isNotEmpty()) {
            times.sum() / times.size
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
            } catch (e: Exception) {
                log("[Wave $waveId] $ts CST | HTTP ${resp.code} | ${body.take(100)}...")
            }
            
        } catch (e: Exception) {
            log("[Wave $waveId] ERROR: ${e.message}")
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
