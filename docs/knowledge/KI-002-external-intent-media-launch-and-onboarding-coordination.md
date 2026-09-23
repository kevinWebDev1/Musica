# Knowledge Item (KI-002): External Intent Media Launch & Lifecycle Coordination

## Problem Summary
When opening media files (`.mp4`, `.mp3`) from external apps (File Manager, Gallery, MovieBox stream links), playback either failed to start, crashed, or was immediately wiped back to the Home screen:
1. **Onboarding Auto-Redirect Hijack:**
   In `OnboardingScreen.kt`, when `SMART SKIP` found an existing profile, it executed `delay(200); onComplete()`, but never persisted `putBoolean(onboardedKey, true)` to `SharedPreferences`. Every cold launch forced `startDestination = Routes.Onboarding`. 200ms later, `onComplete()` navigated to `Routes.Home` with `popUpTo(Routes.Onboarding) { inclusive = true }`, wiping any intent-initiated screen.
2. **Destructive Side-Effects in `confirmValueChange`:**
   In `MainActivity.kt`, `confirmValueChange` had `if (value == SheetValue.Hidden) binder?.player?.clearMediaItems()`. Because `DisposableEffect` initially hid the player sheet while the service connected, `confirmValueChange` executed, wiping out ExoPlayer's newly loaded media item 20ms after starting playback.
3. **SessionManager Ghost Disconnect & False Participant Broadcasts:**
   On app launch, the transport layer emitted `sessionId = null`. In `SessionManager`, `if (sessionId == null && !isHost)` executed `pause()` and `seekTo(0)`. In addition, `onTrackChanged()` assumed any non-host was a participant in an active sync session and attempted network broadcasts.
4. **Scaffold Sheet Rejection vs. Navigation Routes:**
   `confirmValueChange` explicitly returned `false` for `SheetValue.Expanded` because fullscreen playback in Musica is managed via `Routes.FullscreenPlayer`. Calling `playerState.expand()` failed silently.
5. **Missing `clipData` & Missing `audio/*` Manifest Filter:**
   Many Android file managers send URIs inside `intent.clipData` instead of `intent.data`. If `intent.data` was null, the intent was discarded. Also, `AndroidManifest.xml` lacked `<data android:mimeType="audio/*" />`.

## Architectural Fixes & Rules

1. **Persistent Onboarding State:**
   - Both `OnboardingScreen` (on `SMART SKIP`) and `ProfileManager.fetchUserProfile` explicitly save `putBoolean(onboardedKey, true)`.
   - In `Navigation.kt`, `Onboarding`'s `onComplete` verifies `navController.currentDestination?.route?.contains("Onboarding") == true` before navigating to `Home`. If an external intent has already navigated to `FullscreenPlayer`, it only pops `Onboarding` without hijacking the screen.

2. **Clean Separation of Compose Sheet Validation & Lifecycle:**
   - `confirmValueChange` must remain a pure validator. Never execute destructive mutations (`clearMediaItems()`) inside it.
   - Media clearing is moved exclusively to explicit user gestures (swiping down the miniplayer or tapping close).
   - `DisposableEffect` guards against calling `playerState.hide()` when `data != null` (active intent being processed).

3. **Active-Session Gated Disconnects in `SessionManager`:**
   - Track `previousSessionId`. Disconnect shutdown (`pause()`, `seekTo(0)`) only fires if `sessionId == null && previousSessionId != null && !isHost`.
   - In `onTrackChanged`: If `_sessionState.value.sessionId == null`, immediately return (standalone playback outside rooms bypasses network sync).

4. **Multi-Source Intent Ingestion & Auto-Navigation:**
   - Parse `intent.data ?: intent.clipData?.getItemAt(0)?.uri ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.toUri()`.
   - If URI/title represents video, attach `extras.putBoolean("forceVideo", true)`.
   - Route playback through `hybridPlaybackEngine.loadMediaItem(...)` (Single Source of Truth).
   - In `LaunchedEffect(data)` and in `engine.playbackState` collector, automatically navigate to `Routes.FullscreenPlayer` for all video formats.
