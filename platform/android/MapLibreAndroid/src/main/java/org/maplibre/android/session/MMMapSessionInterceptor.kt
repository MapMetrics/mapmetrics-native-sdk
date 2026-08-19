package org.maplibre.android.session

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.maplibre.android.log.Logger
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.module.http.MMHttpClients
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one place v2 map sessions touch the network.
 *
 * Android has no per-request delegate — the only hook the SDK offers is
 * [HttpRequestUtil.setOkHttpClient], a whole-client swap — so signing on the way out and
 * credential recovery on the way back both live in this single interceptor.
 */
class MMMapSessionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Signing is a no-op for anything that is not a v2 tile URL on the pinned origin, and
        // signedUrl never throws — an unsigned tile 401s and recovers below.
        val signed = MMMapSession.signedUrl(original.url)
        val request = if (signed != original.url) {
            original.newBuilder().url(signed).build()
        } else {
            original
        }

        val response = chain.proceed(request)

        // X-Map-Session-* arrive ONLY from a rollover: the credential had expired, the gateway
        // minted a replacement AND CHARGED for it. The responding URL is passed so adoption can
        // verify the credential came from the pinned gateway origin and not from some host named
        // in a style document.
        val adopted = if (response.header(SESSION_SIG_HEADER) != null) {
            MMMapSession.applyCredentialFromHeaders(response.headers, request.url)
        } else {
            false
        }

        // FALL THROUGH. Deliberately not `else if`: a 401 that also carries rollover headers we
        // then REFUSE (unknown account, not newer, wrong host) would otherwise consume the header
        // branch and never trigger recovery, leaving the session machine with no usable credential
        // and nothing scheduled — a permanently blank map.
        if (!adopted &&
            response.code == 401 &&
            MMMapSession.shouldRefreshForResponseUrl(request.url)
        ) {
            MMMapSession.refreshNow()
        }

        return response
    }

    companion object {

        private const val TAG = "Mbgl-MMMapSessionInterceptor"

        /** The rollover marker header. Its presence is what makes adoption worth attempting. */
        const val SESSION_SIG_HEADER = "X-Map-Session-Sig"

        private val installed = AtomicBoolean(false)
        private val foregroundInstalled = AtomicBoolean(false)

        @Volatile
        private var installedClient: OkHttpClient? = null

        /** How many activities are currently started. 0 -> 1 is "the app entered the foreground". */
        private val startedActivities = AtomicInteger(0)

        /**
         * Registers a client carrying [MMMapSessionInterceptor] as the SDK's HTTP client, and
         * wires the foreground hook if an [Application] can be reached from [context].
         *
         * Idempotent: safe to call from every `MapLibre.getInstance` overload on every call.
         *
         * @param context any context; its application context is used, and null simply skips the
         * foreground hook.
         */
        @JvmStatic
        @JvmOverloads
        fun install(context: Context? = null) {
            installClient()
            installForegroundHook(context)
        }

        private fun installClient() {
            if (!installed.compareAndSet(false, true)) return
            try {
                // newBuilder() on the EXISTING client, never a fresh OkHttpClient.Builder(): the
                // default client carries this fork's InMemoryCookieJar, which holds the gateway's
                // usageSession cookie. A fresh client drops it silently — tiles still render, and
                // v1 billing regresses by roughly 200x with nothing logged anywhere.
                val client: OkHttpClient = MMHttpClients.defaultClient()
                    .newBuilder()
                    .addInterceptor(MMMapSessionInterceptor())
                    .build()
                installedClient = client
                HttpRequestUtil.setOkHttpClient(client)
            } catch (throwable: Throwable) {
                // Never take the map down over this: without the interceptor, v2 tiles go
                // unsigned, which is the pre-existing v1 behaviour rather than a failure.
                installed.set(false)
                Logger.e(TAG, "failed to install the map-session interceptor", throwable)
            }
        }

        /**
         * [MMMapSession.onEnterForeground] clears the give-up state and re-arms the renewal timer.
         * Without a caller, a transient auth failure becomes a permanently blank map until the
         * process restarts.
         *
         * `ProcessLifecycleOwner` would be the natural signal, but `androidx.lifecycle:
         * lifecycle-process` is not a dependency of this module and adding one is out of scope,
         * so this counts started activities instead — the same thing that library does.
         */
        private fun installForegroundHook(context: Context?) {
            if (context == null) return
            val application = context.applicationContext as? Application ?: return
            if (!foregroundInstalled.compareAndSet(false, true)) return
            try {
                application.registerActivityLifecycleCallbacks(ForegroundCallbacks)
            } catch (throwable: Throwable) {
                foregroundInstalled.set(false)
                Logger.e(TAG, "failed to install the map-session foreground hook", throwable)
            }
        }

        /** Test seam: the client [install] registered, or null if it has not run. */
        @JvmStatic
        fun installedClientForTesting(): OkHttpClient? = installedClient

        /** Test seam: lets a test install again against a fresh client. */
        @JvmStatic
        fun resetInstallStateForTesting() {
            installed.set(false)
            foregroundInstalled.set(false)
            startedActivities.set(0)
            installedClient = null
            HttpRequestUtil.setOkHttpClient(null)
        }

        /** Test seam: drives the foreground transition without an Activity. */
        @JvmStatic
        fun notifyActivityStartedForTesting() = activityStarted()

        /** Test seam: drives the background transition without an Activity. */
        @JvmStatic
        fun notifyActivityStoppedForTesting() = activityStopped()

        private fun activityStarted() {
            // Only the 0 -> 1 edge is a foreground transition; every later activity start within
            // the same session is just navigation and must not re-arm anything.
            if (startedActivities.incrementAndGet() == 1) {
                MMMapSession.onEnterForeground()
            }
        }

        private fun activityStopped() {
            if (startedActivities.decrementAndGet() < 0) startedActivities.set(0)
        }

        private object ForegroundCallbacks : Application.ActivityLifecycleCallbacks {

            override fun onActivityStarted(activity: Activity) = activityStarted()

            override fun onActivityStopped(activity: Activity) = activityStopped()

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
    }
}
