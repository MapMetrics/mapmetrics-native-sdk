#import "MMMapSession.h"
#import "MMMapSession_Private.h"
#import "MMMapSessionNetworkDelegate.h"
#import "MLNSettings.h"
#import "MLNSettings_Private.h"   // +sharedSettings, for the API-key observation

#include <mbgl/interface/native_apple_interface.h>

#if TARGET_OS_IPHONE
#import <UIKit/UIKit.h>
#endif

// A tile request is recognised by the SHAPE of its path -- the last three
// segments being {z}/{x}/{y}.mvt -- rather than by a known path prefix.
//
// The gateway now accepts a v2 session signature on the EXISTING v1 tile path,
// not only on /v2/tiles. That inverts the migration: a new SDK can sign the
// tile URL the style already gave it, and the customer moves to session
// billing with no style change and no coordination. Matching on the literal
// "/v2/tiles/" would have ignored every real style's tile URLs and never
// engaged at all. Both of these are tiles and one predicate covers them:
//
//     https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt?token=...
//     https://gateway-mapatlas-staging.example.workers.dev/v2/tiles/12/2094/1362.mvt
//
// A shape test also needs no list of known prefixes to be maintained as the
// gateway's paths change. What it does NOT do is decide who may be trusted:
// broadening this broadens what could become the LEARNED origin, so the
// origin check in -signedURLForRequestURL: (https-only, learned once, and
// scheme/host/port must all match -- see MMSameOrigin) is now carrying more
// weight, not less.
BOOL MMURLIsTileShaped(NSURL *_Nullable url) {
    NSString *path = url.path;
    if (path.length == 0) return NO;
    static NSRegularExpression *re;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        re = [NSRegularExpression regularExpressionWithPattern:@"/\\d+/\\d+/\\d+\\.mvt$"
                                                      options:0
                                                        error:NULL];
    });
    return [re numberOfMatchesInString:path
                               options:0
                                 range:NSMakeRange(0, path.length)] > 0;
}

// Three, not one and not ten. A single hard failure is legitimately reachable
// during a signing-key rotation, and giving up on it would blank a map that
// would have recovered on the next try. Three consecutive failures that are
// SPACED BY `MMMinHardFailureSpacing` (see below) with no intervening success
// are not a wobble: the only thing that produces them is a credential the
// gateway will never accept -- a misconfigured or revoked MLNApiKey, or a key
// without map-session scope. Retrying that forever costs a request per lapse
// for the life of the process and tells the developer nothing, so we stop and
// say why -- but only until `MMGiveUpCooldown` elapses or the app is
// foregrounded, because "never accepted" is not something we can prove.
const NSInteger MMMaxConsecutiveHardFailures = 3;

// The budget above counts ATTEMPTS, not round trips. Every unsigned tile that
// 401s drives another refreshNow immediately, so a hundred in-flight tiles can
// burn three failures in well under a second -- a two-second gateway wobble
// would then blank the map for the life of the process. Failures closer
// together than this are the same incident and are not counted twice. 30s is
// chosen to be longer than any plausible burst of in-flight tile responses
// (they arrive within a second or two of each other) and far shorter than a
// session window, so three counted failures really do span more than a minute.
const NSTimeInterval MMMinHardFailureSpacing = 30;

// Even a "permanent" failure may not be: a key can be re-provisioned, a scope
// granted, or a gateway deployment rolled back, none of which the app sees.
// After this long we try once more. 10 minutes is long enough that a genuinely
// revoked key costs ~6 requests an hour rather than one per lapse, and short
// enough that a fixed deployment recovers without the user relaunching. The
// foreground reset below is the fast path; this is for a map left open.
const NSTimeInterval MMGiveUpCooldown = 600;

// Same-origin: SCHEME, HOST AND PORT, all three -- not host alone.
//
// `_origin` has always been built from all three (see -pinConfiguredOrigin and
// the learning branch in -signedURLForRequestURL:); only the comparison was
// narrow. Host alone meant that, pinned to https://gw.example.com, the SDK
// would sign a tile for http://gw.example.com -- putting the session signature
// on the wire in CLEARTEXT -- and would accept X-Map-Session-* from a
// plaintext response, letting anyone on the path mint the credential the SDK
// then signs with. A non-default port on the same host is the same class of
// mistake.
//
// This mirrors Android, which already compares all three (finding I4 of the
// Android review; iOS never received it). The two platforms implement one
// shared contract and must not disagree about a security check.
//
// Deliberately NOT normalised: `https://h` and `https://h:443` are the same
// origin to a browser but compare unequal here, exactly as on Android. A
// configured MLNTileServerBaseURL must therefore be written the way the tile
// URLs are. Diverging from Android to be clever here would reintroduce the
// problem this fixes.
static BOOL MMSameOrigin(NSURL *url, NSURL *origin) {
    if (url == nil || origin == nil) return NO;
    if (url.host.length == 0 || origin.host.length == 0) return NO;
    if (![url.host isEqualToString:origin.host]) return NO;
    if (url.scheme.length == 0 || origin.scheme.length == 0) return NO;
    if (![url.scheme isEqualToString:origin.scheme]) return NO;
    // -isEqual:, not -isEqualToNumber:, so that a nil on either side is handled
    // rather than being passed to a method declared nonnull. Both nil (the
    // default-port case, which is the normal one) is a match.
    NSNumber *a = url.port, *b = origin.port;
    return a == b || [a isEqual:b];
}

@implementation MMMapSession {
    NSLock *_lock;
    NSString *_account, *_sessionId, *_sig, *_keyId;
    NSTimeInterval _exp, _sae;
    NSURL *_origin;          // pinned from configuration, or learned once
    BOOL _originIsConfigured;
    BOOL _refreshInFlight;
    NSInteger _hardFailures;
    BOOL _gaveUp;
    NSTimeInterval _gaveUpAt;
    NSTimeInterval _lastCountedFailureAt;
    BOOL _loggedOriginMismatch;
    NSInteger _originMismatchLogs;   // test observation only
    NSInteger _refreshCalls;   // test observation only; see refreshCallCountForTesting
    // The MLNApiKey, CACHED. See -beginObservingAPIKey for why refreshNow may
    // not read it from MLNSettings itself.
    NSString *_cachedApiKey;
    MLNSettings *_observedSettings;
    BOOL _observingAPIKey;
}

+ (void)load {
    // The SDK has no init hook and no public entry point for this -- the whole
    // design is that the app developer changes nothing. +load is what
    // MLNSettings already uses to read Info.plist, so it is the established
    // pattern here.
    //
    // Installing the delegate is cheap and side-effect-free: no network call
    // happens until a v2 tile URL is actually seen.
    [MMMapSessionNetworkDelegate install];
    // Start caching the API key HERE, on the main thread, at launch -- see
    // -beginObservingAPIKey. This is also why the singleton is created eagerly:
    // registering the observation is the one thing that must not happen on a
    // network callback thread, so it is done before any traffic can exist.
    [[self sharedSession] beginObservingAPIKey];
}

+ (MMMapSession *)sharedSession {
    static MMMapSession *shared;
    static dispatch_once_t once;
    dispatch_once(&once, ^{ shared = [[MMMapSession alloc] init]; });
    return shared;
}

- (instancetype)init {
    if ((self = [super init])) {
        _lock = [[NSLock alloc] init];
        [self pinConfiguredOrigin];
        // The foreground hook exists for the give-up escape and to re-assert the
        // network delegate -- NOT to renew. There is no renewal to schedule.
        [[NSNotificationCenter defaultCenter] addObserver:self
            selector:@selector(applicationWillEnterForeground)
#if TARGET_OS_IPHONE
                name:UIApplicationWillEnterForegroundNotification
#else
                name:@"UIApplicationWillEnterForegroundNotification"
#endif
              object:nil];
    }
    return self;
}

- (void)dealloc {
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    // The +load singleton is never deallocated, so in production this is
    // academic -- but KVO registrations are not zeroed for you the way
    // notification-centre ones are, and the API-key tests construct throwaway
    // instances, so an un-removed observation would leave MLNSettings holding
    // a dangling observer and crash the next time the key is set.
    if (_observingAPIKey) [_observedSettings removeObserver:self forKeyPath:@"apiKey"];
}

// `refreshNow` runs on NSURLSession callback threads. Reading
// `[MLNSettings apiKey]` there goes through +[MLNSettings sharedSettings],
// which dispatch_syncs onto the MAIN QUEUE for every off-main call -- the
// isMainThread check sits OUTSIDE its dispatch_once, so this is not a one-time
// setup cost. Every session create would therefore block on the queue that is
// simultaneously rendering the map, and if the main thread is itself waiting
// on anything downstream of that network callback the two wait on each other
// and the app hangs.
//
// So the key is OBSERVED rather than re-read, exactly as MLNOfflineStorage
// does for the same property (MLNOfflineStorage.mm, -init / -observeValue...).
// NSKeyValueObservingOptionInitial covers the ordering where the key was
// already set before we got here -- notably Info.plist's MLNApiKey, which
// MLNSettings' own +load reads and whose +load may run before ours -- and the
// change notifications cover a key set programmatically afterwards.
- (void)beginObservingAPIKey {
    MLNSettings *settings = [MLNSettings sharedSettings];
    if (!settings) return;   // designables agent on macOS returns nil
    [_lock lock];
    BOOL already = _observingAPIKey;
    if (!already) { _observingAPIKey = YES; _observedSettings = settings; }
    [_lock unlock];
    if (already) return;
    // Outside the lock: addObserver: delivers the Initial notification
    // SYNCHRONOUSLY and our observer takes `_lock`, which is not reentrant.
    [settings addObserver:self
               forKeyPath:@"apiKey"
                  options:(NSKeyValueObservingOptionInitial | NSKeyValueObservingOptionNew)
                  context:NULL];
}

- (void)observeValueForKeyPath:(NSString *)keyPath
                      ofObject:(id)object
                        change:(NSDictionary<NSString *, id> *)change
                       context:(void *)context {
    // Compared against the CAPTURED settings object, not +[MLNSettings
    // sharedSettings]: the host may set the key from any thread, so this
    // callback can run off the main thread, and calling sharedSettings here
    // would reintroduce exactly the main-queue hop this cache exists to remove.
    if ([keyPath isEqualToString:@"apiKey"]) {
        id apiKey = change[NSKeyValueChangeNewKey];
        [_lock lock];
        BOOL mine = (object == _observedSettings);
        if (mine && [apiKey isKindOfClass:NSString.class]) _cachedApiKey = [apiKey copy];
        [_lock unlock];
        if (mine) return;
    }
    [super observeValueForKeyPath:keyPath ofObject:object change:change context:context];
}

- (NSString *)cachedAPIKeyForTesting {
    [_lock lock]; NSString *key = _cachedApiKey; [_lock unlock];
    return key;
}

// The host application may configure the gateway explicitly. When it has, the
// origin is PINNED here and never learned from traffic -- `refreshNow` POSTs
// the permanent, full-scope MLNApiKey to this origin, so letting an arbitrary
// URL seen in a style document decide where that goes would hand the
// customer's key to whoever wrote the style.
- (void)pinConfiguredOrigin {
    NSString *configured = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"MLNTileServerBaseURL"];
    NSURL *url = configured.length ? [NSURL URLWithString:configured] : nil;
    if (!url.host.length) return;
    NSURLComponents *o = [[NSURLComponents alloc] init];
    o.scheme = url.scheme; o.host = url.host; o.port = url.port;
    [_lock lock];
    _origin = o.URL;
    _originIsConfigured = YES;
    [_lock unlock];
}

- (void)applicationWillEnterForeground {
    // MLNNetworkConfiguration holds its delegate WEAKLY and exposes it
    // publicly, so a host app that installs its own displaces ours and
    // silently unhooks signing. Re-assert on every foreground rather than
    // installing exactly once and never checking again.
    [MMMapSessionNetworkDelegate install];
    // A way back out of give-up. Once `_gaveUp` is set nothing else can clear
    // it in practice: the resets live in adoptRefreshResponse: and
    // applyCredentialFromHeaders:, both of which need traffic that can no
    // longer happen (no credential means unsigned tiles, and unsigned tiles
    // never carry rollover headers). Without this a transient fault -- or a
    // key fixed on the server while the app was backgrounded -- costs a
    // process restart. Backgrounding and returning is the user's natural
    // "try again", so honour it.
    [_lock lock];
    _gaveUp = NO; _gaveUpAt = 0;
    _hardFailures = 0; _lastCountedFailureAt = 0;
    [_lock unlock];
}

- (void)resetForTesting {
    [_lock lock];
    _account = _sessionId = _sig = _keyId = nil;
    _exp = _sae = 0; _refreshInFlight = NO;
    _hardFailures = 0; _gaveUp = NO; _refreshCalls = 0;
    _gaveUpAt = 0; _lastCountedFailureAt = 0;
    _loggedOriginMismatch = NO; _originMismatchLogs = 0;
    if (!_originIsConfigured) _origin = nil;
    [_lock unlock];
}

- (NSTimeInterval)secondsUntilExpiry {
    [_lock lock];
    NSTimeInterval left = _exp > 0 ? _exp - [[NSDate date] timeIntervalSince1970] : 0;
    [_lock unlock];
    return left > 0 ? left : 0;
}

- (NSURL *)signedURLForRequestURL:(NSURL *)url {
    if (!MMURLIsTileShaped(url)) return url;

    [_lock lock];
    // Learn the gateway origin from the tile URL itself when nothing is
    // configured, so no setup is required and the SDK works against staging
    // and production unchanged. Two constraints make that safe enough to keep:
    // https only (the API key is POSTed here later), and learned exactly ONCE
    // -- a later tile URL on a different host can never re-point the origin.
    if (!_origin && [url.scheme isEqualToString:@"https"] && url.host.length) {
        NSURLComponents *o = [[NSURLComponents alloc] init];
        o.scheme = url.scheme; o.host = url.host; o.port = url.port;
        _origin = o.URL;
    }
    // Never hand the credential to anywhere but the pinned origin -- scheme,
    // host and port. See MMSameOrigin: http:// on the pinned host is a
    // different origin and must not receive the signature in cleartext.
    BOOL sameOrigin = MMSameOrigin(url, _origin);
    // A credential is only usable once we know which account it belongs to:
    // accountId is inside the HMAC payload the gateway signs, so a missing
    // or empty account can never verify. The gateway treats an empty `u` as
    // malformed and returns 401 — and Task 1 made 401 retryable, so signing
    // with an unknown account would turn into an infinite retry loop of
    // blank tiles instead of the recoverable "not signed yet" state below.
    BOOL haveCredential = sameOrigin && (_sig != nil && _sessionId != nil && _account.length > 0);
    // Refusing to sign because the request is not on the pinned origin is a
    // CONFIGURATION fault, not a transient state, and it is otherwise entirely
    // silent: every tile goes out unsigned, the gateway 401s, and the
    // session-identity gate correctly declines to buy a window for a 401 that
    // is not about our credential -- so the map is blank forever with nothing
    // logged. Say so, once. A map view issues hundreds of tile requests and a
    // per-tile log would drown the console (and the reader).
    //
    // The origins are logged WHOLE rather than by host, because since the
    // comparison covers scheme and port a matching host is no longer proof the
    // origins agree -- "gw.example.com does not match gw.example.com" would be
    // the single most confusing diagnostic this SDK could emit.
    BOOL logMismatch = NO;
    NSString *originDesc = _origin.absoluteString;
    NSURLComponents *rc = [[NSURLComponents alloc] init];
    rc.scheme = url.scheme; rc.host = url.host; rc.port = url.port;
    NSString *requestDesc = rc.URL.absoluteString;
    if (_origin && !sameOrigin && !_loggedOriginMismatch) {
        _loggedOriginMismatch = YES;
        _originMismatchLogs++;
        logMismatch = YES;
    }
    NSArray<NSURLQueryItem *> *items = haveCredential ? @[
        [NSURLQueryItem queryItemWithName:@"u"   value:_account],
        [NSURLQueryItem queryItemWithName:@"s"   value:_sessionId],
        [NSURLQueryItem queryItemWithName:@"e"   value:[@((long long)_exp) stringValue]],
        [NSURLQueryItem queryItemWithName:@"a"   value:[@((long long)_sae) stringValue]],
        [NSURLQueryItem queryItemWithName:@"k"   value:_keyId ?: @"1"],
        [NSURLQueryItem queryItemWithName:@"sig" value:_sig]
    ] : nil;
    [_lock unlock];

    if (logMismatch) {
        // Outside the lock: errorLog: is foreign code and must never run with
        // our lock held.
        [MLNNativeNetworkManager.sharedManager
            errorLog:@"MMMapSession: tile origin \"%@\" does not match the configured map-session "
                      "origin \"%@\", so tiles will NOT be signed and the map will stay blank. "
                      "Scheme, host and port must all match. Set MLNTileServerBaseURL to the "
                      "origin that serves the tiles.",
                     requestDesc ?: @"(none)", originDesc ?: @"(none)"];
    }

    if (!haveCredential) return url;   // Task 1's patch lets the 401 recover.

    NSURLComponents *c = [NSURLComponents componentsWithURL:url resolvingAgainstBaseURL:NO];
    // APPEND. The URL may already carry query items that the gateway or the
    // style depends on (the header promises this, and MapLibre does append
    // params of its own); replacing the query wholesale would drop them.
    // Any stale copy of our OWN params is dropped first so re-signing the
    // same URL cannot duplicate them.
    NSMutableArray<NSURLQueryItem *> *merged =
        [NSMutableArray arrayWithArray:c.queryItems ?: @[]];
    NSSet<NSString *> *ours = [NSSet setWithArray:@[@"u", @"s", @"e", @"a", @"k", @"sig"]];
    [merged filterUsingPredicate:[NSPredicate predicateWithBlock:
        ^BOOL(NSURLQueryItem *item, NSDictionary *bindings) {
            return ![ours containsObject:item.name];
        }]];
    [merged addObjectsFromArray:items];
    c.queryItems = merged;
    return c.URL ?: url;
}

- (BOOL)applyCredentialFromHeaders:(NSDictionary *)headers {
    return [self applyCredentialFromHeaders:headers responseURL:nil];
}

- (BOOL)applyCredentialFromHeaders:(NSDictionary *)headers responseURL:(NSURL *)responseURL {
    NSString *sid = headers[@"X-Map-Session-Id"];
    NSString *sig = headers[@"X-Map-Session-Sig"];
    NSString *exp = headers[@"X-Map-Session-Exp"];
    NSString *end = headers[@"X-Map-Session-Ends"];
    NSString *kid = headers[@"X-Map-Session-Key-Id"];
    // All five or nothing. A partial set means a rollover we cannot use, and
    // storing half a credential would sign every later tile invalidly.
    if (!sid || !sig || !exp || !end || !kid) return NO;

    [_lock lock];
    // Only the pinned gateway may mint credentials. Without this, a style
    // document that references a tile URL on an attacker-controlled host lets
    // that host return X-Map-Session-* headers and own the SDK's credential.
    //
    // The SAME origin test as signing uses -- scheme, host and port. Guarding
    // only one of the two paths would be pointless: accepting a credential
    // from http:// on the pinned host hands the session to anyone on the
    // network path, and every tile afterwards is signed with it. Android
    // applies its check in both places too.
    if (_origin && responseURL && !MMSameOrigin(responseURL, _origin)) {
        [_lock unlock];
        return NO;
    }
    // A rollover only ever replaces sessionId/sig/exp/sae for the account
    // that created the session in the first place — that's why these
    // headers carry no account field of their own. _account is set once, by
    // refreshNow's create response, and persists across rollovers. Without
    // it we cannot say which account this rollover belongs to, so adopting
    // it would be meaningless (and, per signedURLForRequestURL:, unsignable
    // anyway).
    if (_account.length == 0) { [_lock unlock]; return NO; }
    BOOL newer = exp.doubleValue > _exp;
    if (newer) {
        _sessionId = sid; _sig = sig; _keyId = kid;
        _exp = exp.doubleValue; _sae = end.doubleValue;
        _hardFailures = 0;   // a working credential clears the give-up counter
        _lastCountedFailureAt = 0;
        _gaveUp = NO; _gaveUpAt = 0;
    }
    [_lock unlock];
    return newer;
}

- (BOOL)shouldRefreshForResponseSessionId:(NSString *)sessionId {
    [_lock lock];
    BOOL should;
    if (sessionId.length == 0) {
        // An unsigned tile 401ing is the bootstrap case: no credential yet.
        // If we DO hold one, an unsigned request is none of our business and
        // must not buy a window.
        should = (_sig == nil);
    } else {
        // Stale news: this 401 belongs to a credential we have already
        // replaced. Refreshing again would bill a second map load for the
        // same rotation.
        should = [sessionId isEqualToString:_sessionId];
    }
    [_lock unlock];
    return should;
}

- (void)refreshNow {
    [_lock lock];
    // Cool-down: give-up is a pause, not a verdict. See MMGiveUpCooldown.
    if (_gaveUp && [[NSDate date] timeIntervalSince1970] - _gaveUpAt >= MMGiveUpCooldown) {
        _gaveUp = NO; _gaveUpAt = 0;
        _hardFailures = 0; _lastCountedFailureAt = 0;
    }
    if (_refreshInFlight || _gaveUp) { [_lock unlock]; return; }
    // Counted here -- after the suppression gates, before the origin check --
    // so a test can observe "a refresh was warranted and not suppressed"
    // without an origin, and therefore without any network traffic.
    _refreshCalls++;
    if (!_origin) { [_lock unlock]; return; }
    _refreshInFlight = YES;
    BOOL canRenew = (_sig != nil && _sae > [[NSDate date] timeIntervalSince1970]);
    NSURL *origin = _origin;
    NSString *account = _account, *sessionId = _sessionId, *sig = _sig, *keyId = _keyId;
    NSTimeInterval exp = _exp, sae = _sae;
    // Copied out with the rest of the state. MLNSettings is deliberately NOT
    // consulted here -- see -beginObservingAPIKey.
    NSString *apiKey = _cachedApiKey;
    [_lock unlock];

    NSURLComponents *c = [NSURLComponents componentsWithURL:origin resolvingAgainstBaseURL:NO];
    if (canRenew) {
        c.path = @"/v2/map-sessions/renew";
        c.queryItems = @[
            [NSURLQueryItem queryItemWithName:@"u"   value:account],
            [NSURLQueryItem queryItemWithName:@"s"   value:sessionId],
            [NSURLQueryItem queryItemWithName:@"e"   value:[@((long long)exp) stringValue]],
            [NSURLQueryItem queryItemWithName:@"a"   value:[@((long long)sae) stringValue]],
            [NSURLQueryItem queryItemWithName:@"k"   value:keyId ?: @"1"],
            [NSURLQueryItem queryItemWithName:@"sig" value:sig]
        ];
    } else {
        c.path = @"/v2/map-sessions";
        c.queryItems = @[[NSURLQueryItem queryItemWithName:@"token"
                                                    value:apiKey ?: @""]];
    }

    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:c.URL];
    req.HTTPMethod = @"POST";
    [[[NSURLSession sharedSession] dataTaskWithRequest:req
        completionHandler:^(NSData *data, NSURLResponse *resp, NSError *err) {
        NSInteger status = [(NSHTTPURLResponse *)resp statusCode];
        NSDictionary *j = data ? [NSJSONSerialization JSONObjectWithData:data options:0 error:NULL] : nil;
        if (status == 200 && [self adoptRefreshResponse:j]) return;
        [self handleRefreshFailureWithStatus:status];
    }] resume];
}

- (BOOL)adoptRefreshResponse:(NSDictionary *)j {
    if (![j isKindOfClass:NSDictionary.class]) return NO;
    // A 200 whose body does not carry a FUTURE expiry is not a credential.
    // Adopting it anyway yields _exp = 0, which signs `e=0` on every tile and
    // 401s on every one of them -- feeding exactly the repeat-refresh loop
    // `shouldRefreshForResponseSessionId:` exists to stop.
    NSTimeInterval exp = [j[@"expires_at"] doubleValue];
    NSString *sid = j[@"session_id"], *sig = j[@"sig"];
    if (exp <= [[NSDate date] timeIntervalSince1970] || !sid.length || !sig.length) return NO;

    [_lock lock];
    _account   = j[@"account"] ?: _account;
    _sessionId = sid;
    _sig       = sig;
    _keyId     = [j[@"key_id"] description];
    _exp       = exp;
    _sae       = [j[@"session_ends_at"] doubleValue];
    _refreshInFlight = NO;
    _hardFailures = 0; // success clears the consecutive-failure count
    _lastCountedFailureAt = 0;
    _gaveUp = NO; _gaveUpAt = 0;
    [_lock unlock];
    return YES;
}

- (void)handleRefreshFailureWithStatus:(NSInteger)status {
    // 401 past grace means "create a new one" (the gateway says so in
    // must_create_new). 403 means the key is not permitted to do this at all.
    // Both must drop the credential: leaving it in place keeps `canRenew`
    // true, so every later refresh takes the renew branch and fails the same
    // way forever.
    BOOL hard = (status == 401 || status == 403);
    NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
    [_lock lock];
    if (hard) {
        // The credential is dropped on EVERY hard failure -- it demonstrably
        // does not work, and keeping it would leave `canRenew` true.
        _sig = nil; _sessionId = nil; _exp = 0;
        // The COUNT, though, only advances for failures far enough apart to be
        // separate attempts. A burst of 401s from tiles that were all in flight
        // together is one incident, not three. See MMMinHardFailureSpacing.
        if (now - _lastCountedFailureAt >= MMMinHardFailureSpacing) {
            _hardFailures++;
            _lastCountedFailureAt = now;
        }
    }
    BOOL givingUp = hard && !_gaveUp && _hardFailures >= MMMaxConsecutiveHardFailures;
    if (givingUp) { _gaveUp = YES; _gaveUpAt = now; }
    NSInteger failures = _hardFailures;
    _refreshInFlight = NO;
    [_lock unlock];

    if (givingUp) {
        // The developer would otherwise see a permanently blank map and no
        // signal at all. errorLog: is the channel the SDK's own HTTP layer
        // already uses (http_file_source.mm).
        [MLNNativeNetworkManager.sharedManager
            errorLog:@"MMMapSession: giving up after %ld consecutive map-session auth failures "
                      "(last status %ld). Tiles will not be signed. Check that MLNApiKey is set "
                      "and permitted to create v2 map sessions. Refreshing resumes when the app "
                      "next enters the foreground, or after %ld seconds.",
                     (long)failures, (long)status, (long)MMGiveUpCooldown];
    }
}

- (void)seedAccountForTesting:(NSString *)account {
    [_lock lock];
    _account = account;
    [_lock unlock];
}

- (BOOL)hasGivenUpForTesting {
    [_lock lock]; BOOL g = _gaveUp; [_lock unlock];
    return g;
}

- (NSInteger)refreshCallCountForTesting {
    [_lock lock]; NSInteger n = _refreshCalls; [_lock unlock];
    return n;
}

- (NSURL *)originForTesting {
    [_lock lock]; NSURL *o = _origin; [_lock unlock];
    return o;
}

- (NSInteger)originMismatchLogCountForTesting {
    [_lock lock]; NSInteger n = _originMismatchLogs; [_lock unlock];
    return n;
}

- (void)rewindFailureClocksForTesting:(NSTimeInterval)seconds {
    [_lock lock];
    if (_lastCountedFailureAt > 0) _lastCountedFailureAt -= seconds;
    if (_gaveUpAt > 0) _gaveUpAt -= seconds;
    [_lock unlock];
}

@end
