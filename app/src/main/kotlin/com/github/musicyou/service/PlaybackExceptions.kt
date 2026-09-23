package com.github.musicyou.service

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ContentDataSource
import androidx.media3.datasource.FileDataSource
import java.io.FileNotFoundException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayableFormatNotFoundException :
    PlaybackException("Playable format not found", null, ERROR_CODE_REMOTE_ERROR)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class UnplayableException :
    PlaybackException("Unplayable", null, ERROR_CODE_REMOTE_ERROR)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class LoginRequiredException :
    PlaybackException("Login required", null, ERROR_CODE_REMOTE_ERROR)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoIdMismatchException :
    PlaybackException("Video id mismatch", null, ERROR_CODE_REMOTE_ERROR)

@OptIn(UnstableApi::class)
fun isFileNotFoundError(error: Throwable?): Boolean {
    if (error is PlaybackException && error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
        return true
    }
    var current: Throwable? = error
    while (current != null) {
        if (current is FileNotFoundException ||
            current is ContentDataSource.ContentDataSourceException ||
            current is FileDataSource.FileDataSourceException) {
            return true
        }
        current = current.cause
    }
    return false
}