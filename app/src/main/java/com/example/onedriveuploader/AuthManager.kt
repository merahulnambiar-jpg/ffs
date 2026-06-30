package com.example.onedriveuploader

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.*
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps MSAL single-account sign-in and silent token acquisition.
 * Scope used is Files.ReadWrite which lets the app create/upload files
 * in the signed-in user's own OneDrive.
 */
class AuthManager(private val context: Context) {

    private var msalApp: ISingleAccountPublicClientApplication? = null
    private val scopes = arrayOf("Files.ReadWrite")

    suspend fun init() = suspendCancellableCoroutine<Unit> { cont ->
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context,
            R.raw.msal_config,
            object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                override fun onCreated(application: ISingleAccountPublicClientApplication) {
                    msalApp = application
                    if (cont.isActive) cont.resume(Unit)
                }

                override fun onError(exception: MsalException) {
                    if (cont.isActive) cont.resumeWithException(exception)
                }
            }
        )
    }

    suspend fun signIn(activity: Activity): String = suspendCancellableCoroutine { cont ->
        val app = msalApp ?: return@suspendCancellableCoroutine cont.resumeWithException(
            IllegalStateException("MSAL not initialized")
        )

        val callback = object : AuthenticationCallback {
            override fun onSuccess(result: IAuthenticationResult) {
                if (cont.isActive) cont.resume(result.accessToken)
            }
            override fun onError(exception: MsalException) {
                if (cont.isActive) cont.resumeWithException(exception)
            }
            override fun onCancel() {
                if (cont.isActive) cont.resumeWithException(Exception("Sign-in cancelled"))
            }
        }

        val params = SignInParameters.builder()
            .withActivity(activity)
            .withScopes(scopes.toList())
            .withCallback(callback)
            .build()

        app.signIn(params)
    }

    /** Tries silent token first (no UI), falls back to interactive sign-in. */
    suspend fun getAccessToken(activity: Activity): String {
        val app = msalApp ?: throw IllegalStateException("MSAL not initialized")
        val account = suspendCancellableCoroutine<IAccount?> { cont ->
            app.getCurrentAccountAsync(object : ISingleAccountPublicClientApplication.CurrentAccountCallback {
                override fun onAccountLoaded(activeAccount: IAccount?) {
                    if (cont.isActive) cont.resume(activeAccount)
                }
                override fun onAccountChanged(priorAccount: IAccount?, currentAccount: IAccount?) {}
                override fun onError(exception: MsalException) {
                    if (cont.isActive) cont.resume(null)
                }
            })
        }

        if (account == null) {
            return signIn(activity)
        }

        return suspendCancellableCoroutine { cont ->
            app.acquireTokenSilentAsync(
                scopes,
                account.authority,
                object : SilentAuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        if (cont.isActive) cont.resume(authenticationResult.accessToken)
                    }
                    override fun onError(exception: MsalException) {
                        // Silent failed (e.g. token expired beyond refresh) -> interactive fallback
                        if (cont.isActive) {
                            cont.resumeWithException(exception)
                        }
                    }
                }
            )
        }
    }

    fun isSignedIn(): Boolean = msalApp != null
}
