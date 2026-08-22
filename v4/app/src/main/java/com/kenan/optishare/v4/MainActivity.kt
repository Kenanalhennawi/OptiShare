package com.kenan.optishare.v4

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kenan.optishare.v4.transport.ConnectionState
import com.kenan.optishare.v4.transport.NearbyConnectionsTransport
import com.kenan.optishare.v4.transport.NearbyPeer

class MainActivity : ComponentActivity() {
    private lateinit var transport: NearbyConnectionsTransport
    private var pendingAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) pendingAction?.invoke()
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        transport = NearbyConnectionsTransport(applicationContext)
        transport.setLocalName(Build.MODEL ?: "Android device")

        setContent {
            MaterialTheme {
                OptiShareScreen(
                    transport = transport,
                    onSend = { withNearbyPermissions { transport.startDiscovery() } },
                    onReceive = { withNearbyPermissions { transport.startAdvertising() } }
                )
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing) transport.stop()
        super.onDestroy()
    }

    private fun withNearbyPermissions(action: () -> Unit) {
        val missing = requiredNearbyPermissions().filter {
            Build.VERSION.SDK_INT >= 23 && checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requiredNearbyPermissions(): List<String> = buildList {
        when {
            Build.VERSION.SDK_INT >= 31 -> {
                add(Manifest.permission.BLUETOOTH_ADVERTISE)
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            Build.VERSION.SDK_INT >= 29 -> add(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= 32) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    }
}

@Composable
private fun OptiShareScreen(
    transport: NearbyConnectionsTransport,
    onSend: () -> Unit,
    onReceive: () -> Unit
) {
    val state by transport.state.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF06111F), Color(0xFF09283E), Color(0xFF1A1640))
                )
            )
    ) {
        Scaffold(containerColor = Color.Transparent) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item { Spacer(Modifier.height(10.dp)) }
                item { BrandHeader() }
                item { StatusCard(state) }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ActionCard(
                            title = "Send",
                            subtitle = "Find nearby devices",
                            glyph = "↑",
                            modifier = Modifier.weight(1f),
                            onClick = onSend
                        )
                        ActionCard(
                            title = "Receive",
                            subtitle = "Become discoverable",
                            glyph = "↓",
                            modifier = Modifier.weight(1f),
                            onClick = onReceive
                        )
                    }
                }

                when (val current = state) {
                    is ConnectionState.PeersFound -> {
                        item { SectionTitle("Nearby OptiShare devices") }
                        items(current.peers, key = { it.id }) { peer ->
                            PeerCard(peer) { transport.requestConnection(peer) }
                        }
                    }
                    is ConnectionState.Searching -> {
                        item { SectionTitle("Nearby devices") }
                        item { SearchCard(current.message) }
                    }
                    else -> Unit
                }

                item { PromiseCard() }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        val verification = state as? ConnectionState.VerificationRequired
        if (verification != null) {
            VerificationDialog(
                verification = verification,
                onAccept = { transport.confirmPendingConnection(true) },
                onDecline = { transport.confirmPendingConnection(false) }
            )
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF2CC7FF), Color(0xFF7357FF)))),
            contentAlignment = Alignment.Center
        ) {
            Text("O", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text("OptiShare", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
            Text("Private nearby sharing", color = Color(0xFF9BC7DF), fontSize = 12.sp)
        }
    }
}

@Composable
private fun StatusCard(state: ConnectionState) {
    val title: String
    val detail: String
    val accent: Color
    when (state) {
        ConnectionState.Idle -> {
            title = "Ready to share"; detail = "Choose Send or Receive to begin."; accent = Color(0xFF5BD8FF)
        }
        is ConnectionState.Preparing -> {
            title = "Preparing"; detail = state.message; accent = Color(0xFFFFC166)
        }
        is ConnectionState.Advertising -> {
            title = "Ready to receive"; detail = state.message; accent = Color(0xFF5BE0A5)
        }
        is ConnectionState.Searching -> {
            title = "Searching nearby"; detail = state.message; accent = Color(0xFF5BD8FF)
        }
        is ConnectionState.PeersFound -> {
            title = "Devices found"; detail = "Tap a device to connect securely."; accent = Color(0xFF5BD8FF)
        }
        is ConnectionState.Connecting -> {
            title = "Connecting"; detail = "Connecting to ${state.peer.displayName}…"; accent = Color(0xFFFFC166)
        }
        is ConnectionState.VerificationRequired -> {
            title = "Verify connection"; detail = "Compare the code on both devices."; accent = Color(0xFFFFC166)
        }
        is ConnectionState.Connected -> {
            title = "Connected"; detail = "Secure link with ${state.peer.displayName}"; accent = Color(0xFF5BE0A5)
        }
        is ConnectionState.Retrying -> {
            title = "Restoring connection"; detail = state.message; accent = Color(0xFFFFC166)
        }
        is ConnectionState.Failed -> {
            title = "Needs attention"; detail = state.userMessage; accent = Color(0xFFFF7D8A)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xB3152B3D)),
        shape = RoundedCornerShape(26.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(13.dp).clip(CircleShape).background(accent))
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(detail, color = Color(0xFFB6CEDD), fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    subtitle: String,
    glyph: String,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xCC173A54))
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(glyph, color = Color(0xFF6FDCFF), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color(0xFFA3BECE), fontSize = 11.sp)
        }
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(value, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SearchCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x99162B3B)),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("◎", color = Color(0xFF6FDCFF), fontSize = 42.sp)
            Text(message, color = Color(0xFFC2D5E0), fontSize = 13.sp)
        }
    }
}

@Composable
private fun PeerCard(peer: NearbyPeer, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xB3122A3C))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF214E69)),
                contentAlignment = Alignment.Center
            ) {
                Text("◉", color = Color(0xFF6FDCFF), fontSize = 19.sp)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(peer.displayName, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("OptiShare nearby", color = Color(0xFF95B8CB), fontSize = 11.sp)
            }
            Text("Connect", color = Color(0xFF6FDCFF), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PromiseCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x80102131)),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp)) {
            Text("Built for reliability", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "No account • No cloud relay • Encrypted pairing • Automatic transport fallback",
                color = Color(0xFF96B7C9),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun VerificationDialog(
    verification: ConnectionState.VerificationRequired,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDecline,
        title = { Text("Verify ${verification.peer.displayName}") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Compare this code on both devices")
                Spacer(Modifier.height(12.dp))
                Text(
                    verification.digits,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Black,
                    color = Color(0xFF1F79D6)
                )
            }
        },
        confirmButton = { Button(onClick = onAccept) { Text("Codes match") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("Cancel") } }
    )
}
