package org.maplibre.android.session

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.annotation.RestrictTo
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
        // minted a replacement AND CHARGED for it.
        //
        // The URL passed to adoption is `response.request.url`, NOT `request.url`. This is an
        // application interceptor, so `request.url` is the URL BEFORE any redirect, while
        // `response.request.url` is the one that actually produced these headers. If the gateway
        // ever 3xx'd cross-host, using the pre-redirect URL would let the redirect TARGET's
        // headers pass an origin check they never actually satisfied.
        val adopted = if (response.header(SESSION_SIG_HEADER) != null) {
            MMMapSession.applyCredentialFromHeaders(response.headers, response.request.url)
        } else {
            false
        }

        // FALL THROUGH. Deliberately not `else if`: a 401 that also carries rollover headers we
        // then REFUSE (unknown account, not newer, wrong host) would otherwise consume the header
        // branch and never trigger recovery, leaving the session machine with no usable credential
        // and nothing scheduled — a permanently blank map.
        //
        // This gate reads `request.url`, not `response.request.url`, and the difference is
        // deliberate: it asks "is this 401 about the credential WE SENT", and what we sent is the
        // URL we signed. A redirect that dropped the query would make the post-redirect URL look
        // unsigned and wrongly suppress recovery.
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

        /**
         * `AndroidManifest.xml` `<meta-data>` naming the gateway origin, for example:
         *
         * ```xml
         * <meta-data
         *     android:name="org.maplibre.android.MapSessionOrigin"
         *     android:value="https://gateway.example.com" />
         * ```
         *
         * This is the configuration channel for invariant 3. Without it the origin is learned from
         * the first https tile URL, and [MMMapSession.refreshNow] POSTs the customer's permanent
         * API key to whatever that turned out to be.
         */
        const val GATEWAY_ORIGIN_META_DATA = "org.maplibre.android.MapSessionOrigin"

        private val installed = AtomicBoolean(false)
        private val foregroundInstalled = AtomicBoolean(false)

        @Volatile
        private var installedClient: OkHttpClient? = null

        /** How many activities are currently started. 0 -> 1 is "the app entered the foreground". */
        private val startedActivities = AtomicInteger(0)

        /**
         * Set when an activity stops FOR A CONFIGURATION CHANGE, so the matching restart is not
         * counted as a new foreground entry. See [activityStarted].
         */
        private val recreatingForConfigChange = AtomicBoolean(false)

        /**
         * Pins the gateway origin from the manifest, registers a client carrying
         * [MMMapSessionInterceptor] as the SDK's HTTP client, and wires the foreground hook.
         *
         * Idempotent: safe to call from every `MapLibre.getInstance` overload on every call.
         *
         * @param context any context; its application context is used. Null skips the manifest
         * lookup and the foreground hook, leaving only the interceptor.
         */
        @JvmStatic
        @JvmOverloads
        fun install(context: Context? = null) {
            pinOriginFromManifest(context)
            installClient()
            installForegroundHook(context)
        }

        /**
         * Reads [GATEWAY_ORIGIN_META_DATA] and pins it as the gateway origin (invariant 3).
         *
         * Absent meta-data is not an error: the SDK falls back to learning the origin from the
         * first https tile URL, which is what every app does today and what keeps staging and
         * production working with no setup. That fallback is weaker — see
         * [MMMapSession.pinConfiguredOrigin] — which is exactly why this channel exists.
         */
        private fun pinOriginFromManifest(context: Context?) {
            if (context == null) return
            try {
                val appContext = context.applicationContext ?: context
                val info = appContext.packageManager.getApplicationInfo(
                    appContext.packageName,
                    PackageManager.GET_META_DATA
                )
                val configured = info.metaData?.getString(GATEWAY_ORIGIN_META_DATA)?.trim()
                if (!configured.isNullOrEmpty()) {
                    MMMapSession.pinConfiguredOrigin(configured)
                }
            } catch (throwable: Throwable) {
                // A manifest we cannot read must not take getInstance down; the learn-once
                // fallback still produces a working map.
                Logger.e(TAG, "could not read $GATEWAY_ORIGIN_META_DATA from the manifest", throwable)
            }
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

        private fun activityStarted() {
            // A restart that is only the other half of a configuration change is not a foreground
            // entry, and the matching stop already declined to decrement, so this must not
            // increment either — otherwise the count drifts up by one per rotation and the app
            // never looks backgrounded again.
            if (recreatingForConfigChange.compareAndSet(true, false)) return

            // Only the 0 -> 1 edge is a foreground transition; every later activity start within
            // the same session is just navigation and must not re-arm anything.
            if (startedActivities.incrementAndGet() == 1) {
                MMMapSession.onEnterForeground()
            }
        }

        private fun activityStopped(isChangingConfigurations: Boolean) {
            // THE ROTATION GUARD. A configuration change runs stop -> destroy -> create -> start,
            // so a naive counter goes 1 -> 0 -> 1 and fires the foreground edge. onEnterForeground
            // clears gaveUp, hardFailures and lastCountedFailureAt, so a user rotating the device
            // would defeat the three-failure give-up guard entirely and resume hammering a key the
            // gateway will never accept. This is what ProcessLifecycleOwner's debounce exists for.
            if (isChangingConfigurations) {
                recreatingForConfigChange.set(true)
                return
            }
            if (startedActivities.decrementAndGet() < 0) startedActivities.set(0)
        }

        /** Test seam: the client [install] registered, or null if it has not run. */
        @JvmStatic
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun installedClientForTesting(): OkHttpClient? = installedClient

        /**
         * Test seam: lets a test install again against a fresh client. Restricted because calling
         * it from an app would unhook signing and blank every v2 map in the process.
         */
        @JvmStatic
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun resetInstallStateForTesting() {
            installed.set(false)
            foregroundInstalled.set(false)
            startedActivities.set(0)
            recreatingForConfigChange.set(false)
            installedClient = null
            HttpRequestUtil.setOkHttpClient(null)
        }

        /** Test seam: drives an activity start without an Activity. */
        @JvmStatic
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun notifyActivityStartedForTesting() = activityStarted()

        /** Test seam: drives an activity stop, optionally as half of a configuration change. */
        @JvmStatic
        @JvmOverloads
        @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
        fun notifyActivityStoppedForTesting(isChangingConfigurations: Boolean = false) =
            activityStopped(isChangingConfigurations)

        private object ForegroundCallbacks : Application.ActivityLifecycleCallbacks {

            override fun onActivityStarted(activity: Activity) = activityStarted()

            override fun onActivityStopped(activity: Activity) =
                activityStopped(activity.isChangingConfigurations)

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
    }
}
