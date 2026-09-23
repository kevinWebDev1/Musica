# Knowledge Item (KI-001): Local Override Sync Architecture & Race Condition Prevention

## Problem
In collaborative media playback (Listen/Watch Together), when a participant deliberately selects a different local file than the host (e.g. host playing Video A, participant manually picking Video B to sync playback timing):
1. Inbound network snapshots (e.g. `PlayEvent` or `StateSyncEvent`) were applied using `_sessionState.update { current -> state.copy(...) }`. Because incoming network events obviously do not contain the guest's local file URI, `state.copy` inadvertently wiped `current.localMatchUri` and `current.localOverride` back to `null` on every single event!
2. Once wiped, the check `if ((!isSameTrack || _sessionState.value.localMatchUri == null) && isPeerLocalUri)` evaluated to true, re-running automatic matching, failing, and loading a dummy item to re-trigger the manual match UI.
3. The participant's `DriftMonitor` compared `currentMediaId` against `state.currentMediaId`, marked `isWrongTrack = true`, cleared `lastSyncedMediaId`, and triggered an infinite loop of snap-backs and manual pick prompts.
4. Loading a manual file triggered ExoPlayer's `onMediaItemTransition`, which caused the participant to echo a `PlayEvent` back to the host, wiping the host's state.

## Solution & Architecture
1. **First-Class `LocalOverrideState` & State Copy Preservation:**
   - Active manual choices are encapsulated in `LocalOverrideState(manualUri, isDeliberateMismatch = true)`.
   - In `applyAuthoritativeSnapshot`, `state.copy(...)` now explicitly preserves `localMatchUri = current.localMatchUri`, `localOverride = current.localOverride`, and `isPendingManualMatch = false` when an active match or override exists.
2. **Snapshot Immunity:**
   When `localOverride != null`, inbound authoritative snapshots:
   - Do NOT wipe the local URI even if `state.mediaFingerprint == null` or mismatched.
   - Do NOT prompt the manual match bottom sheet.
   - Only synchronize play/pause state and playhead position.
3. **Loosened Drift Thresholds:**
   When `localOverride.isDeliberateMismatch == true`, the drift threshold is widened from `300ms` to `3000ms` (`MISMATCHED_DRIFT_THRESHOLD_MS`), and `isWrongTrack` is suppressed in `DriftMonitor`.
4. **Echo Suppression via Last-Synced Tracking:**
   `setManualMatchUri` sets `lastSyncedMediaId` and `lastSyncedTimestamp` to current time before calling `loadTrack`. The `onTrackChanged` callback detects that the change was locally triggered within the echo suppression window and drops outbound broadcasts.
5. **Universal Media Ingestion (`ACTION_VIEW`):**
   `MainActivity` routes external file manager intents and MovieBox remote streams (`.m3u8`, `.mp4`) through `binder.player.forcePlay(mediaItem)`.
