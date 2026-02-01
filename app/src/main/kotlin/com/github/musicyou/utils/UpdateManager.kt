package com.github.musicyou.utils

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * Manages app version checks and force update logic
 */
class UpdateManager(private val context: Context) {
    
    private val firestore = FirebaseFirestore.getInstance()
    private val TAG = "UpdateManager"
    
    /**
     * Check if app needs to update by comparing with Firebase config
     * @return UpdateStatus indicating if update is required
     */
    suspend fun checkForUpdate(): Pair<UpdateStatus, VersionConfig?> {
        try {
            // Get current app version
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0.0.0"
            
            Log.d(TAG, "Current app version: $currentVersion")
            
            // Fetch version config from Firebase
            val config = fetchVersionConfig() ?: run {
                Log.w(TAG, "No version config found in Firebase, skipping update check")
                return UpdateStatus.OK to null
            }
            
            Log.d(TAG, "Firebase config: min=${config.minimumRequiredVersion}, recommended=${config.recommendedVersion}, force=${config.forceUpdateEnabled}")
            
            // Determine update status
            val status = when {
                // Force update if enabled and version is below minimum
                config.forceUpdateEnabled && 
                VersionCheck.isVersionOlder(currentVersion, config.minimumRequiredVersion) -> {
                    Log.i(TAG, "FORCE UPDATE REQUIRED: $currentVersion < ${config.minimumRequiredVersion}")
                    UpdateStatus.FORCE_REQUIRED
                }
                
                // Recommended update if below recommended version
                VersionCheck.isVersionOlder(currentVersion, config.recommendedVersion) -> {
                    Log.i(TAG, "Recommended update: $currentVersion < ${config.recommendedVersion}")
                    UpdateStatus.RECOMMENDED
                }
                
                // Optional update if new version available
                VersionCheck.isVersionOlder(currentVersion, config.currentVersion) -> {
                    Log.i(TAG, "Optional update: $currentVersion < ${config.currentVersion}")
                    UpdateStatus.OPTIONAL
                }
                
                else -> {
                    Log.d(TAG, "App is up to date")
                    UpdateStatus.OK
                }
            }
            
            return status to config
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update: ${e.message}", e)
            // On error, don't block the app
            return UpdateStatus.OK to null
        }
    }
    
    /**
     * Fetch version configuration from Firestore
     */
    private suspend fun fetchVersionConfig(): VersionConfig? {
        return try {
            val doc = firestore.collection("app_config")
                .document("version_control")
                .get()
                .await()
            
            if (!doc.exists()) {
                Log.w(TAG, "version_control document does not exist in Firestore")
                return null
            }
            
            doc.toObject(VersionConfig::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching version config: ${e.message}", e)
            null
        }
    }
}
