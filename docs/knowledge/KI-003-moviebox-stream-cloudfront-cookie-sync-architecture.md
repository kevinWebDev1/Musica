# Knowledge Item (KI-003): MovieBox Stream CloudFront Cookie & Watch Party Sync Architecture

## 1. Problem Summary
When streaming protected direct video streams (such as MovieBox DASH `index_web.mpd` served from CloudFront CDN `sacdn.hakunaymatata.com`), requests without CloudFront signed cookies (`CloudFront-Policy`, `CloudFront-Signature`, `CloudFront-Key-Pair-Id`) fail immediately with `403 Forbidden`.

Furthermore, in a real-time Watch Party (Universal Sync), participants only receive the stream URI (`mediaId`). If CloudFront cookies are not synchronized over the WebRTC protocol, guests fail to open the stream even if the host plays it successfully.

## 2. Ingestion & Player Architecture

### A. Intent Ingestion (`MainActivity.kt`)
External launcher apps (or modded buttons like `musica_btn`) pass stream credentials through intent extras:
- `intent.getStringExtra("cookie")` / `intent.getStringExtra("Cookie")`
- `intent.getStringArrayExtra("headers")` (`["Cookie", "<cookie-value>"]`)
- `intent.getStringExtra("userAgent")` / `intent.getStringExtra("User-Agent")` (optional)

In `LaunchedEffect(data)`, before loading the track into the playback engine:
```kotlin
PlayerService.currentStreamCookie = streamCookie
PlayerService.currentStreamUserAgent = streamUserAgent
currentBinder.syncLoadMediaItem(mediaItem, 0L, autoPlay = true)
```

### B. ExoPlayer Header Injection (`PlayerService.kt`)
CloudFront cookies must be applied at two layers in ExoPlayer:
1. **`DefaultHttpDataSource.Factory`**: Configured via `setDefaultRequestProperties(props)` dynamically when `currentStreamCookie` changes.
2. **`ResolvingDataSource.Factory`**: Intercepts `isDirectStream` (HTTP/HTTPS non-YouTube streams) and explicitly injects the `Cookie` header into `dataSpec.httpRequestHeaders`:
```kotlin
if (!currentStreamCookie.isNullOrBlank()) {
    val headers = HashMap(dataSpec.httpRequestHeaders)
    headers["Cookie"] = currentStreamCookie!!
    headers["User-Agent"] = currentStreamUserAgent ?: DEFAULT_USER_AGENT
    return@Factory dataSpec.buildUpon().setHttpRequestHeaders(headers).build()
}
```

### C. WebRTC Synchronization Layer
To enable watch parties for CloudFront streams, credentials propagate across the sync protocol:
- **`SyncEvent.kt`**: `PlayEvent` and `StateSyncEvent` contain `streamCookie: String?` and `streamUserAgent: String?`.
- **`SyncEventSerializer.kt`**: Serializes and deserializes `streamCookie` and `streamUserAgent` in JSON payloads.
- **`SessionState.kt`**: Holds `streamCookie` and `streamUserAgent`.
- **`SessionManager.kt`**: 
  - On track change (Host), reads `currentCookie = PlayerService.currentStreamCookie` and broadcasts it in `PlayEvent`.
  - In `processEvent(PlayEvent)` and `applyAuthoritativeSnapshot` (Participant), updates `SessionState` and immediately sets `PlayerService.currentStreamCookie = state.streamCookie`.

## 3. Gotchas & Debugging
1. **Null Cookie in `[INTENT-MEDIA]`:**
   If ExoPlayer logs `HttpDataSource$InvalidResponseCodeException: Response code: 403`, check logcat for `[INTENT-MEDIA]`. If `cookie=null`, the sender did not attach `intent.putExtra("cookie", ...)`.
2. **Sender Smali Hook:**
   In MovieBox (`com.community.newroom`), `ao/e.smali` (`q(Map)` method) captures the `signCookie` into `MusicaSync.sCurrentCookie`. `MusicaSync$1.onClick` must attach:
   ```java
   intent.putExtra("cookie", sCurrentCookie);
   intent.putExtra("Cookie", sCurrentCookie);
   intent.putExtra("headers", new String[] { "Cookie", sCurrentCookie });
   ```
