package com.inhibitai.app.ui.auth

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.inhibitai.app.BuildConfig
import com.inhibitai.app.ui.InhibitViewModel
import kotlinx.coroutines.launch

/** "Continue with Google": asks Android's Credential Manager for a Google ID token for our backend. */
object GoogleSignIn {

    val isConfigured: Boolean get() = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

    sealed interface Outcome {
        data class Token(val idToken: String) : Outcome
        data object Cancelled : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** [context] must be the Activity: Android shows the account picker on top of it. */
    suspend fun requestIdToken(context: Context): Outcome {
        if (!isConfigured) {
            return Outcome.Failed("Google sign-in isn't set up in this version of the app yet. Please use email.")
        }
        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        return try {
            val credential = CredentialManager.create(context).getCredential(context, request).credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                Outcome.Token(GoogleIdTokenCredential.createFrom(credential.data).idToken)
            } else {
                Outcome.Failed("Google sign-in returned something unexpected. Please try again.")
            }
        } catch (e: GetCredentialCancellationException) {
            Outcome.Cancelled
        } catch (e: NoCredentialException) {
            Outcome.Failed("There's no Google account on this phone. Add one in Settings → Accounts, then try again.")
        } catch (e: GoogleIdTokenParsingException) {
            Outcome.Failed("Google sign-in failed: couldn't read Google's answer.")
        } catch (e: GetCredentialException) {
            Outcome.Failed("Google sign-in failed: ${e.message ?: e.type}")
        }
    }
}

/** Click handler for a "Continue with Google" button: signs in through [vm], or shows why it couldn't. */
@Composable
fun rememberGoogleSignIn(vm: InhibitViewModel): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return {
        vm.clearAuthError()
        scope.launch {
            when (val outcome = GoogleSignIn.requestIdToken(context)) {
                is GoogleSignIn.Outcome.Token -> vm.googleSignIn(outcome.idToken)
                is GoogleSignIn.Outcome.Failed -> vm.showAuthError(outcome.message)
                GoogleSignIn.Outcome.Cancelled -> Unit
            }
        }
    }
}
