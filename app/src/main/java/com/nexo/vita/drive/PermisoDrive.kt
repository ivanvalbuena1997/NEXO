package com.nexo.vita.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

/**
 * Permiso de Google Drive con la API de autorización de Google para Android
 * (Identity.getAuthorizationClient). Según la documentación: se pide una vez con
 * interacción; después, mientras no se retire, authorize() devuelve un token
 * (válido ~1 hora) sin interacción.
 */
object PermisoDrive {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"

    sealed class Estado {
        data class Token(val valor: String) : Estado()
        /** Hace falta que el usuario conceda el permiso (solo posible con la app abierta). */
        data class NecesitaPermiso(val intent: PendingIntent) : Estado()
    }

    /** [email]: la cuenta de Google con la que se ha entrado en NEXO (la misma que V.I.T.A.). */
    suspend fun pedir(ctx: Context, email: String?): Estado {
        val peticion = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .apply { if (email != null) setAccount(Account(email, "com.google")) }
            .build()
        val r = Identity.getAuthorizationClient(ctx).authorize(peticion).await()
        if (r.hasResolution()) {
            val pi = r.pendingIntent ?: error("Google pide permiso pero no da la pantalla para concederlo.")
            return Estado.NecesitaPermiso(pi)
        }
        val token = r.accessToken ?: error("Google no devolvió el token de Drive.")
        return Estado.Token(token)
    }

    /** Si Drive rechaza un token (401), se borra de la caché para que el siguiente sea nuevo. */
    suspend fun olvidar(ctx: Context, token: String) {
        runCatching {
            Identity.getAuthorizationClient(ctx)
                .clearToken(ClearTokenRequest.builder().setToken(token).build())
                .await()
        }
    }
}
