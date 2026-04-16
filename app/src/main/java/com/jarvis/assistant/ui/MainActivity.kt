package com.jarvis.assistant.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import com.jarvis.assistant.auth.OAuthCallbackHolder
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * MainActivity — the single Activity in the app.
 *
 * RESPONSIBILITIES:
 *  1. Request all runtime permissions (mic, notifications, boot).
 *  2. Explain why we need battery-optimisation exemption, then request it.
 *  3. Host the Compose NavHost with two routes: main and settings.
 *
 * WHY ONE ACTIVITY?
 * Jetpack Compose works best with a single Activity. Navigation between
 * "screens" is handled by NavHost in Compose, not by starting new Activities.
 */
class MainActivity : ComponentActivity() {

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            JarvisTheme {
                // ── Permission gating ─────────────────────────────────────
                // Build the list of permissions we need at runtime.
                // Some are only required on newer API levels.
                val runtimePermissions = buildList {
                    add(Manifest.permission.RECORD_AUDIO)
                    add(Manifest.permission.CALL_PHONE)
                    add(Manifest.permission.READ_CONTACTS)
                    add(Manifest.permission.SEND_SMS)
                    add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    // BLUETOOTH_CONNECT is a runtime permission from Android 12 (S) onwards.
                    // Required to query connected headsets and route audio through SCO.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        add(Manifest.permission.BLUETOOTH_CONNECT)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val permissionState = rememberMultiplePermissionsState(runtimePermissions)

                // Battery optimisation dialog state
                var showBatteryDialog by remember { mutableStateOf(false) }

                // Once all runtime permissions granted, check battery optimisation
                LaunchedEffect(permissionState.allPermissionsGranted) {
                    if (permissionState.allPermissionsGranted) {
                        showBatteryDialog = !isBatteryOptimisationIgnored()
                    }
                }

                if (!permissionState.allPermissionsGranted) {
                    PermissionRationaleScreen(
                        onRequest = { permissionState.launchMultiplePermissionRequest() }
                    )
                } else {
                    // All permissions granted — show app
                    AppNavHost()
                }

                // Battery optimisation dialog
                if (showBatteryDialog) {
                    BatteryOptimisationDialog(
                        onConfirm = {
                            showBatteryDialog = false
                            requestBatteryOptimisationExemption()
                        },
                        onDismiss = { showBatteryDialog = false }
                    )
                }
            }
        }
    }

    /**
     * Called when the app is already running and receives a new Intent —
     * specifically the OAuth redirect: com.jarvis.assistant://oauth/callback?code=...
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val uri = intent.data ?: return
        if (uri.scheme == "com.jarvis.assistant" && uri.host == "oauth") {
            val code = uri.getQueryParameter("code") ?: return
            OAuthCallbackHolder.invoke(code)
        }
    }

    private fun isBatteryOptimisationIgnored(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryOptimisationExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }
}

// ── Navigation ─────────────────────────────────────────────────────────────────

@Composable
private fun AppNavHost() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = "main") {
        composable("main") {
            MainScreen(onOpenSettings = { nav.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}

// ── Permission rationale screen ────────────────────────────────────────────────

@Composable
private fun PermissionRationaleScreen(onRequest: () -> Unit) {
    val Cyan    = Color(0xFF00BCD4)
    val BgDark  = Color(0xFF0D0D0D)
    val Surface = Color(0xFF1A1A2E)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgDark)
            .padding(32.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "Jarvis needs a few permissions",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            PermissionItem("Microphone", "Required to hear wake words and spoken commands.")
            PermissionItem("Phone", "Required to make calls — say \"call [name]\" to dial.")
            PermissionItem("Contacts", "Required to look up names — say \"call John\" or \"text Sarah\".")
            PermissionItem("SMS", "Required to send messages — say \"text [name] [message]\".")
            PermissionItem("Location", "Allows Jarvis to know your approximate location for relevant answers.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PermissionItem("Bluetooth", "Required to use a Bluetooth headset for hands-free voice input.")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionItem("Notifications", "Required to show the 'Jarvis is listening' status.")
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = onRequest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Grant Permissions", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun PermissionItem(name: String, reason: String) {
    val Surface = Color(0xFF1A1A2E)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(name, color = Color(0xFF00BCD4), fontWeight = FontWeight.SemiBold)
        Text(reason, color = Color(0xFFBBBBBB), fontSize = 13.sp)
    }
}

// ── Battery optimisation dialog ────────────────────────────────────────────────

@Composable
private fun BatteryOptimisationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1A1A2E),
        title = {
            Text("Disable Battery Optimisation", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                "Android may stop Jarvis when the screen turns off to save battery.\n\n" +
                "Tapping OK will take you to the system screen where you can exempt " +
                "Jarvis from battery optimisation. This is required for reliable " +
                "always-on operation.",
                color = Color(0xFFCCCCCC)
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("OK", color = Color(0xFF00BCD4), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not now", color = Color(0xFF888888))
            }
        }
    )
}

// ── Theme ──────────────────────────────────────────────────────────────────────

@Composable
private fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background    = Color(0xFF0D0D0D),
            surface       = Color(0xFF1A1A2E),
            primary       = Color(0xFF00BCD4),
            onBackground  = Color(0xFFE0E0E0),
            onSurface     = Color(0xFFE0E0E0)
        ),
        content = content
    )
}
