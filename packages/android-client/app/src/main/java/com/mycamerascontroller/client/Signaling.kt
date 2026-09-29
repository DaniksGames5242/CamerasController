package com.mycamerascontroller.client

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.HttpURLConnection
import java.net.URL

// Mirrors packages/shared/src/{types,signaling}.ts — same RTDB schema and
// "room" concept as the desktop client and every agent.

// @JvmOverloads is required so a genuine zero-arg constructor exists for
// Firebase's reflection-based deserializer — a Kotlin all-defaults
// constructor alone does not produce one.
data class DeviceRecord @JvmOverloads constructor(
    var name: String = "",
    var platform: String = "",
    var status: String = "offline",
    var lastSeen: Long = 0L,
)

data class DeviceWithId(val id: String, val record: DeviceRecord)

/**
 * The heartbeat is every 20s (see the agents' registerDevice). status
 * "online" alone isn't trustworthy: it only flips to "offline" via
 * onDisconnect, which fires when Firebase's server notices the socket
 * dropped — that can lag well behind the agent actually being gone
 * (uninstalled, force-killed, network cut), leaving a device that shows
 * online forever with nothing really there. Treat a stale heartbeat as
 * offline regardless of the stored status.
 */
private const val STALE_ONLINE_MS = 60_000L

fun DeviceRecord.isOnline(): Boolean =
    status == "online" && System.currentTimeMillis() - lastSeen < STALE_ONLINE_MS

data class SdpPayload @JvmOverloads constructor(
    var type: String = "",
    var sdp: String = "",
)

data class IceCandidatePayload @JvmOverloads constructor(
    var candidate: String = "",
    var sdpMid: String? = null,
    var sdpMLineIndex: Int? = null,
)

data class DeviceSettings @JvmOverloads constructor(
    var width: Int? = null,
    var height: Int? = null,
    var frameRate: Int? = null,
)

object Signaling {
    // Not cached: the default FirebaseApp is (re)created from the network
    // config at runtime, see Network.ensureFirebase.
    private val db get() = FirebaseDatabase.getInstance()

    fun init(context: android.content.Context, onReady: () -> Unit) {
        val cfg = Network.load(context) ?: return
        Network.ensureFirebase(context, cfg)
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            onReady()
            return
        }
        auth.signInAnonymously().addOnSuccessListener { onReady() }
    }

    private fun roomRef(path: String) = db.getReference("rooms/${Network.current!!.networkId}/$path")

    fun listenDevices(cb: (List<DeviceWithId>) -> Unit): ValueEventListener {
        val ref = roomRef("devices")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val list = snapshot.children.mapNotNull { child ->
                    val record = child.getValue(DeviceRecord::class.java) ?: return@mapNotNull null
                    val id = child.key ?: return@mapNotNull null
                    DeviceWithId(id, record)
                }
                cb(list)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun stopListeningDevices(listener: ValueEventListener) {
        roomRef("devices").removeEventListener(listener)
    }

    /**
     * Forgets a device record outright — e.g. one left behind by a
     * reinstalled or decommissioned agent. A device that comes back online
     * afterwards just re-registers itself.
     */
    fun forgetDevice(deviceId: String) {
        roomRef("devices/$deviceId").removeValue()
    }

    fun getDeviceSettings(deviceId: String, cb: (DeviceSettings) -> Unit) {
        roomRef("deviceSettings/$deviceId").get()
            .addOnSuccessListener { snap -> cb(snap.getValue(DeviceSettings::class.java) ?: DeviceSettings()) }
            .addOnFailureListener { cb(DeviceSettings()) }
    }

    fun setDeviceSettings(deviceId: String, settings: DeviceSettings) {
        roomRef("deviceSettings/$deviceId").setValue(settings)
    }

    fun createCall(targetDeviceId: String): String {
        val ref = roomRef("calls/$targetDeviceId").push()
        ref.setValue(mapOf("createdAt" to System.currentTimeMillis(), "status" to "pending"))
        return ref.key!!
    }

    fun sendOffer(targetDeviceId: String, callId: String, offer: SdpPayload) {
        roomRef("calls/$targetDeviceId/$callId").updateChildren(mapOf("offer" to offer))
    }

    fun sendIceCandidate(targetDeviceId: String, callId: String, role: String, candidate: IceCandidatePayload) {
        roomRef("calls/$targetDeviceId/$callId/candidates/$role").push().setValue(candidate)
    }

    fun onAnswerSet(targetDeviceId: String, callId: String, cb: (SdpPayload) -> Unit): ValueEventListener {
        val ref = roomRef("calls/$targetDeviceId/$callId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.child("answer").getValue(SdpPayload::class.java)?.let(cb)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun onRemoteIceCandidates(
        targetDeviceId: String, callId: String, remoteRole: String, cb: (IceCandidatePayload) -> Unit,
    ): ChildEventListener {
        val ref = roomRef("calls/$targetDeviceId/$callId/candidates/$remoteRole")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                snapshot.getValue(IceCandidatePayload::class.java)?.let(cb)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addChildEventListener(listener)
        return listener
    }

    fun endCall(targetDeviceId: String, callId: String) {
        roomRef("calls/$targetDeviceId/$callId").removeValue()
    }

    fun removeListeners(targetDeviceId: String, callId: String, answerListener: ValueEventListener, iceListener: ChildEventListener) {
        roomRef("calls/$targetDeviceId/$callId").removeEventListener(answerListener)
        roomRef("calls/$targetDeviceId/$callId/candidates/callee").removeEventListener(iceListener)
    }

    /**
     * ICE servers for a call: [base] (STUN) + the network's own static TURN
     * servers + short-lived credentials from its packages/turn-worker, if one
     * is configured. A slow or unreachable worker never blocks the call for
     * more than a few seconds. Network I/O — calls back off the calling thread.
     */
    fun fetchIceServers(base: List<PeerConnection.IceServer>, cb: (List<PeerConnection.IceServer>) -> Unit) {
        val cfg = Network.current
        val servers = base + (cfg?.turnServers ?: emptyList()).map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val worker = cfg?.turnWorkerUrl
        if (worker == null) { cb(servers); return }
        Thread {
            val fetched = try {
                val url = URL("$worker?room=${cfg.networkId}")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    requestMethod = "GET"
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                parseIceServers(body)
            } catch (e: Exception) {
                emptyList()
            }
            cb(servers + fetched)
        }.start()
    }

    private fun parseIceServers(json: String): List<PeerConnection.IceServer> {
        val entries = JSONObject(json).getJSONArray("iceServers")
        val result = mutableListOf<PeerConnection.IceServer>()
        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val urlsField = entry.get("urls")
            val urls = if (urlsField is JSONArray) {
                (0 until urlsField.length()).map { urlsField.getString(it) }
            } else {
                listOf(urlsField.toString())
            }
            val builder = PeerConnection.IceServer.builder(urls)
            if (entry.has("username")) builder.setUsername(entry.getString("username"))
            if (entry.has("credential")) builder.setPassword(entry.getString("credential"))
            result.add(builder.createIceServer())
        }
        return result
    }
}
