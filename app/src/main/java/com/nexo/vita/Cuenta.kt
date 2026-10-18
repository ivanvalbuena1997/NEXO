package com.nexo.vita

import android.app.Activity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await

/** Inicio de sesión con Google (Credential Manager) en Firebase Auth, con la misma cuenta que V.I.T.A. */
class Cuenta(private val activity: Activity) {

    private val auth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(activity)

    val usuario: FirebaseUser? get() = auth.currentUser

    /** Devuelve null si todo fue bien; si no, un texto con el motivo. */
    suspend fun iniciarSesion(): String? {
        val opcion = GetSignInWithGoogleOption.Builder(
            serverClientId = activity.getString(R.string.default_web_client_id)
        ).build()
        val peticion = GetCredentialRequest.Builder()
            .addCredentialOption(opcion)
            .build()

        return try {
            val respuesta = credentialManager.getCredential(context = activity, request = peticion)
            val credencial = respuesta.credential
            if (credencial is CustomCredential &&
                credencial.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = GoogleIdTokenCredential.createFrom(credencial.data)
                val firebase = GoogleAuthProvider.getCredential(google.idToken, null)
                auth.signInWithCredential(firebase).await()
                null
            } else {
                "Credencial inesperada (${credencial.type})."
            }
        } catch (e: GetCredentialCancellationException) {
            "Inicio de sesión cancelado."
        } catch (e: GetCredentialException) {
            "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
        } catch (e: Exception) {
            "${e.javaClass.simpleName}: ${e.message ?: "sin detalle"}"
        }
    }

    suspend fun cerrarSesion() {
        auth.signOut()
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
            // No impide cerrar la sesión de Firebase.
        }
    }
}
