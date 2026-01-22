package com.github.musicyou.ui.screens.profile

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.musicyou.sync.presence.PresenceManager
import com.github.musicyou.sync.presence.UserPresence
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ProfileViewModel - Aggregates friend presence AND own presence
 * 
 * Provides single StateFlow for all presence data to avoid N RTDB flows
 */
class ProfileViewModel : ViewModel() {
    
    private val TAG = "ProfileViewModel"
    
    // Job to track the collection coroutine
    private var collectionJob: kotlinx.coroutines.Job? = null
    
    // Aggregated FRIENDS presence state
    private val _friendsPresenceMap = MutableStateFlow<Map<String, UserPresence>>(emptyMap())
    val friendsPresenceMap: StateFlow<Map<String, UserPresence>> = _friendsPresenceMap.asStateFlow()
    
    // OWN presence state (for "You" card)
    private val _ownPresence = MutableStateFlow<UserPresence?>(null)
    val ownPresence: StateFlow<UserPresence?> = _ownPresence.asStateFlow()
    
    /**
     * Start observing own presence for "You" card
     */
    fun observeOwnPresence(uid: String) {
        viewModelScope.launch {
            Log.d(TAG, "observeOwnPresence: Starting observation for uid=$uid")
            PresenceManager.observeFullPresence(uid).collect { presence ->
                Log.d(TAG, "observeOwnPresence: Updated - online=${presence.online}, status=${presence.status}")
                _ownPresence.value = presence
            }
        }
    }
    
    /**
     * Observe presence for friends list
     */
    fun observeFriendsPresence(friendUids: List<String>) {
        Log.d(TAG, "observeFriendsPresence: Called with ${friendUids.size} friends")
        
        // Cancel previous collection
        collectionJob?.cancel()
        
        // Validate session truth
        viewModelScope.launch {
            PresenceManager.validateSessionTruth()
        }
        
        // If empty list, clear and return
        if (friendUids.isEmpty()) {
            Log.d(TAG, "observeFriendsPresence: Empty friend list, clearing")
            _friendsPresenceMap.value = emptyMap()
            return
        }
        
        // Start observing each friend
        collectionJob = viewModelScope.launch {
            Log.d(TAG, "observeFriendsPresence: Starting collection for ${friendUids.size} friends")
            
            // Create a flow for each friend
            val friendFlows = friendUids.map { uid ->
                PresenceManager.observeFullPresence(uid).map { presence ->
                    Log.d(TAG, "observeFriendsPresence: Update from uid=$uid, online=${presence.online}, status=${presence.status}")
                    uid to presence
                }
            }
            
            // Combine all flows
            combine(friendFlows) { presenceArray ->
                presenceArray.toMap()
            }.collect { combinedMap ->
                Log.d(TAG, "observeFriendsPresence: Emitting ${combinedMap.size} presence entries")
                _friendsPresenceMap.value = combinedMap
            }
        }
    }
    
    /**
     * Clear all observations
     */
    fun clearPresenceObservations() {
        Log.d(TAG, "clearPresenceObservations: Clearing all presence data")
        collectionJob?.cancel()
        collectionJob = null
        _friendsPresenceMap.value = emptyMap()
        _ownPresence.value = null
    }
}
