package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val AUTH_TAG = "Auth"

object AuthPersistence {
    private const val PREFS_NAME = "pulsecall_auth_prefs"
    private const val KEY_WAS_SIGNED_IN = "was_signed_in"
    private const val KEY_LAST_EMAIL = "last_email"
    private const val KEY_LAST_NAME = "last_name"

    fun markSignedIn(context: Context, user: FirebaseUser?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_WAS_SIGNED_IN, true)
            .apply {
                user?.email?.let { putString(KEY_LAST_EMAIL, it) }
                user?.displayName?.let { putString(KEY_LAST_NAME, it) }
            }
            .apply()
    }

    fun wasSignedIn(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_WAS_SIGNED_IN, false)
    }

    fun lastEmail(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_EMAIL, null)
    }

    fun clear(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope
) {
    val existingUser = Firebase.auth.currentUser
    if (existingUser != null) {
        AuthPersistence.markSignedIn(context, existingUser)
        onAuthSuccess()
        return
    }

    scope.launch {
        // Give FirebaseAuth a brief window on cold boot to hydrate its persisted user from disk
        repeat(4) {
            val hydrated = Firebase.auth.currentUser
            if (hydrated != null) {
                AuthPersistence.markSignedIn(context, hydrated)
                onAuthSuccess()
                return@launch
            }
            delay(80L)
        }

        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return@launch
        }

        val credContext = context.findActivity() ?: context

        // Pass 1: Authorized accounts with auto-select
        val authorizedSuccess = trySilentGoogleSignIn(
            credContext = credContext,
            credentialManager = credentialManager,
            clientId = clientId,
            filterByAuthorizedAccounts = true
        )
        if (authorizedSuccess) {
            AuthPersistence.markSignedIn(context, Firebase.auth.currentUser)
            onAuthSuccess()
            return@launch
        }

        // Pass 2: If the user previously signed in (e.g. via GetSignInWithGoogleOption),
        // retry without authorized-account filtering so saved account restores seamlessly
        if (AuthPersistence.wasSignedIn(context)) {
            val restoredSuccess = trySilentGoogleSignIn(
                credContext = credContext,
                credentialManager = credentialManager,
                clientId = clientId,
                filterByAuthorizedAccounts = false
            )
            if (restoredSuccess) {
                AuthPersistence.markSignedIn(context, Firebase.auth.currentUser)
                onAuthSuccess()
                return@launch
            }
        }

        onUnauthenticated()
    }
}

private suspend fun trySilentGoogleSignIn(
    credContext: Context,
    credentialManager: CredentialManager,
    clientId: String,
    filterByAuthorizedAccounts: Boolean
): Boolean {
    return try {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        val cred = credentialManager.getCredential(credContext, request).credential
        if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
            Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            Firebase.auth.currentUser != null
        } else {
            false
        }
    } catch (e: Exception) {
        Log.d(AUTH_TAG, "Silent credential restore skipped (authorizedOnly=$filterByAuthorizedAccounts): ${e.message}")
        false
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: () -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {}
) {
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
        onAuthError("Google Sign-In configuration missing: default_web_client_id not found")
        return
    }
    val activity = context.findActivity()
    if (activity == null) {
        onAuthError("Unable to locate an active screen to launch Google Sign-In.")
        return
    }
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()
    scope.launch {
        try {
            val cred = credentialManager.getCredential(activity, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                Firebase.auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                AuthPersistence.markSignedIn(context, Firebase.auth.currentUser)
                onAuthSuccess()
            } else {
                onAuthError("Unexpected credential type returned.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(AUTH_TAG, "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: NoCredentialException) {
            Log.w(AUTH_TAG, "No Google credential found on device: ${e.message}", e)
            onAuthError("No Google account available on this device. Add a Google account in Settings or verify Google Play Services.")
        } catch (e: Exception) {
            Log.e(AUTH_TAG, "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Sign in failed")
        }
    }
}

fun signOut(
    context: Context,
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onSignOutComplete: () -> Unit = {}
) {
    AuthPersistence.clear(context)
    scope.launch {
        withContext(NonCancellable) {
            try {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(AUTH_TAG, "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}
