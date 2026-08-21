#import <XCTest/XCTest.h>
#import "MMMapSession.h"
#import "MMMapSession_Private.h"
#import "MMMapSessionNetworkDelegate.h"
#import "MLNSettings.h"
#import "MLNNetworkConfiguration.h"
#import "MLNNetworkResponse.h"

// End-to-end acceptance gate for the v2 map-session work (Tasks 1-4). Unit
// tests prove the credential logic in isolation; this proves the BILLING
// PROPERTY against the real staging gateway: a session create is billed once,
// and every tile it authorises afterwards is free.
//
// Requires MM_STAGING_KEY in the environment (staging-creds.json .fullApiKey
// in ~/devprog2/gatewayMapAtlas/gateway-mapatlas). Skips cleanly without it,
// so the unit suite stays runnable offline and in CI.
static NSString *const MMStagingBase =
    @"https://gateway-mapatlas-staging.jim9710.workers.dev";

@interface MMMapSessionIntegrationTests : XCTestCase
@end

@implementation MMMapSessionIntegrationTests

// MMMapSession is a SINGLETON and -resetForTesting deliberately preserves a
// CONFIGURED origin, because in production a pin comes from Info.plist and
// cannot go away. So a pin set here outlives this class and every later test
// inherits the staging origin — their own gateway URLs then fail the
// same-origin check, go out unsigned, and fail in ways that look nothing like
// the cause. That is exactly what happened: 33 unit tests broke, all of them
// downstream of a pin set in this file.
- (void)tearDown {
    [[MMMapSession sharedSession] resetOriginForTesting];
    [[MMMapSession sharedSession] resetForTesting];
    [super tearDown];
}

// Named for what it PROVES: many tiles served on one credential. It does not
// assert the billing counters — those are server-side meter state, asserted by
// the gateway repo's harness (scripts/staging-maps-v2-matrix.mjs), deliberately,
// because reading them from here would mean shipping an admin credential into
// the iOS test environment. See progress.md ruling task-5-1.
- (void)testManyTilesAreServedOnOneCredential {
    NSString *key = NSProcessInfo.processInfo.environment[@"MM_STAGING_KEY"];
    if (!key.length) { XCTSkip(@"set MM_STAGING_KEY to run"); return; }

    // refreshNow's create call authenticates with MLNSettings.apiKey, not the
    // environment directly -- the brief's env read gets the value to the
    // test, but it still has to be threaded into the SDK the way any host
    // app would configure it before creating a session.
    MLNSettings.apiKey = key;

    [[MMMapSession sharedSession] resetForTesting];
    [MMMapSessionNetworkDelegate install];

    // PIN the origin, do not learn it. Learning is restricted to the gateway
    // host list and staging is deliberately not on it -- an allow-list carrying
    // a workers.dev host would ship in every release build. This also matches
    // what a real staging deployment does: it sets MLNTileServerBaseURL.
    [[MMMapSession sharedSession] pinOriginForTesting:MMStagingBase];

    NSURL *tile = [NSURL URLWithString:
        [MMStagingBase stringByAppendingString:@"/v2/tiles/12/2094/1362.mvt"]];
    [[MMMapSession sharedSession] refreshNow];

    XCTestExpectation *ready = [self expectationWithDescription:@"session"];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC),
                   dispatch_get_main_queue(), ^{ [ready fulfill]; });
    [self waitForExpectationsWithTimeout:10 handler:nil];
    XCTAssertGreaterThan([MMMapSession sharedSession].secondsUntilExpiry, 0,
        @"a credential should have been obtained");

    // Pin the session identity before the first tile goes out. Staging's
    // credential TTL is compressed to 20s; if it lapses mid-loop the gateway
    // rolls it over and keeps returning 200s, which would let this test pass
    // green while silently exercising the rollover path instead of the "many
    // tiles, one credential" property it exists to demonstrate. Capturing
    // `s` (session id) here and asserting it is unchanged after every tile
    // makes a mid-loop rollover fail loudly instead of passing quietly.
    NSString *bootstrapSession = [self sessionIdFromSignedURL:
        [[MMMapSession sharedSession] signedURLForRequestURL:tile]];
    XCTAssertNotNil(bootstrapSession, @"no session id on the signed URL");

    // Ten tiles on one credential must all be served. Each fetch is well under
    // a second on staging (observed ~0.3s avg); the 1s per-tile timeout is ~3x
    // that, and makes the loop's WORST case 10 x 1s = 10s -- genuinely inside
    // staging's compressed 20s TTL, which the earlier 5s (worst case 50s) and
    // the brief's 15s (150s) were not. Raising it breaks that arithmetic: a
    // credential that lapses mid-loop rolls over at the gateway and the test
    // would then exercise the rollover path instead of this property.
    for (int i = 0; i < 10; i++) {
        NSURL *u = [NSURL URLWithString:[NSString stringWithFormat:
            @"%@/v2/tiles/12/%d/1362.mvt", MMStagingBase, 2094 + i]];
        NSURL *signedURL = [[MMMapSession sharedSession] signedURLForRequestURL:u];
        XCTAssertTrue([signedURL.query containsString:@"sig="], @"tile %d unsigned", i);

        NSString *session = [self sessionIdFromSignedURL:signedURL];
        XCTAssertEqualObjects(session, bootstrapSession,
            @"tile %d was signed by a different session than the one obtained "
             "at the start of the loop -- the credential rolled over mid-test, "
             "so this run exercised the ROLLOVER path, not the \"many tiles, "
             "one credential\" property this test is meant to demonstrate", i);

        XCTestExpectation *got = [self expectationWithDescription:@"tile"];
        [[NSURLSession.sharedSession dataTaskWithURL:signedURL
            completionHandler:^(NSData *d, NSURLResponse *r, NSError *e) {
                XCTAssertEqual([(NSHTTPURLResponse *)r statusCode], 200);
                XCTAssertGreaterThan(d.length, 0u);
                [got fulfill];
            }] resume];
        [self waitForExpectationsWithTimeout:1 handler:nil];
    }
}

// MARK: - the v1-style cold start

// THE CASE THE 401 PATH STRUCTURALLY CANNOT REACH.
//
// A v1-shaped `?token=` tile returns 200. It never 401s, so before the eager
// create the delegate's 401 handler -- the only caller -refreshNow had -- never
// fired, no credential was ever minted, and every tile fell through to v1
// cookie billing. Measured against this same gateway, a 12-tile cold wave on
// that path cost 12 units instead of 1: the v1 dedup cookie is only issued by
// the first RESPONSE and the whole wave leaves before it lands.
//
// So this starts on the v1 path deliberately. If the eager create regresses,
// nothing here 401s, no credential appears, and the wave goes out unsigned --
// exactly the silent per-tile billing this exists to prevent.
//
// Asserts client-side facts, not meter counts: reading the meter would mean
// putting an admin credential in the iOS test environment. The gateway's own
// harness owns that half. See progress.md ruling task-5-1.
- (void)testAV1ShapedColdWaveBuysOneWindowBeforeTheFirstTile {
    NSString *key = NSProcessInfo.processInfo.environment[@"MM_STAGING_KEY"];
    if (!key.length) { XCTSkip(@"set MM_STAGING_KEY to run"); return; }

    // ORDER MATTERS, and it bit this test once already. `_cachedApiKey` is
    // populated by KVO on MLNSettings.apiKey, so the session has to EXIST and be
    // observing before the key is set — assigning first means the change fires
    // with nobody listening and the cache stays nil, which makes the eager
    // create a silent no-op. This test sorts first alphabetically, so it cannot
    // rely on an earlier test having warmed the singleton.
    [[MMMapSession sharedSession] resetForTesting];
    [MMMapSessionNetworkDelegate install];

    // Cleared first so the assignment below is always a CHANGE. KVO does not
    // fire when the value is identical, and another test in this bundle may
    // already have set the same key.
    MLNSettings.apiKey = nil;
    MLNSettings.apiKey = key;
    XCTAssertEqualObjects([[MMMapSession sharedSession] cachedAPIKeyForTesting], key,
        @"precondition: the API key must have reached the session's cache, or "
         "the eager create silently does nothing");

    [[MMMapSession sharedSession] pinOriginForTesting:MMStagingBase];

    XCTAssertEqual([MMMapSession sharedSession].secondsUntilExpiry, 0,
        @"precondition: the test must begin with no credential");

    // 1. The STYLE request, which precedes every tile a map fetches. Not a tile,
    //    so it is never signed and teaches the old learning path nothing. It is
    //    a gateway request on the pinned origin, which is all the eager create
    //    needs.
    //
    // Driven through the DELEGATE, not by calling -noteGatewayRequestURL:
    // directly. That distinction is the whole test: an earlier draft called the
    // method itself, and commenting the call out of -willSendRequest: left this
    // test passing — it proved the method worked while proving nothing about
    // whether anything invokes it. Exercise the shipped path or exercise
    // nothing.
    NSURL *style = [NSURL URLWithString:
        [MMStagingBase stringByAppendingString:@"/styles/basic.json"]];
    NSMutableURLRequest *styleRequest = [NSMutableURLRequest requestWithURL:style];
    [MLNNetworkConfiguration.sharedManager.delegate willSendRequest:styleRequest];

    XCTestExpectation *ready = [self expectationWithDescription:@"session"];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC),
                   dispatch_get_main_queue(), ^{ [ready fulfill]; });
    [self waitForExpectationsWithTimeout:10 handler:nil];

    XCTAssertGreaterThan([MMMapSession sharedSession].secondsUntilExpiry, 0,
        @"the style request must have bought a window before any tile went out");
    XCTAssertEqual([[MMMapSession sharedSession] refreshCallCountForTesting], 1,
        @"exactly one window; the create bills the window the map would have "
         "paid anyway");

    // 2. The opening wave, on the v1 path, CONCURRENTLY. A map fires a viewport
    //    at once, and the v1 cookie race only exists under concurrency.
    NSMutableArray<XCTestExpectation *> *waves = [NSMutableArray array];
    for (int i = 0; i < 6; i++) {
        NSURL *u = [NSURL URLWithString:[NSString stringWithFormat:
            @"%@/planet20251013/12/%d/1362.mvt?token=style-token-placeholder",
            MMStagingBase, 2200 + i]];
        NSURL *signedURL = [[MMMapSession sharedSession] signedURLForRequestURL:u];

        XCTAssertTrue([signedURL.query containsString:@"sig="],
            @"v1-shaped tile %d went out UNSIGNED -- that is per-tile v1 billing", i);
        XCTAssertTrue([signedURL.query containsString:@"token=style-token-placeholder"],
            @"the style's own token must survive alongside the credential params");

        XCTestExpectation *got = [self expectationWithDescription:@"v1 tile"];
        [waves addObject:got];
        [[NSURLSession.sharedSession dataTaskWithURL:signedURL
            completionHandler:^(NSData *d, NSURLResponse *r, NSError *e) {
                XCTAssertEqual([(NSHTTPURLResponse *)r statusCode], 200,
                    @"v1-shaped tile %d should have been served", i);
                [got fulfill];
            }] resume];
    }
    [self waitForExpectationsWithTimeout:10 handler:nil];

    // 3. The wave bought nothing further. Each increment is a billed map load;
    //    7 rather than 1 would mean a window per tile.
    XCTAssertEqual([[MMMapSession sharedSession] refreshCallCountForTesting], 1,
        @"the tile wave must not have bought any further windows");
}

// MARK: - concurrent bootstrap coalescing

// The other half of "one billed map load": `shouldRefreshForResponseSessionId:`
// stops SEQUENTIAL stale 401s (covered by the unit suite), and
// `_refreshInFlight` stops SIMULTANEOUS ones. Only the second matters at cold
// start: NSURLSession keeps up to 8 connections per host open, so the first
// eight tiles go out unsigned TOGETHER and 401 TOGETHER, and every one of them
// is a legitimate bootstrap 401 that the session-identity gate lets through
// (sessionId nil, no credential held => refresh warranted). `_refreshInFlight`
// is the only thing between that burst and eight session creates -- eight
// billed map loads for one cold start.
//
// The unit suite CANNOT reach that check. It deliberately leaves `_origin` nil
// to avoid network traffic, and `refreshNow` returns at the origin check on the
// line BEFORE `_refreshInFlight = YES`:
//
//     _refreshCalls++;
//     if (!_origin) { ... return; }   // <- every unit test stops here
//     _refreshInFlight = YES;         // <- never reached
//
// So this test needs a REAL origin, and therefore belongs here. It drives the
// delegate directly (as the unit tests do) rather than through NSURLSession,
// because the existing integration test's direct `NSURLSession.sharedSession`
// fetches bypass MLNNetworkConfiguration entirely and never invoke the delegate.
- (void)testConcurrentBootstrap401sCreateExactlyOneSession {
    NSString *key = NSProcessInfo.processInfo.environment[@"MM_STAGING_KEY"];
    if (!key.length) { XCTSkip(@"set MM_STAGING_KEY to run"); return; }
    MLNSettings.apiKey = key;

    MMMapSession *s = [MMMapSession sharedSession];
    [s resetForTesting];
    [MMMapSessionNetworkDelegate install];

    // Prime the origin the way the first real tile request does. The URL comes
    // back unsigned -- correct, there is no credential yet -- but the origin is
    // now pinned, so `refreshNow` will run PAST the origin check and genuinely
    // set `_refreshInFlight`.
    // PINNED, not learned. Origin learning is restricted to the gateway host
    // list and staging is deliberately off it, so a tile URL no longer
    // establishes the origin. tearDown clears this.
    [s pinOriginForTesting:MMStagingBase];

    NSURL *tile = [NSURL URLWithString:
        [MMStagingBase stringByAppendingString:@"/v2/tiles/12/2094/1362.mvt"]];
    NSURL *unsignedURL = [s signedURLForRequestURL:tile];
    XCTAssertFalse([unsignedURL.query containsString:@"sig="],
        @"precondition: no credential yet, so the first tile goes out unsigned");
    XCTAssertNotNil([s originForTesting], @"precondition: the origin must be pinned");
    XCTAssertEqual([s refreshCallCountForTesting], 0, @"precondition");

    // Eight unsigned staging tile URLs, matching NSURLSession's default
    // HTTPMaximumConnectionsPerHost -- the real width of a cold start.
    const NSUInteger n = 8;
    NSMutableArray<MLNNetworkResponse *> *responses = [NSMutableArray arrayWithCapacity:n];
    for (NSUInteger i = 0; i < n; i++) {
        NSURL *u = [NSURL URLWithString:[NSString stringWithFormat:
            @"%@/v2/tiles/12/%lu/1362.mvt", MMStagingBase, (unsigned long)(2094 + i)]];
        NSHTTPURLResponse *http = [[NSHTTPURLResponse alloc]
            initWithURL:u statusCode:401 HTTPVersion:@"HTTP/1.1" headerFields:@{}];
        MLNNetworkResponse *r = [[MLNNetworkResponse alloc] init];
        r.response = http;
        [responses addObject:r];
    }

    // Real threads, released through a barrier, so the eight deliveries GENUINELY
    // overlap. A serial loop would test the session-identity gate instead (already
    // covered by testUnsigned401IsIgnoredWhileACredentialIsHeld), and a plain
    // dispatch_apply gives no guarantee that any two blocks are ever inside
    // `refreshNow` at the same moment.
    id<MLNNetworkConfigurationDelegate> delegate = MLNNetworkConfiguration.sharedManager.delegate;
    dispatch_semaphore_t ready = dispatch_semaphore_create(0);
    dispatch_semaphore_t go    = dispatch_semaphore_create(0);
    dispatch_group_t done      = dispatch_group_create();
    for (NSUInteger i = 0; i < n; i++) {
        MLNNetworkResponse *r = responses[i];
        dispatch_group_enter(done);
        [NSThread detachNewThreadWithBlock:^{
            dispatch_semaphore_signal(ready);
            dispatch_semaphore_wait(go, DISPATCH_TIME_FOREVER);
            [delegate didReceiveResponse:r];
            dispatch_group_leave(done);
        }];
    }
    for (NSUInteger i = 0; i < n; i++) {
        XCTAssertEqual(dispatch_semaphore_wait(ready,
            dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)), 0,
            @"a delivery thread failed to start");
    }
    // Waited on with dispatch_group_wait, which BLOCKS the main thread and
    // does not pump its run loop -- deliberately. This used to need an
    // XCTestExpectation instead, because `refreshNow` read `MLNSettings.apiKey`
    // and MLNSettings' singleton accessor dispatch_syncs to the main queue on
    // every off-main call, so a blocked main thread deadlocked the very create
    // being waited for. The key is now cached (see -beginObservingAPIKey), and
    // blocking here is the standing proof of it: reintroduce the main-queue
    // read and this line times out.
    for (NSUInteger i = 0; i < n; i++) dispatch_semaphore_signal(go);
    XCTAssertEqual(dispatch_group_wait(done,
        dispatch_time(DISPATCH_TIME_NOW, 10 * NSEC_PER_SEC)), 0,
        @"the 401 deliveries did not complete while the main thread was blocked -- "
         "the map-session refresh path is hopping to the main queue again");

    // Let the single create land. Staging's credential TTL is 20s; three
    // seconds is the same budget the test above uses and is well inside it.
    XCTestExpectation *settled = [self expectationWithDescription:@"session"];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 3 * NSEC_PER_SEC),
                   dispatch_get_main_queue(), ^{ [settled fulfill]; });
    [self waitForExpectations:@[settled] timeout:10];

    // THE ASSERTION. Every increment of this counter is a billed map load. Eight
    // simultaneous bootstrap 401s must produce exactly one. Without the
    // `_refreshInFlight` gate this is > 1 (observed 8).
    XCTAssertEqual([s refreshCallCountForTesting], 1,
        @"%lu concurrent bootstrap 401s must coalesce into ONE session create -- "
         "each extra attempt here is an extra BILLED map load at every cold start",
        (unsigned long)n);

    // ...and the one create must have actually worked, or the assertion above
    // would also be satisfied by a refresh path that is simply broken.
    XCTAssertGreaterThan(s.secondsUntilExpiry, 0,
        @"the single create should have yielded a usable credential");
    XCTAssertTrue([[s signedURLForRequestURL:tile].query containsString:@"sig="],
        @"tiles should sign once the credential is held");
}

// Pulls the `s` (session id) query item off a URL that MMMapSession has
// signed, so the test can assert the same credential authorised every tile
// in the loop without needing a production-code accessor for the private
// session id ivar.
- (NSString *)sessionIdFromSignedURL:(NSURL *)url {
    NSURLComponents *c = [NSURLComponents componentsWithURL:url resolvingAgainstBaseURL:NO];
    for (NSURLQueryItem *item in c.queryItems) {
        if ([item.name isEqualToString:@"s"]) return item.value;
    }
    return nil;
}

@end
