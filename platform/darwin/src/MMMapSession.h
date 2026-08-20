#import <Foundation/Foundation.h>
#import "MLNFoundation.h"

NS_ASSUME_NONNULL_BEGIN

/**
 Owns the v2 map-session credential for the whole process.

 The credential is a signed claim the client carries; the gateway stores nothing.
 See wiki/synthesis/2026-08-18-v2-map-session-client-contract.md.

 BILLING: one map load per 30-minute window of use. Creating a session bills 1
 and letting it expire so the gateway rolls it over also bills 1 — exactly 1 per
 window, however many dead-credential tiles arrive at once. Tiles are free.

 THERE IS NO RENEWAL TIMER. Refresh happens one of two ways and no other:
 rollover — a tile carrying an expired-but-valid credential is served inline and
 the replacement comes back in `X-Map-Session-*` headers, adopted by
 `-applyCredentialFromHeaders:responseURL:` — and, when the gateway REJECTS the
 credential outright, the spaced-and-budgeted 401/403 path via `-refreshNow`.

 Rollover is not merely equivalent to a timer, it is structurally safer. A timer
 had to be gated on recorded activity and on the app being foregrounded to stop
 it billing a map nobody was looking at: a phone left on a desk overnight was
 measured billing ~16 windows for zero tile requests, and reopening the app to
 ANY screen billed a map load with no map on screen. Rollover cannot do either,
 because it is demand-driven BY CONSTRUCTION — it fires only when a tile is
 actually requested. Those two invariants stopped being guards that can be got
 wrong and became facts about the shape of the system.

 Thread safety: every method here is safe to call from any thread. The network
 delegate callbacks that drive it run off the main thread.
 */
MLN_EXPORT
@interface MMMapSession : NSObject

@property (class, nonatomic, readonly) MMMapSession *sharedSession;

/// Seconds until the held credential expires; 0 if none is held.
@property (nonatomic, readonly) NSTimeInterval secondsUntilExpiry;

/// Appends u/s/e/a/k/sig if `url` is a v2 tile URL and a credential is held,
/// preserving any query items the URL already carried.
/// Returns `url` unchanged otherwise. Never throws.
- (NSURL *)signedURLForRequestURL:(NSURL *)url;

/// Adopts a credential from X-Map-Session-* response headers (rollover only).
/// Returns YES if a complete, newer credential was adopted.
///
/// `responseURL` is the URL that produced these headers. It must come from the
/// pinned gateway origin — otherwise any host merely NAMED in a style document
/// could inject a credential the SDK would then sign every tile with.
- (BOOL)applyCredentialFromHeaders:(NSDictionary *)headers responseURL:(nullable NSURL *)responseURL;

/// Creates or renews the credential. Asynchronous; safe to call repeatedly —
/// concurrent calls coalesce onto one in-flight request.
- (void)refreshNow;

/// Test seam: drops the credential and any in-flight state.
- (void)resetForTesting;

@end

NS_ASSUME_NONNULL_END
