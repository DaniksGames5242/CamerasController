package com.mycamerascontroller.agent

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.ChildEventListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.net.HttpURLConnection
import java.net.URL

// Kotlin mirror of packages/shared/src/{types,signaling}.ts — same RTDB schema,
// same "room" concept, so agents and clients on any platform interoperate.

// @JvmOverloads is required so a genuine zero-arg constructor exists for
// Firebase's reflection-based deserializer — a Kotlin all-defaults
// constructor alone does not produce one.
data class DeviceRecord @JvmOverloads constructor(
    var name: String = "",
    var platform: String = "android",
    var status: String = "online",
    var lastSeen: Long = 0L,
)

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
    private var signedIn = false

    /** Older than this and an unanswered call node is presumed abandoned. */
    private const val STALE_CALL_MS = 30_000L

    fun init(context: android.content.Context, onReady: () -> Unit) {
        val cfg = Network.load(context) ?: return
        Network.ensureFirebase(context, cfg)
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            signedIn = true
            onReady()
            return
        }
        auth.signInAnonymously().addOnSuccessListener {
            signedIn = true
            onReady()
        }
    }

    private fun roomRef(path: String) = db.getReference("rooms/${Network.current!!.networkId}/$path")

    fun registerDevice(deviceId: String, name: String, platform: String = "android") {
        val ref = roomRef("devices/$deviceId")
        val record = DeviceRecord(name = name, platform = platform, status = "online", lastSeen = System.currentTimeMillis())
        ref.setValue(record)
        ref.onDisconnect().updateChildren(mapOf("status" to "offline"))
    }

    fun heartbeat(deviceId: String) {
        roomRef("devices/$deviceId").updateChildren(
            mapOf("status" to "online", "lastSeen" to System.currentTimeMillis())
        )
    }

    /**
     * Fires whenever this device's connection to the Firebase backend
     * changes, `true` right after it (re)establishes. Mobile networks drop
     * and re-open the realtime socket far more often than Wi-Fi — reacting
     * to a reconnect lets the caller push a fresh heartbeat immediately
     * instead of waiting up to the next scheduled tick, which is what made
     * a device that's actually back online keep reading as stale/offline
     * for a while on flaky mobile connections.
     */
    fun onConnectionStateChanged(cb: (connected: Boolean) -> Unit): ValueEventListener {
        val ref = db.getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                cb(snapshot.getValue(Boolean::class.java) ?: false)
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun stopConnectionStateListener(listener: ValueEventListener) {
        db.getReference(".info/connected").removeEventListener(listener)
    }

    /** One-time read of this device's current capture preferences, e.g. right before starting capture. */
    fun getDeviceSettings(deviceId: String, cb: (DeviceSettings) -> Unit) {
        roomRef("deviceSettings/$deviceId").get()
            .addOnSuccessListener { snap -> cb(snap.getValue(DeviceSettings::class.java) ?: DeviceSettings()) }
            .addOnFailureListener { cb(DeviceSettings()) }
    }

    fun setOffline(deviceId: String) {
        roomRef("devices/$deviceId").updateChildren(mapOf("status" to "offline"))
    }

    /**
     * Notifies when this device's own record has been forgotten out from
     * under it (deleted by a client while still running, not just an
     * ordinary disconnect). Only fires after the record has actually been
     * observed to exist, so it can't misfire on the initial empty snapshot
     * before registerDevice's write lands.
     */
    fun onDeviceRemoved(deviceId: String, cb: () -> Unit): ValueEventListener {
        val ref = roomRef("devices/$deviceId")
        var seenExisting = false
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    seenExisting = true
                } else if (seenExisting) {
                    cb()
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun stopDeviceRemovedListener(deviceId: String, listener: ValueEventListener) {
        roomRef("devices/$deviceId").removeEventListener(listener)
    }

    /**
     * Fires once per new incoming call for this device.
     * The call node is created before its offer is written (createCall vs.
     * sendAnswer/sendOffer are separate writes on the caller side), so this
     * can't just react to the child being added — it has to watch each new
     * call node's value until an offer shows up, which may already be there
     * or may land a moment later.
     */
    fun onIncomingCall(deviceId: String, cb: (callId: String, offer: SdpPayload) -> Unit): () -> Unit {
        val callsRef = roomRef("calls/$deviceId")
        val handled = mutableSetOf<String>()
        val perCallListeners = mutableMapOf<String, ValueEventListener>()

        val addedListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val callId = snapshot.key ?: return
                if (perCallListeners.containsKey(callId)) return
                val callValueListener = object : ValueEventListener {
                    override fun onDataChange(snap: DataSnapshot) {
                        if (handled.contains(callId)) return
                        val offer = snap.child("offer").getValue(SdpPayload::class.java) ?: return
                        // A call node this stale is a leftover from a caller
                        // that's long gone (e.g. the app closed without
                        // hanging up) — answering it would occupy the "one
                        // call at a time" slot forever with nothing on the
                        // other end, silently blocking every real call after.
                        val createdAt = snap.child("createdAt").getValue(Long::class.java) ?: 0L
                        if (System.currentTimeMillis() - createdAt > STALE_CALL_MS) {
                            handled.add(callId)
                            roomRef("calls/$deviceId/$callId").removeValue()
                            return
                        }
                        handled.add(callId)
                        cb(callId, offer)
                    }
                    override fun onCancelled(error: DatabaseError) {}
                }
                perCallListeners[callId] = callValueListener
                roomRef("calls/$deviceId/$callId").addValueEventListener(callValueListener)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        callsRef.addChildEventListener(addedListener)

        return {
            callsRef.removeEventListener(addedListener)
            perCallListeners.forEach { (callId, listener) ->
                roomRef("calls/$deviceId/$callId").removeEventListener(listener)
            }
        }
    }

    fun sendAnswer(targetDeviceId: String, callId: String, answer: SdpPayload) {
        roomRef("calls/$targetDeviceId/$callId").updateChildren(
            mapOf("answer" to answer, "status" to "accepted")
        )
    }

    fun sendIceCandidate(targetDeviceId: String, callId: String, role: String, candidate: IceCandidatePayload) {
        roomRef("calls/$targetDeviceId/$callId/candidates/$role").push().setValue(candidate)
    }

    fun onRemoteIceCandidates(
        targetDeviceId: String,
        callId: String,
        remoteRole: String,
        cb: (IceCandidatePayload) -> Unit,
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

    fun stopIceListener(targetDeviceId: String, callId: String, remoteRole: String, listener: ChildEventListener) {
        roomRef("calls/$targetDeviceId/$callId/candidates/$remoteRole").removeEventListener(listener)
    }

    /** Fires when the client hangs up (deletes the call node). */
    fun onCallEnded(targetDeviceId: String, callId: String, cb: () -> Unit): ValueEventListener {
        val ref = roomRef("calls/$targetDeviceId/$callId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) cb()
            }
            override fun onCancelled(error: DatabaseError) {}
        }
        ref.addValueEventListener(listener)
        return listener
    }

    fun stopCallEndedListener(targetDeviceId: String, callId: String, listener: ValueEventListener) {
        roomRef("calls/$targetDeviceId/$callId").removeEventListener(listener)
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
