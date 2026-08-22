package com.kenan.optishare.v4.transport

enum class TransportKind {
    NEARBY_CONNECTIONS,
    WIFI_AWARE,
    WIFI_DIRECT
}

data class NearbyPeer(
    val id: String,
    val displayName: String,
    val transport: TransportKind
)

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Preparing(val message: String) : ConnectionState
    data class Advertising(val message: String) : ConnectionState
    data class Searching(val message: String) : ConnectionState
    data class PeersFound(val peers: List<NearbyPeer>) : ConnectionState
    data class Connecting(val peer: NearbyPeer, val attempt: Int) : ConnectionState
    data class VerificationRequired(
        val peer: NearbyPeer,
        val digits: String
    ) : ConnectionState
    data class Connected(val peer: NearbyPeer) : ConnectionState
    data class Retrying(val message: String, val attempt: Int) : ConnectionState
    data class Failed(val userMessage: String, val recoverable: Boolean) : ConnectionState
}
