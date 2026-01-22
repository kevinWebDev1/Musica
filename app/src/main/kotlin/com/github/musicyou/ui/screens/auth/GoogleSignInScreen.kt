package com.github.musicyou.ui.screens.auth

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.musicyou.R
import com.github.musicyou.auth.AuthManager
import com.github.musicyou.auth.ProfileManager
import com.github.musicyou.ui.styling.neonPink
import com.github.musicyou.ui.styling.neonPurple
import kotlinx.coroutines.launch

@Composable
fun GoogleSignInScreen(
    authManager: AuthManager,
    onSignedIn: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d("GoogleSignInScreen", "Launcher result: ${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            scope.launch {
                Log.d("GoogleSignInScreen", "Starting completeSignIn...")
                authManager.completeSignIn(result.data).fold(
                    onSuccess = { user ->
                        Log.i("GoogleSignInScreen", "Sign-in successful: ${user.displayName}")
                        scope.launch {
                            com.github.musicyou.auth.SyncManager.restoreUserData(context)
                            ProfileManager.fetchUserProfile(context)
                        }
                        isLoading = false
                        onSignedIn()
                    },
                    onFailure = { error ->
                        Log.e("GoogleSignInScreen", "Sign-in failed", error)
                        isLoading = false
                        errorMessage = error.message ?: "Firebase sign-in failed"
                    }
                )
            }
        } else {
            isLoading = false
            val code = result.resultCode
            errorMessage = if (code == Activity.RESULT_CANCELED) {
                "Sign-in was cancelled or failed internally (Code 0). This usually means your SHA-1 fingerprint is not registered in Firebase or there's a configuration mismatch."
            } else {
                "Sign-in failed with code: $code"
            }
            Log.w("GoogleSignInScreen", "Sign-in cancelled or failed with code: $code")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // App Logo in 16:9 container with border
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_icon),
                contentDescription = "Musica Logo",
                modifier = Modifier
                    .size(320.dp)
                    .scale(1.4f) // Zoomed in effect
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // App Title
        Text(
            text = "Musica",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Tagline
        Text(
            text = "Sync music across devices",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Sign In Button
        Button(
            onClick = {
                if (!isLoading) {
                    Log.d("GoogleSignInScreen", "SignIn button clicked")
                    isLoading = true
                    errorMessage = null
                    launcher.launch(authManager.getSignInIntent())
                }
            },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent
            ),
            contentPadding = PaddingValues(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .height(56.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    brush = Brush.horizontalGradient(
                        colors = if (isLoading) {
                            listOf(neonPurple.copy(alpha = 0.5f), neonPink.copy(alpha = 0.5f))
                        } else {
                            listOf(neonPurple, neonPink)
                        }
                    )
                )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "G",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                    Text(
                        text = "Sign in with Google",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }

        // Error Message
        errorMessage?.let { error ->
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
