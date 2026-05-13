package com.xiaomi.unlock

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat

val OrangeMain = Color(0xFFFF6900)
val DarkBackground = Color(0xFF141414)
val SurfaceColor = Color(0xFF1E1E1E)
val TextGray = Color(0xFFAAAAAA)

class MainActivity : ComponentActivity() {
    private val viewModel: UnlockViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, we proceed either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize ViewModel with app context for wake lock and notifications
        viewModel.init(applicationContext)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            // Sync caffeine mode with window FLAG_KEEP_SCREEN_ON
            val caffeine = viewModel.caffeineMode
            DisposableEffect(caffeine) {
                if (caffeine) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = OrangeMain,
                    background = DarkBackground,
                    surface = SurfaceColor,
                    onPrimary = Color.White,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UnlockScreen(viewModel)
                }
            }
        }
    }
}

@Composable
fun UnlockScreen(viewModel: UnlockViewModel) {
    val listState = rememberLazyListState()
    val controlsScrollState = rememberScrollState()

    // Auto-scroll log to bottom when new entries arrive
    LaunchedEffect(viewModel.logs.size) {
        if (viewModel.logs.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.logs.size - 1)
        }
    }

    // Outer column: scrollable controls on top, fixed log at bottom
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // ── Controls section (scrollable) ──────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(controlsScrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // --- Header ---
            Text(
                text = "Xiaomi Unlock Automator",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = OrangeMain,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // --- Status Cards ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatusCard("Latency",   viewModel.latencyMs?.let  { "${it}ms" } ?: "--", Modifier.weight(1f))
                StatusCard("NTP Offset",viewModel.ntpOffsetMs?.let { "${it}ms" } ?: "--", Modifier.weight(1f))
                StatusCard("Proxy RTT", viewModel.proxyRttMs?.let  { "${it}ms" } ?: "--", Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // --- Mode Toggles ---
            ModeToggle(
                icon = "☕", label = "Caffeine Mode", sub = "Keep screen awake",
                checked = viewModel.caffeineMode,
                active = viewModel.caffeineMode,
                onToggle = { viewModel.caffeineMode = it }
            )
            Spacer(modifier = Modifier.height(4.dp))
            ModeToggle(
                icon = "🕐", label = "Trust Device Clock", sub = "Skip NTP (safe on 4G/cellular)",
                checked = viewModel.trustDeviceClock,
                active = viewModel.trustDeviceClock,
                onToggle = { viewModel.trustDeviceClock = it }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // --- Countdown ---
            Text(
                text = viewModel.countdownText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (viewModel.isRunning) OrangeMain else Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            )

            // --- Cookie ---
            OutlinedTextField(
                value = viewModel.cookie,
                onValueChange = { viewModel.cookie = it },
                label = { Text("Cookie String") },
                placeholder = { Text("Paste Cookie Here...") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !viewModel.isRunning,
                singleLine = true,
                colors = orangeFieldColors()
            )

            Spacer(modifier = Modifier.height(6.dp))

            // --- Config Row: Triggers + Bracket + Offset ---
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = viewModel.maxTriggers,
                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) viewModel.maxTriggers = it },
                    label = { Text("Triggers") }, placeholder = { Text("4") },
                    modifier = Modifier.weight(1f), enabled = !viewModel.isRunning, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = orangeFieldColors()
                )
                OutlinedTextField(
                    value = viewModel.bracketWidthMsStr,
                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) viewModel.bracketWidthMsStr = it },
                    label = { Text("Bracket ms") }, placeholder = { Text("50") },
                    modifier = Modifier.weight(1f), enabled = !viewModel.isRunning, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = orangeFieldColors()
                )
                OutlinedTextField(
                    value = viewModel.timingOffsetMsStr,
                    onValueChange = { nv -> if (nv.isEmpty() || nv == "-" || nv.toLongOrNull() != null) viewModel.timingOffsetMsStr = nv },
                    label = { Text("Offset ms") }, placeholder = { Text("0") },
                    modifier = Modifier.weight(1f), enabled = !viewModel.isRunning, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = orangeFieldColors()
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // --- Proxy Row ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = viewModel.proxyAddress,
                    onValueChange = { viewModel.proxyAddress = it },
                    label = { Text("Proxy (optional)") },
                    placeholder = { Text("socks5://127.0.0.1:1080") },
                    modifier = Modifier.weight(1f),
                    enabled = !viewModel.isRunning, singleLine = true,
                    colors = orangeFieldColors()
                )
                Button(
                    onClick = { viewModel.testProxy() },
                    enabled = !viewModel.isTestingProxy && !viewModel.isRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeMain),
                    modifier = Modifier.height(54.dp)
                ) {
                    Text(if (viewModel.isTestingProxy) "..." else "Test", fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // --- Action Button ---
            if (!viewModel.isRunning) {
                Button(
                    onClick = { viewModel.startProcess() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = OrangeMain)
                ) {
                    Text("Verify & Start Process", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = { viewModel.stopProcess() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Abort Process", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            // --- Wave Indicators ---
            if (viewModel.waves.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    viewModel.waves.forEach { wave -> WaveCard(wave) }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Log Console (fixed height, always visible) ─────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .padding(8.dp)
        ) {
            LazyColumn(state = listState) {
                items(viewModel.logs) { logMsg ->
                    Text(
                        text = logMsg,
                        color = Color(0xFF00FF00),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeToggle(
    icon: String, label: String, sub: String,
    checked: Boolean, active: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (active) Color(0xFF1A2A1A) else SurfaceColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "$icon $label", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = if (active) OrangeMain else Color.White
                )
                Text(text = sub, fontSize = 11.sp, color = TextGray)
            }
            Switch(
                checked = checked, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = OrangeMain,
                    uncheckedThumbColor = TextGray, uncheckedTrackColor = SurfaceColor
                )
            )
        }
    }
}

@Composable
private fun orangeFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = OrangeMain,
    focusedLabelColor = OrangeMain,
    cursorColor = OrangeMain
)

@Composable
fun StatusCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = modifier.height(64.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, fontSize = 10.sp, color = TextGray)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
    }
}

@Composable
fun WaveCard(wave: WaveStatus) {
    val color = when (wave.state) {
        WaveState.IDLE -> SurfaceColor
        WaveState.SENDING -> Color(0xFFE6A23C) // Yellow
        WaveState.SUCCESS -> Color(0xFF67C23A) // Green
        WaveState.FULL -> Color(0xFFF56C6C)    // Red
        WaveState.ERROR -> Color(0xFF909399)   // Gray
    }

    val textColor = if (wave.state == WaveState.IDLE) TextGray else Color.White

    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .width(80.dp)
            .height(65.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = wave.offset, fontSize = 11.sp, color = textColor)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = wave.resultText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = textColor,
                textAlign = TextAlign.Center
            )
        }
    }
}
