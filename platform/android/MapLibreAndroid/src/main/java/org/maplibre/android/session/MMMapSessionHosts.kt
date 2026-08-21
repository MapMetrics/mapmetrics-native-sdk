package org.maplibre.android.session

import okhttp3.HttpUrl

/**
 * The single source of truth for "is this URL one of our gateways?".
 *
 * THIS PREDICATE IS BILLING-RELEVANT AND KEY-RELEVANT. A host it returns `true` for can become the
 * origin the customer's PERMANENT API key is POSTed to by [MMMapSession.refreshNow]. Widening it
 * hands that key to a third party; narrowing it silently un-authenticates tiles, which surfaces as
 * a blank map rather than an error.
 *
 * It gates LEARNING ONLY. An app that pins its origin through
 * `MMMapSessionInterceptor.GATEWAY_ORIGIN_META_DATA` has explicitly chosen where its key may go,
 * and that choice is not second-guessed here — see [MMMapSession.pinConfiguredOrigin]. That is the
 * channel staging and any self-hosted gateway use, and it is why this list does not need a staging
 * entry.
 *
 * Mirrors `mapmetrics_hosts.ts` in the mapmetrics-gl SDK. Keep the two lists in step.
 */
internal object MMMapSessionHosts {

    /**
     * Exact hostnames, never suffixes.
     *
     * `gateway.mapmetrics.org` used to sit here and in the gl list. It is NXDOMAIN — it never
     * resolved, so it never fired, and it was pure surface area in a list whose whole job is to be
     * narrow. Do not restore it without checking DNS first.
     */
    val GATEWAY_HOSTS: Set<String> = setOf(
        "gateway.mapmetrics-atlas.net"
    )

    /**
     * EXACT hostname match. Never `endsWith`, never `contains`.
     *
     * A suffix test would accept `gateway.mapmetrics-atlas.net.evil.com`, and a substring test
     * would accept anything with the string anywhere in it — including a path or a query
     * parameter. The gateway itself shipped an `endsWith` origin check that was bypassable in
     * exactly this way; do not reintroduce the shape here.
     */
    fun isGatewayHost(host: String?): Boolean = host != null && GATEWAY_HOSTS.contains(host)

    /** Convenience for call sites that hold a parsed URL. */
    fun isGatewayUrl(url: HttpUrl): Boolean = isGatewayHost(url.host)
}
