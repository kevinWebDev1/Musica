package com.github.musicyou.auth

import android.content.Context
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

class AuthManager(private val context: Context) {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val webClientId = "98221868348-o6g1revbm6l9jsuj62ef7c47gjos0tnv.apps.googleusercontent.com"

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken(webClientId)
        .requestEmail()
        .build()

    private val googleSignInClient: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    init {
        // Listen for auth state changes
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
            Log.d(TAG, "Auth state changed: user=${firebaseAuth.currentUser?.displayName}")
        }
    }

    /**
     * Get Google Sign-In intent for launching the sign-in UI
     */
    fun getSignInIntent() = googleSignInClient.signInIntent

    /**
     * Complete sign-in from the Intent received in activity result
     */
    suspend fun completeSignIn(data: android.content.Intent?): Result<FirebaseUser> {
        Log.d(TAG, "completeSignIn called with data: $data")
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            Log.d(TAG, "GoogleSignIn task retrieved, checking success...")
            val account = task.getResult(ApiException::class.java) ?: throw Exception("Google account is null")
            Log.d(TAG, "Google account retrieved: ${account.email}, proceeding to Firebase auth")
            signInWithGoogle(account)
        } catch (e: Exception) {
            Log.e(TAG, "Google sign-in flow failed at getResult", e)
            Result.failure(e)
        }
    }

    /**
     * Complete sign-in after receiving Google account
     */
    suspend fun signInWithGoogle(account: GoogleSignInAccount): Result<FirebaseUser> {
        Log.d(TAG, "signInWithGoogle starting for account: ${account.email}")
        return try {
            val idToken = account.idToken ?: throw Exception("Google ID Token is null")
            Log.d(TAG, "Retrieving Firebase credential with ID token...")
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            Log.d(TAG, "Signing in with credential...")
            val result = auth.signInWithCredential(credential).await()
            val user = result.user ?: throw Exception("Firebase user is null")
            Log.i(TAG, "Firebase sign-in successful: ${user.displayName} (${user.email})")
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Firebase sign-in with credential failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sign out from both Firebase and Google
     */
    fun signOut() {
        val userName = auth.currentUser?.displayName
        auth.signOut()
        googleSignInClient.signOut()
        Log.i(TAG, "Signed out: $userName")
    }

    companion object {
        private const val TAG = "AuthManager"
    }
}
