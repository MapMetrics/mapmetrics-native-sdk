package org.maplibre.android.module.http;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import org.maplibre.android.BuildConfig;
import org.maplibre.android.constants.MapLibreConstants;
import org.maplibre.android.http.HttpIdentifier;
import org.maplibre.android.http.HttpLogger;
import org.maplibre.android.http.HttpRequest;
import org.maplibre.android.http.HttpRequestUrl;
import org.maplibre.android.http.HttpResponder;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.NoRouteToHostException;
import java.net.ProtocolException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.net.ssl.SSLException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Dispatcher;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.maplibre.android.module.http.HttpRequestUtil.toHumanReadableAscii;

public class HttpRequestImpl implements HttpRequest {

  private static final String userAgentString = toHumanReadableAscii(
          String.format("%s %s (%s) Android/%s (%s)",
                  HttpIdentifier.getIdentifier(),
                  BuildConfig.MAPLIBRE_VERSION_STRING,
                  BuildConfig.GIT_REVISION_SHORT,
                  Build.VERSION.SDK_INT,
                  Build.SUPPORTED_ABIS[0])
  );

  private static final InMemoryCookieJar cookieJar = new InMemoryCookieJar();

  @VisibleForTesting
  static final OkHttpClient DEFAULT_CLIENT = new OkHttpClient.Builder()
          .dispatcher(getDispatcher())
          .cookieJar(cookieJar)
          .build();

  @VisibleForTesting
  static Call.Factory client = DEFAULT_CLIENT;

  private Call call;

  // Simple in-memory cookie storage implementation
  private static class InMemoryCookieJar implements CookieJar {
    private final List<Cookie> cookies = new ArrayList<>();

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
      this.cookies.addAll(cookies);
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
      List<Cookie> validCookies = new ArrayList<>();
      for (Cookie cookie : cookies) {
        if (cookie.matches(url)) {
          validCookies.add(cookie);
        }
      }
      return validCookies;
    }

    public List<Cookie> getAllCookies() {
      return new ArrayList<>(cookies);
    }

    public void clearCookies() {
      cookies.clear();
    }
  }

  // Method to get all stored cookies
  public static List<Cookie> getAllCookies() {
    return cookieJar.getAllCookies();
  }

  // Method to clear all stored cookies
  public static void clearCookies() {
    cookieJar.clearCookies();
  }

  @Override
  public void executeRequest(HttpResponder httpRequest, long nativePtr, @NonNull String resourceUrl,
                             @NonNull String dataRange, @NonNull String etag, @NonNull String modified,
                             boolean offlineUsage) {
    OkHttpCallback callback = new OkHttpCallback(httpRequest);
    try {
      HttpUrl httpUrl = HttpUrl.parse(resourceUrl);
      HttpLogger.log(Log.WARN, String.format("[HTTP] parse resourceUrl %s", resourceUrl));
      if (httpUrl == null) {
        HttpLogger.log(Log.ERROR, String.format("[HTTP] Unable to parse resourceUrl %s", resourceUrl));
        return;
      }

      final String host = httpUrl.host().toLowerCase(MapLibreConstants.MAPLIBRE_LOCALE);
      resourceUrl = HttpRequestUrl.buildResourceUrl(host, resourceUrl, httpUrl.querySize(), offlineUsage);

      final Request.Builder builder = new Request.Builder()
              .url(resourceUrl)
              .tag(resourceUrl.toLowerCase(MapLibreConstants.MAPLIBRE_LOCALE))
              .addHeader("User-Agent", userAgentString);

      if (dataRange.length() > 0) {
        builder.addHeader("Range", dataRange);
      }

      if (etag.length() > 0) {
        builder.addHeader("If-None-Match", etag);
      } else if (modified.length() > 0) {
        builder.addHeader("If-Modified-Since", modified);
      }

      final Request request = builder.build();
      call = client.newCall(request);
      call.enqueue(callback);
    } catch (Exception exception) {
      callback.handleFailure(call, exception);
    }
  }

  @Override
  public void cancelRequest() {
    // call can be null if the constructor gets aborted (e.g, under a NoRouteToHostException).
    if (call != null) {
      HttpLogger.log(Log.DEBUG, String.format("[HTTP] This request was cancelled (%s). This is expected for tiles"
              + " that were being prefetched but are no longer needed for the map to render.", call.request().url()));
      call.cancel();
    }
  }

  public static void enablePrintRequestUrlOnFailure(boolean enabled) {
    HttpLogger.logRequestUrl = enabled;
  }

  public static void enableLog(boolean enabled) {
    HttpLogger.logEnabled = enabled;
  }

  public static void setOkHttpClient(@Nullable Call.Factory client) {
    HttpRequestImpl.client = Objects.requireNonNullElse(client, DEFAULT_CLIENT);
  }

  private static class OkHttpCallback implements Callback {

    private HttpResponder httpRequest;

    OkHttpCallback(HttpResponder httpRequest) {
      this.httpRequest = httpRequest;
    }

    @Override
    public void onFailure(@NonNull Call call, @NonNull IOException e) {
      handleFailure(call, e);
    }

    @Override
    public void onResponse(@NonNull Call call, @NonNull Response response) {
      if (response.isSuccessful()) {
        HttpLogger.log(Log.VERBOSE, String.format("[HTTP] Request was successful (code = %s).", response.code()));
      } else {
        // We don't want to call this unsuccessful because a 304 isn't really an error
        String message = !TextUtils.isEmpty(response.message()) ? response.message() : "No additional information";
        HttpLogger.log(Log.DEBUG, String.format("[HTTP] Request with response = %s: %s", response.code(), message));
      }

      ResponseBody responseBody = response.body();
      if (responseBody == null) {
        HttpLogger.log(Log.ERROR, "[HTTP] Received empty response body");
        return;
      }

      byte[] body;
      try {
        body = responseBody.bytes();
      } catch (IOException ioException) {
        onFailure(call, ioException);
        // throw ioException;
        return;
      } finally {
        response.close();
      }

      httpRequest.onResponse(response.code(),
              response.header("ETag"),
              response.header("Last-Modified"),
              response.header("Cache-Control"),
              response.header("Expires"),
              response.header("Retry-After"),
              response.header("x-rate-limit-reset"),
              body);
    }

    private void handleFailure(@Nullable Call call, Exception e) {
      String errorMessage = e.getMessage() != null ? e.getMessage() : "Error processing the request";
      int type = getFailureType(e);

      if (HttpLogger.logEnabled && call != null && call.request() != null) {
        String requestUrl = call.request().url().toString();
        HttpLogger.logFailure(type, errorMessage, requestUrl);
      }
      httpRequest.handleFailure(type, errorMessage);
    }

    private int getFailureType(Exception e) {
      if ((e instanceof NoRouteToHostException) || (e instanceof UnknownHostException) || (e instanceof SocketException)
              || (e instanceof ProtocolException) || (e instanceof SSLException)) {
        return CONNECTION_ERROR;
      } else if ((e instanceof InterruptedIOException)) {
        return TEMPORARY_ERROR;
      }
      return PERMANENT_ERROR;
    }
  }

  @NonNull
  private static Dispatcher getDispatcher() {
    Dispatcher dispatcher = new Dispatcher();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      // Matches core limit set on
      // https://github.com/mapbox/mapbox-gl-native/blob/master/platform/android/src/http_file_source.cpp#L192
      dispatcher.setMaxRequestsPerHost(20);
    } else {
      // Limiting concurrent request on Android 4.4, to limit impact of SSL handshake platform library crash
      // https://github.com/mapbox/mapbox-gl-native/issues/14910
      dispatcher.setMaxRequestsPerHost(10);
    }
    return dispatcher;
  }

  public static class HttpCookieInitializer {
    private static final String BASE_GATEWAY_URL = "https://gateway.mapmetrics.org/20250110/1/1/3.mvt?token=";

    public static void initializeSessionCookie(Context context, String token, Runnable onComplete) {
      // Construct the full URL with the base URL and token
      String gatewayUrl = BASE_GATEWAY_URL + token;

      // Use the same OkHttp client
      OkHttpClient client = (OkHttpClient) HttpRequestImpl.client;

      Request request = new Request.Builder()
              .url(gatewayUrl)
              .header("User-Agent", HttpRequestImpl.userAgentString)
              .build();

      client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(@NonNull Call call, @NonNull IOException e) {
          Log.e("HttpCookieInitializer", "Failed to initialize session cookie", e);
          // Run the completion handler on the main thread even if there's a failure
          if (onComplete != null) {
            new Handler(Looper.getMainLooper()).post(onComplete);
          }
        }

        @Override
        public void onResponse(@NonNull Call call, @NonNull Response response) {
          // The response should contain cookies which will be automatically
          // saved to the CookieJar by OkHttp
          Log.d("HttpCookieInitializer", "Session cookie initialized successfully");

          // Print the cookies that were received
          List<Cookie> cookies = HttpRequestImpl.getAllCookies();
          for (Cookie cookie : cookies) {
            Log.d("HttpCookieInitializer", "Cookie: " + cookie.name() + "=" + cookie.value());
          }

          // Always close the response body
          response.close();

          // Run the completion handler on the main thread
          if (onComplete != null) {
            new Handler(Looper.getMainLooper()).post(onComplete);
          }
        }
      });
    }

    // Method that accepts full URL for backward compatibility
    public static void initializeSessionCookieWithUrl(Context context, String gatewayUrl, Runnable onComplete) {
      // Use the same OkHttp client
      OkHttpClient client = (OkHttpClient) HttpRequestImpl.client;

      Request request = new Request.Builder()
              .url(gatewayUrl)
              .header("User-Agent", HttpRequestImpl.userAgentString)
              .build();

      client.newCall(request).enqueue(new Callback() {
        @Override
        public void onFailure(@NonNull Call call, @NonNull IOException e) {
          Log.e("HttpCookieInitializer", "Failed to initialize session cookie", e);
          // Run the completion handler on the main thread even if there's a failure
          if (onComplete != null) {
            new Handler(Looper.getMainLooper()).post(onComplete);
          }
        }

        @Override
        public void onResponse(@NonNull Call call, @NonNull Response response) {
          // The response should contain cookies which will be automatically
          // saved to the CookieJar by OkHttp
          Log.d("HttpCookieInitializer", "Session cookie initialized successfully");

          // Print the cookies that were received
          List<Cookie> cookies = HttpRequestImpl.getAllCookies();
          for (Cookie cookie : cookies) {
            Log.d("HttpCookieInitializer", "Cookie: " + cookie.name() + "=" + cookie.value());
          }

          // Always close the response body
          response.close();

          // Run the completion handler on the main thread
          if (onComplete != null) {
            new Handler(Looper.getMainLooper()).post(onComplete);
          }
        }
      });
    }

    // Backward compatibility method with just context and callback, using a default token
    public static void initializeSessionCookie(Context context, Runnable onComplete) {
      // Use default token for backward compatibility
      String defaultToken = "";
      initializeSessionCookie(context, defaultToken, onComplete);
    }
  }
}