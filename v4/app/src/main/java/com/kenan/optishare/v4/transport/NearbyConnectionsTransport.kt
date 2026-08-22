package com.kenan.optishare.v4.transport

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Primary OptiShare 4 discovery/connection transport.
 * File protocol payloads are intentionally kept above this layer.
 */
class NearbyConnectionsTransport(context: Context) {
    companion object {
        private const val SERVICE_ID = "com.kenan.optishare.v4.localshare"
        private val STRATEGY = Strategy.P2P_POINT_TO_POINT
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
    private val peers = linkedMapOf<String, NearbyPeer>()
    private val mutableState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = mutableState.asStateFlow()

    private var localName: String = android.os.Build.MODEL ?: "Android device"
    private var pendingEndpointId: String? = null
    private var pendingPeer: NearbyPeer? = null

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            // Transfer protocol integration is a separate layer by design.
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Transfer protocol integration is a separate layer by design.
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peer = peers[endpointId] ?: NearbyPeer(
                id = endpointId,
                displayName = info.endpointName.ifBlank { "Nearby device" },
                transport = TransportKind.NEARBY_CONNECTIONS
            )
            peers[endpointId] = peer
            pendingEndpointId = endpointId
            pendingPeer = peer
            mutableState.value = ConnectionState.VerificationRequired(
                peer = peer,
                digits = info.authenticationDigits
            )
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val peer = peers[endpointId] ?: pendingPeer
            if (resolution.status.isSuccess && peer != null) {
                mutableState.value = ConnectionState.Connected(peer)
            } else {
                mutableState.value = ConnectionState.Failed(
                    userMessage = "Could not establish the nearby connection. Try again.",
                    recoverable = true
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            mutableState.value = ConnectionState.Retrying(
                message = "Connection interrupted. Looking for the device again…",
                attempt = 1
            )
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            peers[endpointId] = NearbyPeer(
                id = endpointId,
                displayName = info.endpointName.ifBlank { "Android device" },
                transport = TransportKind.NEARBY_CONNECTIONS
            )
            mutableState.value = ConnectionState.PeersFound(peers.values.toList())
        }

        override fun onEndpointLost(endpointId: String) {
            peers.remove(endpointId)
            mutableState.value = if (peers.isEmpty()) {
                ConnectionState.Searching("Looking for nearby OptiShare devices…")
            } else {
                ConnectionState.PeersFound(peers.values.toList())
            }
        }
    }

    fun setLocalName(name: String) {
        localName = name.trim().take(48).ifBlank { "Android device" }
    }

    fun startAdvertising() {
        stop()
        mutableState.value = ConnectionState.Preparing("Preparing secure receiving session…")
        val options = AdvertisingOptions.Builder()
            .setStrategy(STRATEGY)
            .build()
        client.startAdvertising(localName, SERVICE_ID, connectionCallback, options)
            .addOnSuccessListener {
                mutableState.value = ConnectionState.Advertising("Ready to receive")
            }
            .addOnFailureListener { error ->
                mutableState.value = ConnectionState.Failed(
                    userMessage = error.message ?: "Could not start nearby receiving.",
                    recoverable = true
                )
            }
    }

    fun startDiscovery() {
        stop()
        peers.clear()
        mutableState.value = ConnectionState.Searching("Looking for nearby OptiShare devices…")
        val options = DiscoveryOptions.Builder()
            .setStrategy(STRATEGY)
            .build()
        client.startDiscovery(SERVICE_ID, discoveryCallback, options)
            .addOnFailureListener { error ->
                mutableState.value = ConnectionState.Failed(
                    userMessage = error.message ?: "Nearby discovery could not start.",
                    recoverable = true
                )
            }
    }

    fun requestConnection(peer: NearbyPeer) {
        pendingPeer = peer
        mutableState.value = ConnectionState.Connecting(peer, attempt = 1)
        client.requestConnection(localName, peer.id, connectionCallback)
            .addOnFailureListener { error ->
                mutableState.value = ConnectionState.Failed(
                    userMessage = error.message ?: "Connection request failed.",
                    recoverable = true
                )
            }
    }

    fun confirmPendingConnection(accept: Boolean) {
        val endpointId = pendingEndpointId ?: return
        if (accept) {
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { error ->
                    mutableState.value = ConnectionState.Failed(
                        userMessage = error.message ?: "Secure connection approval failed.",
                        recoverable = true
                    )
                }
        } else {
            client.rejectConnection(endpointId)
            mutableState.value = ConnectionState.Idle
        }
        pendingEndpointId = null
    }

    fun stop() {
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        pendingEndpointId = null
        pendingPeer = null
        if (mutableState.value !is ConnectionState.Failed) {
            mutableState.value = ConnectionState.Idle
        }
    }
}
