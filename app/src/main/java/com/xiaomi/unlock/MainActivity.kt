package com.xiaomi.unlock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign

val OrangeMain = Color(0xFFFF6900)
val DarkBackground = Color(0xFF141414)
val SurfaceColor = Color(0xFF1E1E1E)
val TextGray = Color(0xFFAAAAAA)

class MainActivity : ComponentActivity() {
    private val viewModel: UnlockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
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
        
        Spacer(modifier = Modifier.height(24.dp))

        // --- Countdown Display ---
        Text(
            text = viewModel.countdownText,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = if (viewModel.isRunning) OrangeMain else Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 24.dp).fillMaxWidth()
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
