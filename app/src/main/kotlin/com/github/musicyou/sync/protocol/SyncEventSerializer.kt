package com.github.musicyou.sync.protocol

import com.github.musicyou.sync.session.SessionState
import org.json.JSONObject

/**
 * Manually serializes SyncEvent objects to/from JSON Strings/Bytes.
 * Using manual JSON to avoid adding Gson/Moshi dependency if not already present.
 */
object SyncEventSerializer {

    fun toByteArray(event: SyncEvent): ByteArray {
        val json = JSONObject()
        json.put("timestamp", event.timestamp)

        when (event) {
            is PlayEvent -> {
                json.put("type", "PLAY")
                json.put("mediaId", event.mediaId)
                json.put("startPos", event.startPos)
                json.put("playbackSpeed", event.playbackSpeed)
                event.title?.let { json.put("title", it) }
                event.artist?.let { json.put("artist", it) }
                event.thumbnailUrl?.let { json.put("thumbnailUrl", it) }
                event.requesterName?.let { json.put("requesterName", it) }
                event.requesterAvatar?.let { json.put("requesterAvatar", it) }
            }
            is PauseEvent -> {
                json.put("type", "PAUSE")
                json.put("pos", event.pos)
                event.requesterName?.let { json.put("requesterName", it) }
            }
            is SeekEvent -> {
                json.put("type", "SEEK")
                json.put("pos", event.pos)
                event.requesterName?.let { json.put("requesterName", it) }
            }
            is RequestStateEvent -> {
                json.put("type", "REQUEST_STATE")
                event.senderName?.let { json.put("senderName", it) }
                event.senderAvatar?.let { json.put("senderAvatar", it) }
                event.senderUid?.let { json.put("senderUid", it) }
            }
            is JoinEvent -> {
                json.put("type", "JOIN")
                json.put("name", event.name)
                event.avatar?.let { json.put("avatar", it) }
                event.uid?.let { json.put("uid", it) }
            }
            is StateSyncEvent -> {
                json.put("type", "STATE_SYNC")
                val stateJson = JSONObject()
                stateJson.put("sessionId", event.state.sessionId)
                event.state.hostUid?.let { stateJson.put("hostUid", it) }
                stateJson.put("isHost", event.state.isHost)
                // Connected Peers is a list
                // stateJson.put("connectedPeers", ... ) // Simplified for now
                stateJson.put("gameId", "") // ignore
                
                stateJson.put("currentMediaId", event.state.currentMediaId)
                stateJson.put("positionAtAnchor", event.state.positionAtAnchor)
                stateJson.put("playbackSpeed", event.state.playbackSpeed)
                stateJson.put("playbackStatus", event.state.playbackStatus.name)
                stateJson.put("trackStartGlobalTime", event.state.trackStartGlobalTime)
                stateJson.put("hostOnlyMode", event.state.hostOnlyMode)
                stateJson.put("stateVersion", event.state.stateVersion)
                stateJson.put("syncStatus", event.state.syncStatus.name)
                event.state.clockSyncMessage?.let { stateJson.put("clockSyncMessage", it) }
                
                val namesJson = JSONObject()
                event.state.connectedPeerNames.forEach { (key, value) ->
                    namesJson.put(key, value)
                }
                stateJson.put("connectedPeerNames", namesJson)
                
                // Serialize connected peer avatars
                val avatarsJson = JSONObject()
                event.state.connectedPeerAvatars.forEach { (key, value) ->
                    avatarsJson.put(key, value ?: "")
                }
                stateJson.put("connectedPeerAvatars", avatarsJson)
                
                // Serialize connected peer UIDs
                val uidsJson = JSONObject()
                event.state.connectedPeerUids.forEach { (key, value) ->
                    uidsJson.put(key, value ?: "")
                }
                stateJson.put("connectedPeerUids", uidsJson)
                
                // Metadata
                event.state.title?.let { stateJson.put("title", it) }
                event.state.artist?.let { stateJson.put("artist", it) }
                event.state.thumbnailUrl?.let { stateJson.put("thumbnailUrl", it) }
                
                json.put("state", stateJson)
            }
            is PingEvent -> {
                json.put("type", "PING")
                json.put("id", event.id)
                json.put("clientTimestamp", event.clientTimestamp)
            }
            is PongEvent -> {
                json.put("type", "PONG")
                json.put("id", event.id)
                json.put("clientTimestamp", event.clientTimestamp)
                json.put("serverTimestamp", event.serverTimestamp)
                json.put("serverReplyTimestamp", event.serverReplyTimestamp)
            }
        }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    fun fromByteArray(bytes: ByteArray): SyncEvent? {
        try {
            val str = String(bytes, Charsets.UTF_8)
            val json = JSONObject(str)
            val type = json.getString("type")
            val timestamp = json.getLong("timestamp")

            return when (type) {
                "PLAY" -> PlayEvent(
                    mediaId = json.getString("mediaId"),
                    startPos = json.getLong("startPos"),
                    timestamp = timestamp,
                    playbackSpeed = json.optDouble("playbackSpeed", 1.0).toFloat(),
                    title = json.optString("title", null).takeIf { it?.isNotEmpty() == true },
                    artist = json.optString("artist", null).takeIf { it?.isNotEmpty() == true },
                    thumbnailUrl = json.optString("thumbnailUrl", null).takeIf { it?.isNotEmpty() == true },
                    requesterName = json.optString("requesterName", null).takeIf { it?.isNotEmpty() == true },
                    requesterAvatar = json.optString("requesterAvatar", null).takeIf { it?.isNotEmpty() == true }
                )
                "PAUSE" -> PauseEvent(
                    pos = json.getLong("pos"),
                    timestamp = timestamp,
                    requesterName = json.optString("requesterName", null).takeIf { it?.isNotEmpty() == true }
                )
                "SEEK" -> SeekEvent(
                    pos = json.getLong("pos"),
                    timestamp = timestamp,
                    requesterName = json.optString("requesterName", null).takeIf { it?.isNotEmpty() == true }
                )
                "REQUEST_STATE" -> RequestStateEvent(
                    timestamp = timestamp,
                    senderName = json.optString("senderName", null).takeIf { it?.isNotEmpty() == true },
                    senderAvatar = json.optString("senderAvatar", null).takeIf { it?.isNotEmpty() == true },
                    senderUid = json.optString("senderUid", null).takeIf { it?.isNotEmpty() == true }
                )
                "JOIN" -> JoinEvent(
                    name = json.getString("name"),
                    avatar = json.optString("avatar", null).takeIf { it?.isNotEmpty() == true },
                    uid = json.optString("uid", null).takeIf { it?.isNotEmpty() == true },
                    timestamp = timestamp
                )
                "STATE_SYNC" -> {
                    val stateJson = json.getJSONObject("state")
                    val statusStr = stateJson.getString("playbackStatus")
                    val status = try { SessionState.Status.valueOf(statusStr) } catch (e: Exception) { SessionState.Status.PAUSED }
                    
                    // Deserialize connected peer names
                    val connectedNamesMap = mutableMapOf<String, String>()
                    val namesJson = stateJson.optJSONObject("connectedPeerNames")
                    namesJson?.keys()?.forEach { key ->
                        namesJson.optString(key)?.let { name ->
                            connectedNamesMap[key] = name
                        }
                    }
                    
                    // Deserialize connected peer avatars
                    val connectedAvatarsMap = mutableMapOf<String, String?>()
                    val avatarsJson = stateJson.optJSONObject("connectedPeerAvatars")
                    avatarsJson?.keys()?.forEach { key ->
                        val avatar = avatarsJson.optString(key)
                        connectedAvatarsMap[key] = if (avatar.isNullOrEmpty()) null else avatar
                    }
                    
                    // Deserialize connected peer uids
                    val connectedUidsMap = mutableMapOf<String, String?>()
                    val uidsJson = stateJson.optJSONObject("connectedPeerUids")
                    uidsJson?.keys()?.forEach { key ->
                        val uid = uidsJson.optString(key)
                        connectedUidsMap[key] = if (uid.isNullOrEmpty()) null else uid
                    }
                    
                    // Deserialize sync status
                    val syncStatusStr = stateJson.optString("syncStatus", "WAITING")
                    val syncStatus = try { SessionState.SyncStatus.valueOf(syncStatusStr) } catch (e: Exception) { SessionState.SyncStatus.WAITING }
                    
                    val state = SessionState(
                        sessionId = stateJson.optString("sessionId", null),
                        hostUid = stateJson.optString("hostUid", null).takeIf { it?.isNotEmpty() == true },
                        isHost = stateJson.getBoolean("isHost"),
                        connectedPeers = emptySet(), // Rehydrate peers? Not strictly needed for sync logic
                        currentMediaId = stateJson.optString("currentMediaId", null),
                        positionAtAnchor = stateJson.getLong("positionAtAnchor"),
                        playbackSpeed = stateJson.optDouble("playbackSpeed", 1.0).toFloat(),
                        playbackStatus = status,
                        trackStartGlobalTime = stateJson.getLong("trackStartGlobalTime"),
                        title = stateJson.optString("title", null).takeIf { it?.isNotEmpty() == true },
                        artist = stateJson.optString("artist", null).takeIf { it?.isNotEmpty() == true },
                        thumbnailUrl = stateJson.optString("thumbnailUrl", null).takeIf { it?.isNotEmpty() == true },
                        hostOnlyMode = stateJson.optBoolean("hostOnlyMode", false),
                        connectedPeerNames = connectedNamesMap,
                        connectedPeerAvatars = connectedAvatarsMap,
                        connectedPeerUids = connectedUidsMap,
                        stateVersion = stateJson.optLong("stateVersion", 0L),
                        syncStatus = syncStatus,
                        clockSyncMessage = stateJson.optString("clockSyncMessage", null).takeIf { it?.isNotEmpty() == true }
                    )
                    StateSyncEvent(state, timestamp)
                }
                "PING" -> PingEvent(
                    id = json.getString("id"),
                    clientTimestamp = json.getLong("clientTimestamp"),
                    timestamp = timestamp
                )
                "PONG" -> PongEvent(
                    id = json.getString("id"),
                    clientTimestamp = json.getLong("clientTimestamp"),
                    serverTimestamp = json.getLong("serverTimestamp"),
                    serverReplyTimestamp = json.getLong("serverReplyTimestamp"),
                    timestamp = timestamp
                )
                else -> null
            }.also {
                if (it != null) android.util.Log.v("MusicSync", "Deserialized ${it::class.java.simpleName}")
                else android.util.Log.e("MusicSync", "Failed to deserialize type=$type")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            android.util.Log.e("MusicSync", "Deserialize exception", e)
            return null
        }
    }
}
