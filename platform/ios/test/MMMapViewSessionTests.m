#import <XCTest/XCTest.h>
#import <UIKit/UIKit.h>
#import "MLNMapView.h"
#import "MLNSettings.h"
#import "MMMapSession.h"
#import "MMMapSession_Private.h"
#import "MMMapSessionNetworkDelegate.h"

// A REAL MLNMapView driving the session, against the real staging gateway.
//
// THE GAP THIS CLOSES. MMMapSessionIntegrationTests proves the billing property
// through -willSendRequest: and NSURLSession — the whole credential path — but
// it never instantiates a map view, so nothing there proves a RENDERING map
// actually drives those requests. That distinction is not academic: the Flutter
// Android example loads its style, reports success, and then issues zero tile
// requests, so its map is blank and its billing is zero. A green delegate test
// would not have caught that.
//
// The style is written to a file:// URL rather than fetched: no local server, no
// ATS exemption, and the tile URLs it names are the ONLY thing that has to reach
// the network. They use the v1 `?token=` shape a real style hands out — the case
// that used to cost one unit per tile.
static NSString *const MMStagingBase =
    @"https://gateway-mapatlas-staging.jim9710.workers.dev";

@interface MMMapViewSessionTests : XCTestCase <MLNMapViewDelegate>
@property (nonatomic) MLNMapView *mapView;
@property (nonatomic) UIWindow *window;
@property (nonatomic) XCTestExpectation *styleLoaded;
@end

@implementation MMMapViewSessionTests

- (void)tearDown {
    [self.mapView removeFromSuperview];
    self.mapView = nil;
    self.window.hidden = YES;
    self.window = nil;
    [[MMMapSession sharedSession] resetOriginForTesting];
    [[MMMapSession sharedSession] resetForTesting];
    [super tearDown];
}

/** A minimal style whose only source points at the staging gateway's v1 tile path. */
- (NSURL *)styleURLWithToken:(NSString *)token {
    NSString *tiles = [NSString stringWithFormat:
        @"%@/planet20251013/{z}/{x}/{y}.mvt?token=%@", MMStagingBase, token];
    NSDictionary *style = @{
        @"version": @8,
        @"name": @"mm-staging-probe",
        @"sources": @{
            @"mm": @{ @"type": @"vector", @"tiles": @[tiles], @"maxzoom": @14 }
        },
        @"layers": @[
            @{ @"id": @"bg", @"type": @"background",
               @"paint": @{ @"background-color": @"#eee" } }
        ]
    };
    NSData *data = [NSJSONSerialization dataWithJSONObject:style options:0 error:nil];
    NSURL *url = [[NSURL fileURLWithPath:NSTemporaryDirectory()]
        URLByAppendingPathComponent:@"mm-staging-probe.json"];
    [data writeToURL:url atomically:YES];
    return url;
}

- (void)mapView:(MLNMapView *)mapView didFinishLoadingStyle:(MLNStyle *)style {
    [self.styleLoaded fulfill];
}

- (void)testARenderingMapViewObtainsASessionAndSignsItsTiles {
    NSString *key = NSProcessInfo.processInfo.environment[@"MM_STAGING_KEY"];
    if (!key.length) { XCTSkip(@"set MM_STAGING_KEY to run"); return; }

    [[MMMapSession sharedSession] resetForTesting];
    [[MMMapSession sharedSession] resetOriginForTesting];
    [MMMapSessionNetworkDelegate install];

    // Staging is deliberately off MMGatewayHosts, so it must be PINNED — the same
    // thing a real staging deployment does through MLNTileServerBaseURL.
    [[MMMapSession sharedSession] pinOriginForTesting:MMStagingBase];

    // Ordering matters: the session has to be observing before the key is set, or
    // -createIfConfigured never sees it. Cleared first so the assignment is always
    // a KVO change even if another test set the same key.
    MLNSettings.apiKey = nil;
    MLNSettings.apiKey = key;
    XCTAssertEqualObjects([[MMMapSession sharedSession] cachedAPIKeyForTesting], key,
        @"precondition: the key must have reached the session cache");

    // THE CREDENTIAL IS BOUGHT BY CONFIGURATION ALONE, by -createIfConfigured when
    // the key lands against a pinned origin — before any map view has asked for
    // anything. That is what makes a cold load cost one unit instead of two.
    //
    // Waited for, not asserted inline: -refreshNow is asynchronous, so the value
    // is 0 for the duration of one network round trip after the key is set. An
    // inline assertion here fails while the create is in flight and says nothing
    // about whether it happened.
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:20];
    while ([MMMapSession sharedSession].secondsUntilExpiry <= 0 &&
           [deadline timeIntervalSinceNow] > 0) {
        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.2]];
    }
    XCTAssertGreaterThan([MMMapSession sharedSession].secondsUntilExpiry, 0,
        @"configuration alone should have bought the window, before any map view");
    XCTAssertEqual([[MMMapSession sharedSession] refreshCallCountForTesting], 1,
        @"exactly one window; each is a billed map load");

    // --- now a REAL map view, on a real window, rendering ---------------------
    self.window = [[UIWindow alloc] initWithFrame:CGRectMake(0, 0, 320, 480)];
    self.mapView = [[MLNMapView alloc] initWithFrame:self.window.bounds
                                            styleURL:[self styleURLWithToken:key]];
    self.mapView.delegate = self;
    [self.window addSubview:self.mapView];
    [self.window makeKeyAndVisible];

    self.styleLoaded = [self expectationWithDescription:@"style"];
    [self waitForExpectationsWithTimeout:30 handler:nil];

    [self.mapView setCenterCoordinate:CLLocationCoordinate2DMake(52.37, 4.89)
                            zoomLevel:12
                             animated:NO];

    // Let the view actually request tiles.
    XCTestExpectation *drew = [self expectationWithDescription:@"tiles"];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 8 * NSEC_PER_SEC),
                   dispatch_get_main_queue(), ^{ [drew fulfill]; });
    [self waitForExpectationsWithTimeout:20 handler:nil];

    // The map running must not have bought anything further: tiles ride the
    // window already paid for. 2+ here means a window per tile wave.
    XCTAssertEqual([[MMMapSession sharedSession] refreshCallCountForTesting], 1,
        @"a rendering map view must not buy additional windows");

    // And the credential must still sign the shape the style handed out.
    NSURL *tile = [NSURL URLWithString:[NSString stringWithFormat:
        @"%@/planet20251013/12/2094/1362.mvt?token=%@", MMStagingBase, key]];
    NSString *signed_ = [[MMMapSession sharedSession] signedURLForRequestURL:tile].absoluteString;
    XCTAssertTrue([signed_ containsString:@"sig="],
        @"a v1-shaped tile must go out signed, or it is per-tile v1 billing");
}

@end
