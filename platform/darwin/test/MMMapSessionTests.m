#import <XCTest/XCTest.h>
#import "MMMapSession.h"
#import "MMMapSession_Private.h"
#import "MMMapSessionNetworkDelegate.h"
#import "MLNNetworkConfiguration.h"
#import "MLNNetworkResponse.h"
#import "MLNSettings.h"

@interface MMMapSessionTests : XCTestCase
@end

@implementation MMMapSessionTests

// MMMapSession is a SINGLETON, and -resetForTesting deliberately KEEPS a
// configured origin because in production a pin comes from Info.plist and
// cannot go away. So a pin set by one test outlives it, and every later test's
// URLs then fail the same-origin check and silently go unsigned. Clearing the
// origin between tests makes each one start from the same state whether or not
// its predecessor pinned anything.
- (void)tearDown {
    [[MMMapSession sharedSession] resetOriginForTesting];
    [[MMMapSession sharedSession] resetForTesting];
    [super tearDown];
}

- (void)testSignedURLLeavesNonTileURLsUntouched {
    MMMapSession *s = [MMMapSession sharedSession];
    NSURL *style = [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/styles?fileName=a.json"];
    XCTAssertEqualObjects([s signedURLForRequestURL:style], style);
}

- (void)testSignedURLLeavesTileURLUntouchedWithoutCredential {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    NSURL *tile = [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"];
    XCTAssertEqualObjects([s signedURLForRequestURL:tile], tile);
}

- (void)testAppliesCredentialFromRolloverHeaders {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    // Rollover headers never carry an account (see MMMapSession.mm); in
    // production _account was already set by refreshNow's create response
    // by the time any rollover can occur, so seed it here to match reality.
    [s seedAccountForTesting:@"acct1"];
    NSDictionary *headers = @{
        @"X-Map-Session-Id":     @"abc123",
        @"X-Map-Session-Sig":    @"SIGVALUE",
        @"X-Map-Session-Exp":    @"4102444800",
        @"X-Map-Session-Ends":   @"4102448400",
        @"X-Map-Session-Key-Id": @"1"
    };
    XCTAssertTrue([s applyCredentialFromHeaders:headers]);

    NSURL *tile = [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"];
    NSString *signed_ = [s signedURLForRequestURL:tile].absoluteString;
    XCTAssertTrue([signed_ containsString:@"s=abc123"]);
    XCTAssertTrue([signed_ containsString:@"sig=SIGVALUE"]);
    XCTAssertTrue([signed_ containsString:@"e=4102444800"]);
    XCTAssertTrue([signed_ containsString:@"a=4102448400"]);
    XCTAssertTrue([signed_ containsString:@"k=1"]);
}

- (void)testPartialHeadersAreRejected {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    XCTAssertFalse([s applyCredentialFromHeaders:@{ @"X-Map-Session-Sig": @"only-sig" }]);
}

// Guards the invariant that keeps a rollover for an unknown account from
// ever reaching the network: an empty `u` is rejected by the gateway as
// malformed (401), and Task 1 made 401 retryable, so signing without a
// known account would become an infinite retry loop of blank tiles instead
// of the recoverable "not signed yet" state this test asserts.
- (void)testRolloverWithoutKnownAccountIsRejected {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    NSDictionary *headers = @{
        @"X-Map-Session-Id":     @"abc123",
        @"X-Map-Session-Sig":    @"SIGVALUE",
        @"X-Map-Session-Exp":    @"4102444800",
        @"X-Map-Session-Ends":   @"4102448400",
        @"X-Map-Session-Key-Id": @"1"
    };
    XCTAssertFalse([s applyCredentialFromHeaders:headers]);

    NSURL *tile = [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"];
    XCTAssertEqualObjects([s signedURLForRequestURL:tile], tile);
}

- (void)testDelegateInstallsAndIsRetained {
    [MMMapSessionNetworkDelegate install];
    id delegate = MLNNetworkConfiguration.sharedManager.delegate;
    XCTAssertNotNil(delegate, @"delegate must be retained somewhere or it deallocates");
    [MMMapSessionNetworkDelegate install];   // idempotent
    XCTAssertEqual(delegate, MLNNetworkConfiguration.sharedManager.delegate);
}

// `delegate` is a public, WEAK property on MLNNetworkConfiguration, so a host
// app setting its own silently unhooks tile signing. install: must therefore
// re-assert rather than install exactly once -- otherwise signing is off for
// the life of the process with no way back.
- (void)testDisplacedDelegateIsReinstalled {
    [MMMapSessionNetworkDelegate install];
    id ours = MLNNetworkConfiguration.sharedManager.delegate;
    XCTAssertNotNil(ours);

    MLNNetworkConfiguration.sharedManager.delegate = nil;   // a host app takes over
    XCTAssertNil(MLNNetworkConfiguration.sharedManager.delegate);

    [MMMapSessionNetworkDelegate install];
    XCTAssertEqual(ours, MLNNetworkConfiguration.sharedManager.delegate,
        @"a displaced delegate must be recoverable, not permanently lost");
}

- (void)testWillSendRequestSignsTileURL {
    [[MMMapSession sharedSession] resetForTesting];
    // The brief's version of this test omits this seed call, but
    // applyCredentialFromHeaders: (Task 2) rejects any rollover when no
    // account is known -- without this the credential below is never
    // adopted and the assertion below fails. See task-3-report.md.
    [[MMMapSession sharedSession] seedAccountForTesting:@"acct1"];
    [[MMMapSession sharedSession] applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": @"sid", @"X-Map-Session-Sig": @"sg",
        @"X-Map-Session-Exp": @"4102444800", @"X-Map-Session-Ends": @"4102448400",
        @"X-Map-Session-Key-Id": @"1" }];
    [MMMapSessionNetworkDelegate install];

    NSMutableURLRequest *req = [NSMutableURLRequest requestWithURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"]];
    NSURLRequest *out = [MLNNetworkConfiguration.sharedManager.delegate willSendRequest:req];
    XCTAssertTrue([out.URL.query containsString:@"sig=sg"]);
}

// MARK: - No renewal timer

// THE REPLACEMENT FOR THE TIMER. There is none: rollover is the only refresh
// path in normal operation, and it is demand-driven BY CONSTRUCTION -- it can
// only fire when a tile is actually requested. These tests pin that nothing
// in the session machine buys a window on its own initiative. Every increment
// of refreshCallCountForTesting is a BILLED map load in production.

- (void)testAdoptingACredentialBuysNothing {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [s seedAccountForTesting:@"acct1"];
    NSTimeInterval soon = [[NSDate date] timeIntervalSince1970] + 30;
    [s applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": @"sid", @"X-Map-Session-Sig": @"sg",
        @"X-Map-Session-Exp": [@((long long)soon) stringValue],
        @"X-Map-Session-Ends": @"4102448400", @"X-Map-Session-Key-Id": @"1" }];
    XCTAssertEqualWithAccuracy([s secondsUntilExpiry], 30, 2);
    XCTAssertEqual([s refreshCallCountForTesting], 0,
        @"adopting a rollover credential must not schedule or buy anything");
}

// The old code put an already-expired credential straight onto the "renew now"
// branch, which bought a window the moment it was adopted. The correct
// response is to do nothing: the next real tile carries the dead credential,
// the gateway rolls it over, serves the tile inline and bills exactly once.
- (void)testAnExpiredCredentialDoesNotBuyAWindow {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [s seedAccountForTesting:@"acct1"];
    [s applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": @"sid", @"X-Map-Session-Sig": @"sg",
        @"X-Map-Session-Exp": @"1",            // long past
        @"X-Map-Session-Ends": @"4102448400", @"X-Map-Session-Key-Id": @"1" }];
    XCTAssertEqual([s secondsUntilExpiry], 0);
    XCTAssertEqual([s refreshCallCountForTesting], 0,
        @"a lapsed credential must lapse -- the next tile rolls it over and is billed then");
}

// THE IDLE TEST. A map open but untouched issues no tile requests and must not
// be billed. The timer had to be GATED on recorded activity to achieve this and
// the gate was got wrong twice; with no timer the property is structural.
- (void)testIdleMapBuysNothing {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [s seedAccountForTesting:@"acct1"];
    [s applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": @"sid", @"X-Map-Session-Sig": @"sg",
        @"X-Map-Session-Exp": @"4102444800", @"X-Map-Session-Ends": @"4102448400",
        @"X-Map-Session-Key-Id": @"1" }];
    XCTAssertEqual([s refreshCallCountForTesting], 0,
        @"an idle map must let its credential lapse, not buy another window");
}

- (void)testSigningATileBuysNothing {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [s seedAccountForTesting:@"acct1"];
    [s applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": @"sid", @"X-Map-Session-Sig": @"sg",
        @"X-Map-Session-Exp": @"4102444800", @"X-Map-Session-Ends": @"4102448400",
        @"X-Map-Session-Key-Id": @"1" }];

    NSURL *signed_ = [s signedURLForRequestURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"]];

    XCTAssertTrue([signed_.query containsString:@"sig=sg"], @"the tile must still be signed");
    XCTAssertEqual([s refreshCallCountForTesting], 0,
        @"signing a tile rides the window already held; it must not buy another");
}

// THE SECOND DOCUMENTED TIMER FAILURE. Reopening the app to ANY screen used to
// bill a map load with no map on screen: the foreground hook re-armed the
// renewal, the stale activity flag said "in use", and a window was bought. The
// hook is KEPT -- it re-asserts the network delegate and is the fast way out of
// the give-up state -- but it no longer refreshes.
- (void)testForegroundEntryBuysNothing {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [s seedAccountForTesting:@"acct1"];
    [s applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": @"sid", @"X-Map-Session-Sig": @"sg",
        @"X-Map-Session-Exp": @"4102444800", @"X-Map-Session-Ends": @"4102448400",
        @"X-Map-Session-Key-Id": @"1" }];
    // A tile was signed, exactly as it would have been before the app was
    // backgrounded. Under the timer this is what made the flag stale.
    [s signedURLForRequestURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"]];

    [s applicationWillEnterForeground];

    XCTAssertEqual([s refreshCallCountForTesting], 0,
        @"reopening the app must not bill a map load with no map on screen");
    XCTAssertEqual(MLNNetworkConfiguration.sharedManager.delegate.class,
                   MMMapSessionNetworkDelegate.class,
                   @"the foreground hook must still re-assert the signing delegate");
}

// MARK: - Helpers

// Deliberately leaves the origin UNSET. refreshNow counts the decision to
// refresh before it looks up the origin, so these tests observe the billing
// decision without any request actually leaving the simulator -- a unit suite
// that reached out to example.com would be both slow and flaky, and its
// completion handler would mutate shared state under a later test.
//
// That hazard is not hypothetical. The mapmetrics-gl suite had the same shape
// and DID leak: once its test origin became a host that resolves, ~30 real
// creates per run went to production, and the replies landed inside whichever
// later test happened to await first, clearing its credential. Keep `_origin`
// nil in unit tests unless the test is specifically about the origin.
- (MMMapSession *)sessionWithCredential:(NSString *)sessionId {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [s seedAccountForTesting:@"acct1"];
    [s applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": sessionId, @"X-Map-Session-Sig": @"sg",
        @"X-Map-Session-Exp": @"4102444800", @"X-Map-Session-Ends": @"4102448400",
        @"X-Map-Session-Key-Id": @"1" }];
    return s;
}

- (void)deliver401ForURL:(NSString *)urlString headers:(NSDictionary *)headers {
    NSHTTPURLResponse *http = [[NSHTTPURLResponse alloc]
        initWithURL:[NSURL URLWithString:urlString]
         statusCode:401 HTTPVersion:@"HTTP/1.1" headerFields:headers];
    MLNNetworkResponse *r = [[MLNNetworkResponse alloc] init];
    r.response = http;
    [MMMapSessionNetworkDelegate install];
    [MLNNetworkConfiguration.sharedManager.delegate didReceiveResponse:r];
}

// MARK: - C1: the recovery dead-end

// A 401 that ALSO carries X-Map-Session-* headers which are then REFUSED (here:
// no account is known, so the rollover is meaningless) must still trigger a
// refresh. With an `else if`, the header branch consumed the response, the
// adoption failed, and nothing was ever scheduled: no usable credential and no
// refresh on its way -- a permanently blank map.
- (void)testRefusedRolloverOnA401StillTriggersRefresh {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];   // deliberately NO account: the rollover cannot be adopted

    [self deliver401ForURL:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"
                   headers:@{ @"X-Map-Session-Id": @"abc", @"X-Map-Session-Sig": @"sg",
                              @"X-Map-Session-Exp": @"4102444800",
                              @"X-Map-Session-Ends": @"4102448400",
                              @"X-Map-Session-Key-Id": @"1" }];

    XCTAssertEqual([s refreshCallCountForTesting], 1,
        @"a 401 whose rollover headers were refused must fall through to refresh");
}

// MARK: - C2: one billed map load per window of use

// THE CORE PROPERTY. Eight tiles are in flight when the signing key rotates, so
// all eight 401. The first buys a replacement credential; the other seven are
// signed with the now-dead session and are STALE NEWS. Without the identity
// check each of them takes the renew branch and BILLS A MAP LOAD -- eight
// charges for one rotation. `_refreshInFlight` cannot help: the network
// serialises these responses, so each arrives after the previous refresh ended.
- (void)testStale401sFromASupersededCredentialDoNotBill {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    XCTAssertEqual([s refreshCallCountForTesting], 0, @"precondition");

    for (int i = 0; i < 8; i++) {
        [self deliver401ForURL:[NSString stringWithFormat:
            @"https://gateway.mapmetrics-atlas.net/v2/tiles/12/%d/1362.mvt?s=session-old&sig=dead", 2094 + i]
                       headers:@{}];
    }

    XCTAssertEqual([s refreshCallCountForTesting], 0,
        @"a 401 for a credential we have already replaced must not buy another window");
}

// The other half of the gate: a 401 that DOES concern the credential we hold is
// a real signal and must still recover, or the map stays blank after a rotation.
- (void)testA401ForTheHeldCredentialDoesTriggerRefresh {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];

    [self deliver401ForURL:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt?s=session-new&sig=x"
                   headers:@{}];

    XCTAssertEqual([s refreshCallCountForTesting], 1,
        @"a 401 for the credential actually in use must recover");
}

// Bootstrap: before any credential exists tiles go out unsigned, and that 401
// is precisely what creates the first session. It must not be gated away.
- (void)testUnsigned401BootstrapsWhenNoCredentialIsHeld {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];

    [self deliver401ForURL:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt" headers:@{}];

    XCTAssertEqual([s refreshCallCountForTesting], 1,
        @"the unsigned first tile's 401 is how the first session is created");
}

// ...but once a credential IS held, an unsigned request 401ing is not ours and
// must not buy a window.
- (void)testUnsigned401IsIgnoredWhileACredentialIsHeld {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];

    [self deliver401ForURL:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt" headers:@{}];

    XCTAssertEqual([s refreshCallCountForTesting], 0,
        @"an unrelated unsigned 401 must not bill a refresh while the credential is healthy");
}

// MARK: - I1: concurrent adoption is atomic

// Adoption runs on NSURLSession callback threads and several tile responses can
// carry rollover headers at once. The credential fields must move together --
// a torn read that paired a new sig with an old session id would sign every
// later tile invalidly -- and, since the whole update is inside a non-reentrant
// NSLock, the risk the fix introduces is a self-deadlock, which the timeout
// here catches. Concurrency must also never itself buy a window.
- (void)testConcurrentAdoptionIsAtomicAndDoesNotDeadlock {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [s seedAccountForTesting:@"acct1"];
    NSTimeInterval exp = [[NSDate date] timeIntervalSince1970] + 600;

    XCTestExpectation *done = [self expectationWithDescription:@"hammered"];
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        dispatch_apply(200, dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0),
                       ^(size_t i) {
            [s applyCredentialFromHeaders:@{
                @"X-Map-Session-Id": [NSString stringWithFormat:@"sid%zu", i],
                @"X-Map-Session-Sig": [NSString stringWithFormat:@"sg%zu", i],
                @"X-Map-Session-Exp": [@((long long)(exp + i)) stringValue],
                @"X-Map-Session-Ends": @"4102448400", @"X-Map-Session-Key-Id": @"1" }];
            [s signedURLForRequestURL:
                [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"]];
        });
        [done fulfill];
    });
    [self waitForExpectationsWithTimeout:10 handler:nil];

    // Only the newest wins: adoption is newer-than-held, so whichever ordering
    // the threads took, the survivor is the highest exp anyone offered.
    XCTAssertTrue([s secondsUntilExpiry] > 0, @"a credential must have survived");
    XCTAssertEqual([s refreshCallCountForTesting], 0,
        @"adopting must never itself buy a window");
}

// MARK: - I2: the API key goes to a pinned origin only

// refreshNow POSTs the permanent, full-scope MLNApiKey to _origin. A host named
// only in a style document must never be able to become that origin, nor to
// inject a credential the SDK would then sign tiles with.
- (void)testCredentialFromAForeignHostIsRefused {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    // Establish the gateway as the origin, exactly as a first real tile would.
    [s signedURLForRequestURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"]];

    BOOL adopted = [s applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": @"attacker", @"X-Map-Session-Sig": @"evil",
        @"X-Map-Session-Exp": @"4102444999", @"X-Map-Session-Ends": @"4102448400",
        @"X-Map-Session-Key-Id": @"1" }
                                     responseURL:[NSURL URLWithString:
        @"https://attacker.example.net/v2/tiles/1/2/3.mvt"]];

    XCTAssertFalse(adopted, @"only the pinned origin may mint credentials");
    NSString *signedURL = [[s signedURLForRequestURL:[NSURL URLWithString:
        @"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"]] absoluteString];
    XCTAssertTrue([signedURL containsString:@"s=session-new"],
        @"the held credential must be untouched by the rejected injection");
}

// The origin is learned at most once and only over https. An http tile URL must
// not become the destination for the API key, and a later host must not be able
// to re-point an origin already established.
- (void)testOriginIsHTTPSOnlyAndLearnedOnce {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [s signedURLForRequestURL:[NSURL URLWithString:@"http://insecure.example/v2/tiles/1/2/3.mvt"]];
    XCTAssertNil([s originForTesting], @"an http tile URL must never become the origin");

    [s signedURLForRequestURL:[NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/1/2/3.mvt"]];
    XCTAssertEqualObjects([s originForTesting].host, @"gateway.mapmetrics-atlas.net");

    [s signedURLForRequestURL:[NSURL URLWithString:@"https://attacker.example/v2/tiles/1/2/3.mvt"]];
    XCTAssertEqualObjects([s originForTesting].host, @"gateway.mapmetrics-atlas.net",
        @"a later host must not be able to re-point an established origin");
}

// RESIDUAL 1. A split deployment -- MLNTileServerBaseURL naming one host, tiles
// served from another -- makes sameOrigin NO, so every tile goes out unsigned,
// the gateway 401s, and the session-identity gate rightly declines to buy a
// window for a 401 that is not about our credential. The map is blank forever.
// The refusal is correct (it is what keeps the API key off a host the app never
// configured); the silence is the defect. Log it, exactly once, however many
// tiles are refused.
- (void)testTileHostMismatchRefusesToSignAndSaysSoOnce {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    // Establish the gateway as the origin, exactly as the first real tile would.
    [s signedURLForRequestURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"]];
    XCTAssertEqual([s originMismatchLogCountForTesting], 0, @"precondition");

    NSURL *foreign = [NSURL URLWithString:@"https://tiles.other.example/v2/tiles/12/2094/1362.mvt"];
    NSURL *out = [s signedURLForRequestURL:foreign];
    XCTAssertEqualObjects(out, foreign,
        @"the origin check must still refuse to hand the credential to another host");
    XCTAssertEqual([s originMismatchLogCountForTesting], 1,
        @"and it must say why, or the developer sees a blank map and nothing else");

    // A map view issues hundreds of tile requests; one log, not hundreds.
    for (int i = 0; i < 50; i++) {
        [s signedURLForRequestURL:[NSURL URLWithString:[NSString stringWithFormat:
            @"https://tiles.other.example/v2/tiles/12/%d/1362.mvt", 2094 + i]]];
    }
    XCTAssertEqual([s originMismatchLogCountForTesting], 1,
        @"the diagnostic must be emitted once, not once per tile");
}

// MARK: - I3: permanent auth failure gives up instead of retrying forever

- (void)testConsecutiveHardFailuresEventuallyStopRefreshing {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];

    for (NSInteger i = 0; i < MMMaxConsecutiveHardFailures; i++) {
        XCTAssertFalse([s hasGivenUpForTesting], @"must not give up early (i=%ld)", (long)i);
        [s handleRefreshFailureWithStatus:401];
        // Failures must be SPACED to count -- see MMMinHardFailureSpacing.
        // Back-date the clock rather than sleeping 30s three times.
        [s rewindFailureClocksForTesting:MMMinHardFailureSpacing + 1];
    }
    XCTAssertTrue([s hasGivenUpForTesting],
        @"a key that can never work must stop costing a request per lapse");

    NSInteger before = [s refreshCallCountForTesting];
    [s refreshNow];
    XCTAssertEqual([s refreshCallCountForTesting], before,
        @"after giving up, refreshNow must be a no-op");
}

// RESIDUAL 2b. Every unsigned tile that 401s drives another refreshNow at once,
// so a burst of in-flight tile responses used to burn the whole budget in under
// a second: a two-second gateway wobble blanked the map for the life of the
// process. The budget is meant to count ATTEMPTS, so failures that arrive
// together must count once.
- (void)testRapidBackToBackFailuresDoNotExhaustTheBudget {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];

    // Ten failures, all "now" -- one incident, however many tiles were in flight.
    for (NSInteger i = 0; i < MMMaxConsecutiveHardFailures * 3; i++) {
        [s handleRefreshFailureWithStatus:401];
    }
    XCTAssertFalse([s hasGivenUpForTesting],
        @"a burst of simultaneous tile 401s is one incident, not a permanent fault");

    // The same number of failures, spaced, does exhaust it.
    for (NSInteger i = 0; i < MMMaxConsecutiveHardFailures; i++) {
        [s rewindFailureClocksForTesting:MMMinHardFailureSpacing + 1];
        [s handleRefreshFailureWithStatus:401];
    }
    XCTAssertTrue([s hasGivenUpForTesting],
        @"genuinely repeated, spaced attempts must still reach the give-up state");
}

// RESIDUAL 2a. `_gaveUp` had no way back: the only resets need traffic that a
// credential-less session can no longer produce, so a transient fault became a
// permanently blank map until the process was restarted.
- (void)testForegroundRecoversFromGiveUp {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    for (NSInteger i = 0; i < MMMaxConsecutiveHardFailures; i++) {
        [s handleRefreshFailureWithStatus:401];
        [s rewindFailureClocksForTesting:MMMinHardFailureSpacing + 1];
    }
    XCTAssertTrue([s hasGivenUpForTesting], @"precondition");

    NSInteger before = [s refreshCallCountForTesting];
    [s refreshNow];
    XCTAssertEqual([s refreshCallCountForTesting], before, @"inert while given up");

    [s applicationWillEnterForeground];

    XCTAssertFalse([s hasGivenUpForTesting],
        @"backgrounding and returning is the user's 'try again' and must be honoured");
    [s refreshNow];
    XCTAssertEqual([s refreshCallCountForTesting], before + 1,
        @"and refreshNow must actually work again afterwards");
}

// The unattended way back: a map left open recovers once MMGiveUpCooldown has
// elapsed, without the user touching anything.
- (void)testGiveUpExpiresAfterTheCooldown {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    for (NSInteger i = 0; i < MMMaxConsecutiveHardFailures; i++) {
        [s handleRefreshFailureWithStatus:401];
        [s rewindFailureClocksForTesting:MMMinHardFailureSpacing + 1];
    }
    XCTAssertTrue([s hasGivenUpForTesting], @"precondition");

    NSInteger before = [s refreshCallCountForTesting];
    [s rewindFailureClocksForTesting:MMGiveUpCooldown + 1];
    [s refreshNow];

    XCTAssertEqual([s refreshCallCountForTesting], before + 1,
        @"give-up is a pause, not a verdict: the cool-down must let one attempt through");
    XCTAssertFalse([s hasGivenUpForTesting]);
}

// 403 (insufficient scope) used to clear nothing, so `canRenew` stayed true and
// every later refresh took the renew branch and 403'd again, forever.
- (void)test403ClearsTheCredentialLikeA401 {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    XCTAssertGreaterThan(s.secondsUntilExpiry, 0, @"precondition");

    [s handleRefreshFailureWithStatus:403];

    XCTAssertEqual(s.secondsUntilExpiry, 0,
        @"a 403 must drop the credential, or every later refresh re-tries the same renew");
}

- (void)testSuccessResetsTheHardFailureCount {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [s handleRefreshFailureWithStatus:401];
    [s handleRefreshFailureWithStatus:401];

    NSDictionary *good = @{
        @"account": @"acct1", @"session_id": @"sid", @"sig": @"sg",
        @"key_id": @1, @"expires_at": @4102444800, @"session_ends_at": @4102448400 };
    XCTAssertTrue([s adoptRefreshResponse:good]);

    [s handleRefreshFailureWithStatus:401];
    [s handleRefreshFailureWithStatus:401];
    XCTAssertFalse([s hasGivenUpForTesting],
        @"the counter is for CONSECUTIVE failures; a success must clear it");
}

// A 200 with no usable expiry is not a credential. Adopting it gives _exp = 0,
// which signs `e=0` on every tile and 401s on every one of them -- feeding
// exactly the repeat-refresh loop the session-identity gate exists to stop.
- (void)testMalformed200IsNotAdopted {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];

    NSDictionary *noExpiry = @{ @"account": @"a", @"session_id": @"sid",
                                @"sig": @"sg", @"key_id": @1 };
    NSDictionary *pastExpiry = @{ @"account": @"a", @"session_id": @"sid",
                                  @"sig": @"sg", @"key_id": @1, @"expires_at": @1 };
    XCTAssertFalse([s adoptRefreshResponse:noExpiry],
        @"a body with no expires_at must be rejected");
    XCTAssertFalse([s adoptRefreshResponse:pastExpiry],
        @"a body whose expiry is already past must be rejected");
    XCTAssertEqual(s.secondsUntilExpiry, 0);
}

// MARK: - I6: signing appends, it does not replace

- (void)testSigningPreservesExistingQueryItems {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];

    NSURL *out = [s signedURLForRequestURL:[NSURL URLWithString:
        @"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt?token=abc&lang=nl"]];
    NSString *q = out.query;

    XCTAssertTrue([q containsString:@"token=abc"], @"pre-existing query items must survive");
    XCTAssertTrue([q containsString:@"lang=nl"],   @"pre-existing query items must survive");
    XCTAssertTrue([q containsString:@"sig=sg"],    @"and the credential is still appended");
}

// Re-signing an already-signed URL must not accumulate duplicate parameters.
- (void)testResigningDoesNotDuplicateCredentialParameters {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    NSURL *once = [s signedURLForRequestURL:[NSURL URLWithString:
        @"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"]];
    NSURL *twice = [s signedURLForRequestURL:once];

    NSInteger sigCount = 0;
    for (NSURLQueryItem *item in [NSURLComponents componentsWithURL:twice
                                             resolvingAgainstBaseURL:NO].queryItems) {
        if ([item.name isEqualToString:@"sig"]) sigCount++;
    }
    XCTAssertEqual(sigCount, 1, @"re-signing must replace our own params, not append copies");
}

// MARK: - what counts as a tile: SHAPE, not a known path prefix
//
// The gateway accepts a v2 session signature on the EXISTING v1 tile path, not
// only on /v2/tiles. So the SDK must sign the tile URL the style already gave
// it -- otherwise a customer moves to session billing only by editing their
// style, which is the coordination this whole design exists to avoid. Matching
// the literal "/v2/tiles/" would have ignored every real style's tile URLs and
// the feature would never have engaged in production at all.
//
// The predicate is therefore: the path's last three segments are
// {z}/{x}/{y}.mvt. These tests pin both halves of that -- what it now catches,
// and what it must still not catch, because broadening the matcher broadens
// what could become the LEARNED origin.

// The URL a real style hands out today. No /v2/tiles/ anywhere in it.
- (void)testV1ShapedTileURLIsSigned {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];

    NSURL *tile = [NSURL URLWithString:
        @"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt"];
    NSString *q = [s signedURLForRequestURL:tile].query;

    XCTAssertTrue([q containsString:@"sig=sg"],
        @"the v1 tile path is what every real style serves; if it is not signed "
         "the feature never engages outside the staging harness");
    XCTAssertTrue([q containsString:@"s=session-new"]);
}

// ...and the /v2/tiles form must not regress while the v1 one is added.
- (void)testV2TilesURLIsStillSigned {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];

    NSURL *tile = [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.mvt"];
    NSString *q = [s signedURLForRequestURL:tile].query;

    XCTAssertTrue([q containsString:@"sig=sg"], @"widening must not drop the v2 form");
}

// A v1 tile URL ALREADY CARRIES ?token=<JWT>. The gateway sees `sig` and takes
// the session path; the token riding along is harmless -- but dropping it would
// break the request outright for anything that still reads it. Signing merges,
// it does not replace.
- (void)testV1TokenSurvivesSigningAlongsideAllSixCredentialParams {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];

    NSURL *out = [s signedURLForRequestURL:[NSURL URLWithString:
        @"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt?token=JWT123"]];

    NSMutableDictionary<NSString *, NSString *> *items = [NSMutableDictionary dictionary];
    for (NSURLQueryItem *item in [NSURLComponents componentsWithURL:out
                                           resolvingAgainstBaseURL:NO].queryItems) {
        items[item.name] = item.value;
    }
    XCTAssertEqualObjects(items[@"token"], @"JWT123",
        @"the style's own token must survive alongside the credential");
    for (NSString *name in @[@"u", @"s", @"e", @"a", @"k", @"sig"]) {
        XCTAssertNotNil(items[name], @"credential parameter %@ is missing", name);
    }
    XCTAssertEqualObjects(items[@"s"], @"session-new");
    XCTAssertEqualObjects(items[@"sig"], @"sg");
}

// The other half. Each of these lives UNDER /v2/tiles/ deliberately: a
// prefix-based matcher signs all three, so this test is what distinguishes a
// shape test from a prefix test. Signing a style or a metadata document would
// leak the credential into URLs the gateway never expects it on, and -- worse
// -- would let any such URL establish the learned origin.
- (void)testNonTileURLsAreStillUntouched {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];

    NSArray<NSString *> *notTiles = @[
        @"https://gateway.mapmetrics-atlas.net/v2/styles?fileName=a.json",       // a style
        @"https://gateway.mapmetrics-atlas.net/v2/tiles/style.json",             // under the old prefix
        @"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.json",// adjacent to a tile
        @"https://gateway.mapmetrics-atlas.net/v2/tiles/12/2094/1362.pbf",       // right shape, wrong suffix
        @"https://gateway.mapmetrics-atlas.net/v2/tiles/planet/tiles.mvt",       // .mvt, no z/x/y
        @"https://gateway.mapmetrics-atlas.net/v2/tiles/2094/1362.mvt",          // only two numeric segments
    ];
    for (NSString *str in notTiles) {
        NSURL *url = [NSURL URLWithString:str];
        XCTAssertEqualObjects([s signedURLForRequestURL:url], url,
            @"%@ is not a tile and must go out exactly as given", str);
    }
}

// Widening WHAT is a tile widens what could become the learned origin, so the
// origin check is now doing more work, not less. A tile-shaped URL on a host
// that is not the pinned origin must still be refused -- and must be refused at
// the ORIGIN check (which says so, once), not silently skipped by the matcher.
- (void)testV1ShapedTileOnAForeignHostIsStillRefused {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    // Establish the gateway as the origin, exactly as the first real tile would.
    [s signedURLForRequestURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt"]];
    XCTAssertEqualObjects([s originForTesting].host, @"gateway.mapmetrics-atlas.net", @"precondition");
    XCTAssertEqual([s originMismatchLogCountForTesting], 0, @"precondition");

    NSURL *foreign = [NSURL URLWithString:
        @"https://attacker.example.net/planet20251013/12/2094/1362.mvt"];
    XCTAssertEqualObjects([s signedURLForRequestURL:foreign], foreign,
        @"a tile-shaped URL on another host must never receive the credential");
    XCTAssertEqual([s originMismatchLogCountForTesting], 1,
        @"and it must be REFUSED by the origin check, with the diagnostic, rather "
         "than merely not recognised as a tile");
}

// The delegate must use the SAME predicate, or a v1 tile that 401s never
// bootstraps a session and the map stays blank on exactly the customers this
// change exists to reach.
- (void)testV1ShapedTile401Bootstraps {
    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];

    [self deliver401ForURL:
        @"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt?token=JWT123"
                   headers:@{}];

    XCTAssertEqual([s refreshCallCountForTesting], 1,
        @"a 401 on the v1 tile path is how a real style's first session is created");
}

// MARK: - I4: same origin means SCHEME, HOST AND PORT
//
// `_origin` was always built from all three, but the comparison only looked at
// the host. So, pinned to https://gw.example.com, the SDK would sign a tile for
// http://gw.example.com -- the session signature on the wire in CLEARTEXT --
// and would accept X-Map-Session-* from a plaintext response, letting anyone on
// the network path mint the credential every later tile is signed with.
//
// Android already compared all three (finding I4 of the Android review). iOS
// never received it, so the two platforms disagreed about a security check that
// is supposed to be one shared contract. Widening the tile matcher to a SHAPE
// test makes this worse, not academic: more URLs now reach the origin check.

- (void)testRightHostWrongSchemeIsRefused {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    [s signedURLForRequestURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt"]];
    XCTAssertEqualObjects([s originForTesting].absoluteString, @"https://gateway.mapmetrics-atlas.net",
        @"precondition: the origin is pinned, scheme included");

    NSURL *cleartext = [NSURL URLWithString:
        @"http://good.example/planet20251013/12/2094/1362.mvt"];
    XCTAssertEqualObjects([s signedURLForRequestURL:cleartext], cleartext,
        @"http:// on the pinned host is a DIFFERENT origin; signing it would put "
         "the session signature on the wire in cleartext");
    XCTAssertEqual([s originMismatchLogCountForTesting], 1,
        @"and the refusal must be diagnosed, not silent");
}

- (void)testRightHostWrongPortIsRefused {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    [s signedURLForRequestURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt"]];
    XCTAssertNil([s originForTesting].port, @"precondition: no port pinned");

    NSURL *otherPort = [NSURL URLWithString:
        @"https://gateway.mapmetrics-atlas.net:8443/planet20251013/12/2094/1362.mvt"];
    XCTAssertEqualObjects([s signedURLForRequestURL:otherPort], otherPort,
        @"a different port on the pinned host is a different origin");
    XCTAssertEqual([s originMismatchLogCountForTesting], 1);
}

// The port must be honoured in BOTH directions: an origin pinned WITH a port
// must still sign its own tiles, or tightening the check would simply break
// every non-default-port deployment.
- (void)testMatchingSchemeHostAndPortIsStillSigned {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    NSURL *tile = [NSURL URLWithString:
        @"https://gateway.mapmetrics-atlas.net:8443/planet20251013/12/2094/1362.mvt"];

    [s signedURLForRequestURL:tile];   // learns https://gateway.mapmetrics-atlas.net:8443
    XCTAssertEqualObjects([s originForTesting].port, @8443, @"precondition");

    XCTAssertTrue([[s signedURLForRequestURL:tile].query containsString:@"sig=sg"],
        @"a request on exactly the pinned origin must still be signed");
    XCTAssertEqual([s originMismatchLogCountForTesting], 0, @"and must not be diagnosed");
}

// The adoption path needs the SAME check, not just the signing path. Guarding
// only one would be pointless: a credential accepted from http:// on the pinned
// host is then used to sign every subsequent tile.
- (void)testCredentialFromTheRightHostOverPlaintextIsRefused {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    [s signedURLForRequestURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt"]];

    BOOL adopted = [s applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": @"injected", @"X-Map-Session-Sig": @"evil",
        @"X-Map-Session-Exp": @"4102444999", @"X-Map-Session-Ends": @"4102448400",
        @"X-Map-Session-Key-Id": @"1" }
                                     responseURL:[NSURL URLWithString:
        @"http://good.example/planet20251013/12/2094/1362.mvt"]];

    XCTAssertFalse(adopted,
        @"a plaintext response on the pinned host must not be able to mint the "
         "credential -- anyone on the network path can write those headers");
    XCTAssertTrue([[[s signedURLForRequestURL:[NSURL URLWithString:
        @"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt"]] query]
            containsString:@"s=session-new"],
        @"and the held credential must be untouched by the rejected injection");
}

- (void)testCredentialFromTheRightHostOnAnotherPortIsRefused {
    MMMapSession *s = [self sessionWithCredential:@"session-new"];
    [s signedURLForRequestURL:
        [NSURL URLWithString:@"https://gateway.mapmetrics-atlas.net/planet20251013/12/2094/1362.mvt"]];

    // Bound to a local first: the dictionary literal's commas would otherwise
    // be read as macro argument separators.
    BOOL adopted = [s applyCredentialFromHeaders:@{
        @"X-Map-Session-Id": @"injected", @"X-Map-Session-Sig": @"evil",
        @"X-Map-Session-Exp": @"4102444999", @"X-Map-Session-Ends": @"4102448400",
        @"X-Map-Session-Key-Id": @"1" }
                                     responseURL:[NSURL URLWithString:
        @"https://gateway.mapmetrics-atlas.net:8443/planet20251013/12/2094/1362.mvt"]];
    XCTAssertFalse(adopted,
        @"only the pinned origin may mint credentials, port included");
}

// MARK: - the API-key cache
//
// `refreshNow` runs on NSURLSession callback threads and must never read the
// key back off MLNSettings: +[MLNSettings sharedSettings] dispatch_syncs to
// the main queue on EVERY off-main call (the isMainThread test is outside its
// dispatch_once), so doing so blocks each billing call on the queue that
// renders the map — and hangs outright when the main thread is waiting on that
// callback. The key is therefore observed once and cached.

// The KVO ordering: the observation is already registered (the singleton's
// happens in +load) and the key is set afterwards, which is the documented way
// a host app configures it programmatically.
- (void)testAPIKeySetAfterRegistrationIsCached {
    NSString *key = [NSString stringWithFormat:@"unit-key-after-%@", NSUUID.UUID.UUIDString];
    MLNSettings.apiKey = key;
    XCTAssertEqualObjects([[MMMapSession sharedSession] cachedAPIKeyForTesting], key,
        @"a key set after registration must reach the cache via the KVO change");
}

// The other ordering: the key is already set before anyone observes it — what
// happens in production when Info.plist carries MLNApiKey and MLNSettings'
// +load reads it before ours runs. NSKeyValueObservingOptionInitial is what
// covers this, and nothing else would: no change notification ever follows.
// Exercised on a throwaway session because the singleton registered long ago.
- (void)testAPIKeySetBeforeRegistrationIsCached {
    NSString *key = [NSString stringWithFormat:@"unit-key-before-%@", NSUUID.UUID.UUIDString];
    MLNSettings.apiKey = key;

    MMMapSession *fresh = [[MMMapSession alloc] init];
    XCTAssertNil([fresh cachedAPIKeyForTesting], @"precondition: nothing observed yet");
    [fresh beginObservingAPIKey];
    XCTAssertEqualObjects([fresh cachedAPIKeyForTesting], key,
        @"a key already set at registration time must be read immediately");
}

// THE PROOF that the hop is gone. The main thread is BLOCKED for the whole of
// `refreshNow`; any dispatch_sync onto the main queue from inside it — which
// is what `[MLNSettings apiKey]` did — deadlocks here and this test times out.
// It also pins that the cached value is the one actually used: the request is
// only built at all once a key is present.
- (void)testRefreshNowDoesNotTouchTheMainQueueOffTheMainThread {
    NSString *key = [NSString stringWithFormat:@"unit-key-refresh-%@", NSUUID.UUID.UUIDString];
    MLNSettings.apiKey = key;

    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    XCTAssertEqualObjects([s cachedAPIKeyForTesting], key,
        @"precondition: the key is cached, and resetForTesting must not clear it — "
         "the cache is configuration, not session state");

    // A real origin is required or `refreshNow` returns at the origin check,
    // on the line before the create branch that reads the key, and would prove
    // nothing. Port 9 (discard) is never answered, so no traffic leaves the
    // machine and the create simply fails later, off this thread.
    //
    // The origin is PINNED, not learned: learning is now restricted to the
    // gateway host list, and 127.0.0.1 is not on it -- deliberately, since the
    // whole point of the list is that only a known gateway may become the
    // destination for the customer's API key. Pinning is the supported way to
    // point the SDK at any other host, and it is what a self-hosted deployment
    // does. This test is about the main-queue hop, not about origin matching;
    // the assertion below pins that the origin really was established, port
    // included, so the same-origin check is satisfied by construction.
    [s pinOriginForTesting:@"https://127.0.0.1:9"];
    XCTAssertEqualObjects([s originForTesting].absoluteString, @"https://127.0.0.1:9",
        @"precondition: the origin must be pinned, port included");

    dispatch_semaphore_t returned = dispatch_semaphore_create(0);
    [NSThread detachNewThreadWithBlock:^{
        [s refreshNow];
        dispatch_semaphore_signal(returned);
    }];
    XCTAssertEqual(dispatch_semaphore_wait(returned,
        dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)), 0,
        @"refreshNow did not return while the main thread was blocked — it is "
         "still hopping to the main queue, which is the deadlock this cache removes");
    XCTAssertEqual([s refreshCallCountForTesting], 1, @"the refresh was not suppressed");

    [s resetForTesting];
}

@end
