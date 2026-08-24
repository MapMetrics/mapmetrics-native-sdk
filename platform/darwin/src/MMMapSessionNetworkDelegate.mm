#import "MMMapSessionNetworkDelegate.h"
#import "MMMapSession.h"
#import "MMMapSession_Private.h"
#import "MLNNetworkConfiguration.h"
#import "MLNNetworkResponse.h"

@interface MMMapSessionNetworkDelegate () <MLNNetworkConfigurationDelegate>
@end

@implementation MMMapSessionNetworkDelegate

+ (void)install {
    static MMMapSessionNetworkDelegate *retained;   // MLNNetworkConfiguration
    static dispatch_once_t once;                    // holds `delegate` WEAKLY.
    dispatch_once(&once, ^{
        retained = [[MMMapSessionNetworkDelegate alloc] init];
    });
    // Re-ASSERT rather than install-once. `delegate` is a public, weak
    // property: a host app that sets its own displaces ours, which silently
    // unhooks tile signing for the rest of the process. Checking every time
    // means a later call (notably the foreground hook in MMMapSession) can
    // put signing back.
    if (MLNNetworkConfiguration.sharedManager.delegate != retained) {
        MLNNetworkConfiguration.sharedManager.delegate = retained;
    }
}

// Called off the main thread, immediately before dataTaskWithRequest:.
- (NSMutableURLRequest *)willSendRequest:(NSMutableURLRequest *)request {
    // BEFORE signing, and before the first tile. The style request reaches the
    // gateway ahead of every tile, so learning the origin and buying the first
    // window here is what puts a credential in hand for the opening wave.
    // Without it the SDK only ever acquires one by being 401'd, which a
    // v1-shaped `?token=` tile never is -- it returns 200 and bills per tile.

    [[MMMapSession sharedSession] noteGatewayRequestURL:request.URL];

    // -noteGatewayRequestURL: may have just STARTED a create, and the create is
    // an async POST. Without this wait the request that triggered it -- and the
    // whole wave behind it -- would leave before the credential landed and go
    // out unsigned. That is invisible rather than broken: a v1-shaped `?token=`
    // tile returns 200 either way, and bills once PER TILE instead of once per
    // session. Bounded, and fails open; see -awaitCredentialForURL:.
    [[MMMapSession sharedSession] awaitCredentialForURL:request.URL];

    NSURL *signedURL = [[MMMapSession sharedSession] signedURLForRequestURL:request.URL];
    if (signedURL && ![signedURL isEqual:request.URL]) request.URL = signedURL;
    return request;
}

// The session id the request was signed with, or nil if it went out unsigned.
static NSString *MMSessionIdFromURL(NSURL *url) {
    NSURLComponents *c = [NSURLComponents componentsWithURL:url resolvingAgainstBaseURL:NO];
    for (NSURLQueryItem *item in c.queryItems) {
        if ([item.name isEqualToString:@"s"]) return item.value;
    }
    return nil;
}

// Called off the main thread, BEFORE any status-code interpretation.
// X-Map-Session-* means a rollover: the credential had already expired, the
// gateway minted a replacement and CHARGED for it.
- (MLNNetworkResponse *)didReceiveResponse:(MLNNetworkResponse *)response {
    NSURLResponse *raw = response.response;
    if ([raw isKindOfClass:NSHTTPURLResponse.class]) {
        NSHTTPURLResponse *http = (NSHTTPURLResponse *)raw;
        MMMapSession *session = [MMMapSession sharedSession];

        // Capture the result and FALL THROUGH. A 401 can carry rollover
        // headers that are then refused (unknown account, or not newer); with
        // an `else if` that response consumed the header branch and no
        // refresh was ever scheduled, leaving no usable credential and
        // nothing on its way to fix that.
        BOOL adopted = NO;
        if (http.allHeaderFields[@"X-Map-Session-Sig"]) {
            adopted = [session applyCredentialFromHeaders:http.allHeaderFields
                                              responseURL:http.URL];
        }

        if (!adopted && http.statusCode == 401 &&
            MMURLIsTileShaped(http.URL) &&
            // Only if this 401 concerns the credential we are actually
            // holding. Many tiles are in flight at once, so a key rotation
            // 401s all of them; without this every straggler would buy its
            // own billed window. See -shouldRefreshForResponseSessionId:.
            [session shouldRefreshForResponseSessionId:MMSessionIdFromURL(http.URL)]) {
            // Task 1's patch turns this into a retryable error, so recovering
            // here means the retry succeeds instead of the tile staying blank.
            [session refreshNow];
        }
    }
    return response;
}

@end
