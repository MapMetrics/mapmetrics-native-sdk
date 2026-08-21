#import "MMMapSession.h"

NS_ASSUME_NONNULL_BEGIN

/// YES if `url` looks like a tile request: its path's last three segments are
/// {z}/{x}/{y}.mvt. A SHAPE test, not a known-prefix test, so the same
/// predicate covers the v1 path a real style hands out
/// (`/planet20251013/12/2094/1362.mvt`) and the `/v2/tiles/...` form, and no
/// list of gateway path prefixes has to be maintained.
///
/// Shared between MMMapSession (which decides what to sign) and
/// MMMapSessionNetworkDelegate (which decides what a 401 relates to) so the
/// two can never drift apart. There is deliberately no second definition.
/// FOUNDATION_EXTERN, not plain `extern`: MMMapSession.mm and
/// MMMapSessionNetworkDelegate.mm are ObjC++ but this header is also imported
/// from plain-ObjC test files, and without C linkage the two would disagree
/// about the symbol name.
FOUNDATION_EXTERN BOOL MMURLIsTileShaped(NSURL *_Nullable url);

/// How many CONSECUTIVE hard auth failures (401 past grace, or 403) pause the
/// refresh loop. See `-hasGivenUpForTesting`.
extern const NSInteger MMMaxConsecutiveHardFailures;

/// Hard failures closer together than this are the same incident and advance
/// the counter above only once, so a burst of simultaneous tile 401s cannot
/// exhaust the budget in a second.
extern const NSTimeInterval MMMinHardFailureSpacing;

/// How long the give-up state lasts without app interaction. Foregrounding
/// clears it sooner.
extern const NSTimeInterval MMGiveUpCooldown;

@interface MMMapSession (Private)

/// Convenience form of `applyCredentialFromHeaders:responseURL:` with no
/// origin to validate against. Test seam only — production always has the
/// responding URL and must pass it, or a host named in a style document can
/// inject a credential.
- (BOOL)applyCredentialFromHeaders:(NSDictionary *)headers;

/// YES if a 401 carrying `sessionId` concerns the credential currently held,
/// and a refresh is therefore warranted.
///
/// This is the guard on the property the whole feature exists to deliver: ONE
/// billed map load per window of use. Many tiles are in flight at once, so a
/// signing-key rotation 401s all of them. Without this check the first 401
/// buys a new credential and every straggler — still carrying the now-dead
/// session id — buys ANOTHER one. `_refreshInFlight` cannot help: the network
/// serialises those responses, so each arrives after the previous refresh has
/// already completed.
///
/// `sessionId` is nil for a tile that went out unsigned; that is the bootstrap
/// case and is allowed exactly when no credential is held.
- (BOOL)shouldRefreshForResponseSessionId:(nullable NSString *)sessionId;

/// Adopts a `/v2/map-sessions` create/renew response body. Returns NO for a
/// body that would yield an unusable credential (notably a missing or past
/// `expires_at`, which would sign `e=0` and 401 on every tile).
/// Exposed so the response handling can be tested without a network round trip.
- (BOOL)adoptRefreshResponse:(nullable NSDictionary *)json;

/// Handles a non-adoptable refresh response. Exposed for the same reason.
- (void)handleRefreshFailureWithStatus:(NSInteger)status;

/// Test seam: seeds the account the way `refreshNow`'s create response
/// normally would, without a network round trip, so rollover-only tests can
/// exercise `applyCredentialFromHeaders:` realistically.
- (void)seedAccountForTesting:(NSString *)account;

/// Test seam: YES once `MMMaxConsecutiveHardFailures` consecutive hard auth
/// failures have stopped the refresh loop.
- (BOOL)hasGivenUpForTesting;

/// Test seam: how many times `refreshNow` decided a refresh was warranted and
/// was not suppressed. Counted before the origin lookup, so a test with no
/// origin observes the DECISION without any network traffic. This is what the
/// stale-401 tests assert on: every increment here is a BILLED map load in
/// production.
- (NSInteger)refreshCallCountForTesting;

/// Test seam: the pinned gateway origin, or nil if none has been established.
- (nullable NSURL *)originForTesting;

/// Buys the first window as soon as BOTH the pinned origin and the API key are
/// known, before any request exists. See the implementation for why this cannot
/// wait for -noteGatewayRequestURL: when the style is not served by the gateway.
- (void)createIfConfigured;

/// Test seam: clears the origin AND the configured flag.
///
/// -resetForTesting deliberately preserves a CONFIGURED origin, mirroring
/// production, where a pin comes from Info.plist and cannot go away. That makes
/// a pin permanent for the life of the process — and the session is a
/// singleton, so a test that pins one leaks it into every test that follows,
/// whose own URLs then fail the same-origin check and go unsigned. Any test
/// that calls -pinOriginForTesting: must undo it here in tearDown.
/// The Android sibling has resetOriginForTesting() for the same reason.
- (void)resetOriginForTesting;

/// Test seam: pins the gateway origin directly, the way -pinConfiguredOrigin
/// does from Info.plist's MLNTileServerBaseURL.
///
/// Origin LEARNING is restricted to the gateway host list, so a test that wants
/// to run against a host off that list — staging, chiefly — must pin, exactly
/// as a real staging deployment does. Info.plist cannot be written from a test,
/// hence this seam. Marks the origin as CONFIGURED, so it survives the resets
/// that clear a learned one.
- (void)pinOriginForTesting:(NSString *)baseURL;

/// Test seam: how many times the "tile host does not match the pinned origin"
/// diagnostic has been emitted. Must be at most 1 however many tiles are
/// refused — a map view issues hundreds.
- (NSInteger)originMismatchLogCountForTesting;

/// Test seam: back-dates the give-up and last-counted-failure timestamps by
/// `seconds`, so spacing and cool-down behaviour can be exercised without
/// sleeping for `MMMinHardFailureSpacing` or `MMGiveUpCooldown`.
- (void)rewindFailureClocksForTesting:(NSTimeInterval)seconds;

/// The foreground handler: re-installs the network delegate and clears the
/// give-up state. It does NOT refresh — there is no renewal timer, and a
/// foreground entry is not evidence that a map is on screen. Exposed so a test
/// can post the foreground transition without a UIApplication.
- (void)applicationWillEnterForeground;

/// Starts caching `MLNSettings.apiKey` into this session, via KVO, so that
/// `refreshNow` never has to read it back off MLNSettings.
///
/// `refreshNow` runs on NSURLSession callback threads, and
/// `+[MLNSettings sharedSettings]` dispatch_syncs onto the main queue for
/// EVERY off-main call — so reading the key there blocks each billing call on
/// the queue that renders the map, and deadlocks outright when the main thread
/// is waiting on that same callback. Production calls this exactly once, from
/// `+load`, on the main thread. Idempotent.
///
/// Exposed here so a test can exercise the "key set BEFORE the observation was
/// registered" ordering on a throwaway instance — the singleton's registration
/// has already happened by the time any test runs.
- (void)beginObservingAPIKey;

/// Test seam: the API key currently cached by the observation above, or nil if
/// no key has ever been set. This is the exact value `refreshNow` sends.
- (nullable NSString *)cachedAPIKeyForTesting;

@end

NS_ASSUME_NONNULL_END
