package org.maplibre.android.module.http;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import okhttp3.OkHttpClient;

/**
 * MAPMETRICS PATCH -- v2 map sessions.
 * <p>
 * Exposes {@code HttpRequestImpl.getOrCreateDefaultClient()}, which is package-private, to the
 * {@code org.maplibre.android.session} package.
 * </p>
 * <p>
 * This exists so that {@code MMMapSessionInterceptor.install()} can build its client with
 * {@code defaultClient().newBuilder()} rather than {@code new OkHttpClient.Builder()}. The
 * difference is not cosmetic: the default client carries this fork's {@code InMemoryCookieJar},
 * which holds the gateway's {@code usageSession} cookie and is what makes v1 bill once per
 * 30-minute window instead of once per tile. A freshly built client drops the cookie jar, keeps
 * serving tiles perfectly, logs nothing, and regresses v1 billing by roughly 200x.
 * </p>
 * <p>
 * A new file on purpose: it adds no line to an upstream file, so it costs nothing to replay on
 * a re-vendor.
 * </p>
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public final class MMHttpClients {

  private MMHttpClients() {
  }

  /**
   * The SDK's default OkHttp client, cookie jar and dispatcher included.
   *
   * @return the default client; never null
   */
  @NonNull
  public static OkHttpClient defaultClient() {
    return HttpRequestImpl.getOrCreateDefaultClient();
  }

  /**
   * The call factory the SDK is currently issuing map requests through.
   *
   * <p>
   * {@link HttpRequestUtil#setOkHttpClient(okhttp3.Call.Factory)} is public API, so a host app can
   * replace this at any time — and doing so after {@code MapLibre.getInstance} silently unhooks v2
   * tile signing, after which every v2 tile 401s and retries forever with a blank map and nothing
   * logged. {@code MMMapSessionInterceptor} reads this on install and on every foreground entry so
   * it can notice it has been displaced and re-install, rather than installing once and never
   * checking again.
   * </p>
   *
   * @return the current call factory; never null (it defaults to {@link #defaultClient()})
   */
  @NonNull
  public static okhttp3.Call.Factory currentClient() {
    return HttpRequestImpl.client;
  }
}
