package com.github.musicyou.utils

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.github.musicyou.models.Song

object DeviceMediaManager {
    fun getDeviceMedia(context: Context): List<Song> {
        val songs = mutableListOf<Song>()

        // 1. Query Audio
        val audioCollection = if (isAtLeastAndroid13) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val audioProjection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID
        )

        try {
            context.contentResolver.query(
                audioCollection,
                audioProjection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0",
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)
                    val artist = cursor.getString(artistColumn)
                    val duration = cursor.getLong(durationColumn)
                    val albumId = cursor.getLong(albumIdColumn)

                    val contentUri = ContentUris.withAppendedId(audioCollection, id)
                    val artworkUri = ContentUris.withAppendedId(
                        Uri.parse("content://media/external/audio/albumart"),
                        albumId
                    )

                    songs.add(
                        Song(
                            id = contentUri.toString(),
                            title = title,
                            artistsText = if (artist == "<unknown>") "Unknown Artist" else artist,
                            durationText = formatAsDuration(duration),
                            thumbnailUrl = artworkUri.toString(),
                            likedAt = null // Local files aren't "liked" in DB by default
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Query Video (Optional per request "VIDEO / AUDIO")
        val videoCollection = if (isAtLeastAndroid13) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val videoProjection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION
        )

        try {
            context.contentResolver.query(
                videoCollection,
                videoProjection,
                null,
                null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val title = cursor.getString(titleColumn)
                    val duration = cursor.getLong(durationColumn)
                    
                    val contentUri = ContentUris.withAppendedId(videoCollection, id)
                    // Video thumbnails can be queried but simplistic approach:
                    // Use a placeholder or load via Glide/Coil with uri directly

                    songs.add(
                        Song(
                            id = contentUri.toString(),
                            title = title,
                            artistsText = "Video", 
                            durationText = formatAsDuration(duration),
                            thumbnailUrl = null // Coil loads video thumb from contentUri usually
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return songs.sortedBy { it.title }
    }
    
    /**
     * Finds a local file that matches the remote fingerprint.
     * Strategy:
     * 1. Query for items with similar Duration (±1000ms).
     * 2. Filter by Name (fuzzy match).
     */
    fun findMatchingMedia(context: Context, fingerprint: com.github.musicyou.sync.session.MediaFingerprint): Uri? {
        val targetDuration = fingerprint.durationMs
        val targetTitle = fingerprint.title.lowercase().trim()
        val durationDelta = 1000L // 1 second tolerance

        android.util.Log.d("MusicSyncFlow", "DeviceMediaManager: Matching media - Title='$targetTitle', Dur=$targetDuration ms")

        // Permission Check
        val hasPermission = if (isAtLeastAndroid13) {
             androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VIDEO) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
             androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
             androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        
        if (!hasPermission) {
            android.util.Log.e("MusicSyncFlow", "DeviceMediaManager: Missing permissions to query MediaStore")
            return null
        }

        // Helper: strip file extension and normalize separators for fuzzy comparison
        fun normalizeForMatch(input: String): String {
            return input.replace(Regex("\\.(mp4|mkv|avi|mov|webm|flv|mp3|m4a|ogg|wav|aac|flac)$"), "")
                        .replace(Regex("[_\\-.]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
        }
        
        // Extract meaningful words (3+ chars) for overlap matching
        fun extractWords(input: String): Set<String> {
            return normalizeForMatch(input).split(" ")
                .filter { it.length >= 3 }
                .toSet()
        }
        
        val targetNormalized = normalizeForMatch(targetTitle)
        val targetWords = extractWords(targetTitle)
        
        // Helper to check match
        fun checkCursor(cursor: android.database.Cursor, uriPrefix: Uri, type: String): Uri? {
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.TITLE)
            // displayName might be better for file matching
            val displayCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            
            var count = 0
            var bestMatch: Pair<Long, Int>? = null // (id, matchCount)
            
            while (cursor.moveToNext()) {
                count++
                val id = cursor.getLong(idCol)
                val title = cursor.getString(titleCol)?.lowercase()?.trim() ?: ""
                val displayName = if (displayCol != -1) cursor.getString(displayCol)?.lowercase()?.trim() ?: "" else ""
                
                val titleNorm = normalizeForMatch(title)
                val displayNorm = normalizeForMatch(displayName)
                
                // Level 1: Exact match (after normalization)
                val isExactMatch = titleNorm == targetNormalized || displayNorm == targetNormalized
                
                // Level 2: Contains match (one contains the other, after normalization)
                val isContainsMatch = titleNorm.contains(targetNormalized) || 
                                       targetNormalized.contains(titleNorm) ||
                                       displayNorm.contains(targetNormalized) ||
                                       targetNormalized.contains(displayNorm)
                
                if (isExactMatch || isContainsMatch) {
                   android.util.Log.d("MusicSyncFlow", "DeviceMediaManager: MATCH FOUND ($type)! ID=$id, Title='$title', Display='$displayName'")
                   return ContentUris.withAppendedId(uriPrefix, id)
                }
                
                // Level 3: Word overlap (for renamed files, since duration already matches)
                if (targetWords.isNotEmpty()) {
                    val candidateWords = extractWords(title) + extractWords(displayName)
                    val overlap = targetWords.intersect(candidateWords).size
                    if (overlap > 0 && (bestMatch == null || overlap > bestMatch!!.second)) {
                        bestMatch = Pair(id, overlap)
                        android.util.Log.d("MusicSyncFlow", "DeviceMediaManager: Word overlap ($type) ID=$id, overlap=$overlap/${targetWords.size}, Title='$title'")
                    }
                }
                
                // Log first 5 failures to avoid spam
                if (count <= 5 && bestMatch == null) {
                    android.util.Log.v("MusicSyncFlow", "DeviceMediaManager: No match ($type) - Title='$title', Display='$displayName' vs Target='$targetTitle'")
                }
            }
            
            // Accept word-overlap match if at least half the target words matched (duration already filtered)
            if (bestMatch != null && targetWords.isNotEmpty()) {
                val overlapRatio = bestMatch!!.second.toFloat() / targetWords.size
                android.util.Log.d("MusicSyncFlow", "DeviceMediaManager: Best word-overlap match - ID=${bestMatch!!.first}, ratio=$overlapRatio (${bestMatch!!.second}/${targetWords.size})")
                // Since duration already matches (±1s), even a single meaningful word overlap is enough
                if (overlapRatio >= 0.5f || bestMatch!!.second >= 2) {
                    android.util.Log.d("MusicSyncFlow", "DeviceMediaManager: MATCH FOUND via word overlap ($type)! ID=${bestMatch!!.first}")
                    return ContentUris.withAppendedId(uriPrefix, bestMatch!!.first)
                }
            }
            
            android.util.Log.d("MusicSyncFlow", "DeviceMediaManager: Scanned $count $type items, no match found.")
            return null
        }

        // 1. Search Video
        try {
            val videoUri = if (isAtLeastAndroid13) {
                 MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                 MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.TITLE,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION
            )
            
            // Log query params
            android.util.Log.d("MusicSyncFlow", "DeviceMediaManager: Querying Video for duration ${targetDuration - durationDelta}..${targetDuration + durationDelta}")

            val selection = "${MediaStore.Video.Media.DURATION} BETWEEN ? AND ?"
            val args = arrayOf((targetDuration - durationDelta).toString(), (targetDuration + durationDelta).toString())
            
            context.contentResolver.query(videoUri, projection, selection, args, null)?.use {
                val match = checkCursor(it, videoUri, "Video")
                if (match != null) return match
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicSyncFlow", "DeviceMediaManager: Video query failed", e)
        }
        
        // 2. Search Audio
        try {
            val audioUri = if (isAtLeastAndroid13) {
                 MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            } else {
                 MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DURATION
            )
            
             android.util.Log.d("MusicSyncFlow", "DeviceMediaManager: Querying Audio for duration ${targetDuration - durationDelta}..${targetDuration + durationDelta}")
            
            val selection = "${MediaStore.Audio.Media.DURATION} BETWEEN ? AND ?"
            val args = arrayOf((targetDuration - durationDelta).toString(), (targetDuration + durationDelta).toString())
            
            context.contentResolver.query(audioUri, projection, selection, args, null)?.use {
                 val match = checkCursor(it, audioUri, "Audio")
                 if (match != null) return match
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicSyncFlow", "DeviceMediaManager: Audio query failed", e)
        }

        android.util.Log.w("MusicSyncFlow", "DeviceMediaManager: FINAL - No local match found for '$targetTitle'")
        return null
    }

    /**
     * Extracts fingerprint metadata from a specific URI (Host side).
     */
    fun getFingerprintFromUri(context: Context, uriString: String): com.github.musicyou.sync.session.MediaFingerprint? {
        try {
            val uri = Uri.parse(uriString)
            val projection = arrayOf(
                android.provider.OpenableColumns.DISPLAY_NAME,
                android.provider.OpenableColumns.SIZE
                // Note: Duration isn't always in OpenableColumns, need MediaStore columns if it's a content://media uri
            )
            
            // Try standard query first
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameCol = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    
                    val name = if (nameCol != -1) cursor.getString(nameCol) else uri.lastPathSegment ?: "Unknown"
                    val size = if (sizeCol != -1) cursor.getLong(sizeCol) else 0L
                    
                    // Duration is tricky. If it's a media store URI, we can query it.
                    // Or we can use MediaMetadataRetriever (slower but reliable).
                    // For "Production Level", MediaStore query is faster if applicable.
                    
                    var duration = 0L
                    var mimeType: String? = context.contentResolver.getType(uri)
                    
                    // Try to get Duration
                    val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                    if (durationCol != -1) {
                         duration = cursor.getLong(durationCol)
                    }

                    if (duration == 0L) {
                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            retriever.setDataSource(context, uri)
                            val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                            duration = durStr?.toLongOrNull() ?: 0L
                            retriever.release()
                        } catch (e: Exception) {
                            // ignore
                        }
                    }
                    
                    return com.github.musicyou.sync.session.MediaFingerprint(
                        title = name,
                        durationMs = duration,
                        sizeBytes = size,
                        mimeType = mimeType
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
