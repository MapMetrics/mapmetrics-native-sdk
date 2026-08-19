package org.maplibre.android.module.http;

import androidx.annotation.NonNull;
import androidx.annotation.RestrictTo;

import okhttp3.OkHttpClient;

/**
 * MAPMETRICS PATCH -- v2 map sessions.
 * <p>
 * Exposes {@link HttpRequestImpl#DEFAULT_CLIENT}, which is package-private, to the
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
    return HttpRequestImpl.DEFAULT_CLIENT;
  }
}
