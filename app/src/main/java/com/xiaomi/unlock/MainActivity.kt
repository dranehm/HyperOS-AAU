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
import androidx.compose.foundation.shape.RoundedCornerShape
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

    // Auto-scroll to bottom when logs update
    LaunchedEffect(viewModel.logs.size) {
        if (viewModel.logs.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.logs.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Header ---
        Text(
            text = "Xiaomi Unlock Automator",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = OrangeMain,
            modifier = Modifier.padding(bottom = 24.dp, top = 24.dp)
        )

        // --- Status Cards Section ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatusCard(
                title = "Latency",
                value = viewModel.latencyMs?.let { "${it}ms" } ?: "--"
            )
            StatusCard(
                title = "NTP Offset",
                value = viewModel.ntpOffsetMs?.let { "${it}ms" } ?: "--"
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Caffeine Mode Toggle ---
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (viewModel.caffeineMode) Color(0xFF2A1F00) else SurfaceColor
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "☕ Caffeine Mode",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (viewModel.caffeineMode) OrangeMain else Color.White
                    )
                    Text(
                        text = "Keep screen awake during process",
                        fontSize = 11.sp,
                        color = TextGray
                    )
                }
                Switch(
                    checked = viewModel.caffeineMode,
                    onCheckedChange = { viewModel.caffeineMode = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = OrangeMain,
                        uncheckedThumbColor = TextGray,
                        uncheckedTrackColor = SurfaceColor
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // --- Countdown Display ---
        Text(
            text = viewModel.countdownText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (viewModel.isRunning) OrangeMain else Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp).fillMaxWidth()
        )

        // --- Cookie Input ---
        OutlinedTextField(
            value = viewModel.cookie,
            onValueChange = { viewModel.cookie = it },
            label = { Text("Cookie String") },
            placeholder = { Text("Paste Cookie Here...") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !viewModel.isRunning,
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OrangeMain,
                focusedLabelColor = OrangeMain,
                cursorColor = OrangeMain
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        // --- Max Triggers Input ---
        OutlinedTextField(
            value = viewModel.maxTriggers,
            onValueChange = { newVal ->
                if (newVal.isEmpty() || newVal.all { it.isDigit() }) {
                    viewModel.maxTriggers = newVal
                }
            },
            label = { Text("Max Triggers") },
            placeholder = { Text("4") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !viewModel.isRunning,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = OrangeMain,
                focusedLabelColor = OrangeMain,
                cursorColor = OrangeMain
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Action Buttons ---
        if (!viewModel.isRunning) {
            Button(
                onClick = { viewModel.startProcess() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeMain)
            ) {
                Text("Verify & Start Process", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(
                onClick = { viewModel.stopProcess() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("Abort Process", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // --- Wave Indicators ---
        if (viewModel.waves.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                viewModel.waves.forEach { wave ->
                    WaveCard(wave)
                }
            }
        }

        // --- Log Console ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black)
                .padding(8.dp)
        ) {
            LazyColumn(state = listState) {
                items(viewModel.logs) { logMsg ->
                    Text(
                        text = logMsg,
                        color = Color(0xFF00FF00), // Terminal green
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun StatusCard(title: String, value: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier
            .width(150.dp)
            .height(80.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, fontSize = 12.sp, color = TextGray)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
