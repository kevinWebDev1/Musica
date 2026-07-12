package com.github.musicyou.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.FastForward
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.musicyou.LocalPlayerPadding
import com.github.musicyou.LocalPlayerServiceBinder
import com.github.musicyou.R
import com.github.musicyou.utils.isAtLeastAndroid6
import com.github.musicyou.utils.persistentQueueKey
import com.github.musicyou.utils.rememberPreference
import com.github.musicyou.utils.resumePlaybackWhenDeviceConnectedKey
import com.github.musicyou.utils.skipSilenceKey
import com.github.musicyou.utils.toast
import com.github.musicyou.utils.volumeNormalizationKey

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@ExperimentalAnimationApi
@Composable
fun PlayerSettings() {
    val context = LocalContext.current
    val binder = LocalPlayerServiceBinder.current
    val playerPadding = LocalPlayerPadding.current

    var persistentQueue by rememberPreference(persistentQueueKey, false)
    var resumePlaybackWhenDeviceConnected by rememberPreference(
        resumePlaybackWhenDeviceConnectedKey,
        false
    )
    var skipSilence by rememberPreference(skipSilenceKey, false)
    var volumeNormalization by rememberPreference(volumeNormalizationKey, false)
    val activityResultLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp + playerPadding)
    ) {
        SwitchSettingEntry(
            title = stringResource(id = R.string.persistent_queue),
            text = stringResource(id = R.string.persistent_queue_description),
            icon = Icons.AutoMirrored.Outlined.QueueMusic,
            isChecked = persistentQueue,
            onCheckedChange = {
                persistentQueue = it
            }
        )

        if (isAtLeastAndroid6) {
            SwitchSettingEntry(
                title = stringResource(id = R.string.resume_playback),
                text = stringResource(id = R.string.resume_playback_description),
                icon = Icons.Outlined.Replay,
                isChecked = resumePlaybackWhenDeviceConnected,
                onCheckedChange = {
                    resumePlaybackWhenDeviceConnected = it
                }
            )
        }

        SwitchSettingEntry(
            title = stringResource(id = R.string.skip_silence),
            text = stringResource(id = R.string.skip_silence_description),
            icon = Icons.Outlined.FastForward,
            isChecked = skipSilence,
            onCheckedChange = {
                skipSilence = it
            }
        )

        SwitchSettingEntry(
            title = stringResource(id = R.string.loudness_normalization),
            text = stringResource(id = R.string.loudness_normalization_description),
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
            isChecked = volumeNormalization,
            onCheckedChange = {
                volumeNormalization = it
            }
        )

        SettingsEntry(
            title = stringResource(id = R.string.equalizer),
            text = stringResource(id = R.string.equalizer_description),
            icon = Icons.Outlined.Equalizer,
            onClick = {
                val intent = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL).apply {
                    putExtra(AudioEffect.EXTRA_AUDIO_SESSION, binder?.player?.audioSessionId)
                    putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                    putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                }

                try {
                    activityResultLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    context.toast("Couldn't find an application to equalize audio")
                }
            }
        )

        // Video Settings
        var autoExpandYouTubeVideo by rememberPreference(com.github.musicyou.utils.autoExpandYouTubeVideoKey, true)
        
        SwitchSettingEntry(
            title = "Auto-expand YouTube Videos",
            text = "Automatically open the fullscreen player when a YouTube video starts.",
            icon = Icons.Default.PlayArrow,
            isChecked = autoExpandYouTubeVideo,
            onCheckedChange = {
                autoExpandYouTubeVideo = it
            }
        )

        var videoQuality by rememberPreference(com.github.musicyou.utils.videoQualityKey, com.github.musicyou.enums.VideoQuality.AUTO)
        var defaultFullscreenOrientation by rememberPreference(com.github.musicyou.utils.defaultFullscreenOrientationKey, 0)
        var videoResizeMode by rememberPreference(com.github.musicyou.utils.videoResizeModeKey, 4)
        var doubleTapSeekDuration by rememberPreference(com.github.musicyou.utils.doubleTapSeekDurationKey, 10)
        var scrubSeekIntensity by rememberPreference(com.github.musicyou.utils.scrubSeekIntensityKey, 1.0f)
        var fastForwardSpeedTop by rememberPreference(com.github.musicyou.utils.fastForwardSpeedTopKey, 3.0f)
        var fastForwardSpeedMid by rememberPreference(com.github.musicyou.utils.fastForwardSpeedMidKey, 2.0f)
        var fastForwardSpeedBot by rememberPreference(com.github.musicyou.utils.fastForwardSpeedBotKey, 1.5f)

        ValueSelectorSettingsEntry(
            title = "Video Quality",
            selectedValue = videoQuality,
            values = com.github.musicyou.enums.VideoQuality.values().toList(),
            onValueSelected = { videoQuality = it },
            icon = Icons.Default.PlayArrow,
            valueText = {
                when (it) {
                    com.github.musicyou.enums.VideoQuality.AUTO -> "Auto"
                    com.github.musicyou.enums.VideoQuality.QUALITY_360P -> "360p"
                    com.github.musicyou.enums.VideoQuality.QUALITY_720P -> "720p"
                    com.github.musicyou.enums.VideoQuality.QUALITY_1080P -> "1080p"
                    com.github.musicyou.enums.VideoQuality.QUALITY_1440P -> "1440p"
                    com.github.musicyou.enums.VideoQuality.QUALITY_2160P -> "2160p"
                }
            }
        )

        ValueSelectorSettingsEntry(
            title = "Default Fullscreen Orientation",
            selectedValue = defaultFullscreenOrientation,
            values = listOf(0, 1, 2), // 0: Auto (Sensor), 1: Horizontal (Landscape), 2: Vertical (Portrait)
            onValueSelected = { defaultFullscreenOrientation = it },
            icon = Icons.Default.PlayArrow,
            valueText = {
                when (it) {
                    0 -> "Auto"
                    1 -> "Horizontal"
                    2 -> "Vertical"
                    else -> "Unknown"
                }
            }
        )

        ValueSelectorSettingsEntry(
            title = "Default Video Resize Mode",
            selectedValue = videoResizeMode,
            values = listOf(0, 3, 4), // 0: Fit, 3: Fill, 4: Zoom
            onValueSelected = { videoResizeMode = it },
            icon = Icons.Default.PlayArrow,
            valueText = {
                when (it) {
                    0 -> "Fit"
                    3 -> "Fill"
                    4 -> "Zoom"
                    else -> "Unknown"
                }
            }
        )

        ValueSelectorSettingsEntry(
            title = "Double Tap Seek Duration",
            selectedValue = doubleTapSeekDuration,
            values = listOf(5, 10, 15, 30),
            onValueSelected = { doubleTapSeekDuration = it },
            icon = Icons.Default.FastForward,
            valueText = { "$it seconds" }
        )

        ValueSelectorSettingsEntry(
            title = "Scrub Seek Intensity",
            selectedValue = scrubSeekIntensity,
            values = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f),
            onValueSelected = { scrubSeekIntensity = it },
            icon = Icons.Default.FastForward,
            valueText = { "${it}x" }
        )

        ValueSelectorSettingsEntry(
            title = "Fast Forward Speed (Top Screen)",
            selectedValue = fastForwardSpeedTop,
            values = listOf(2.0f, 2.5f, 3.0f, 4.0f),
            onValueSelected = { fastForwardSpeedTop = it },
            icon = Icons.Default.KeyboardArrowUp,
            valueText = { "${it}x" }
        )
        
        ValueSelectorSettingsEntry(
            title = "Fast Forward Speed (Mid Screen)",
            selectedValue = fastForwardSpeedMid,
            values = listOf(1.5f, 1.75f, 2.0f, 2.5f),
            onValueSelected = { fastForwardSpeedMid = it },
            icon = Icons.Default.KeyboardArrowRight,
            valueText = { "${it}x" }
        )
        
        ValueSelectorSettingsEntry(
            title = "Fast Forward Speed (Bottom Screen)",
            selectedValue = fastForwardSpeedBot,
            values = listOf(1.25f, 1.5f, 1.75f, 2.0f),
            onValueSelected = { fastForwardSpeedBot = it },
            icon = Icons.Default.KeyboardArrowDown,
            valueText = { "${it}x" }
        )
    }
}
