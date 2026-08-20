#import <Foundation/Foundation.h>
#import "MLNFoundation.h"

NS_ASSUME_NONNULL_BEGIN

/**
 Installs a global `MLNNetworkConfigurationDelegate` that signs outgoing v2
 tile requests with the process-wide `MMMapSession` credential, and adopts
 replacement credentials from rollover responses.

 See wiki/synthesis/2026-08-18-v2-map-session-client-contract.md.
 */
MLN_EXPORT
@interface MMMapSessionNetworkDelegate : NSObject

/// Installs the delegate on `MLNNetworkConfiguration.sharedManager`, retaining
/// it strongly (the `delegate` property is weak). Idempotent -- safe to call
/// more than once; only the first call has any effect.
+ (void)install;

@end

NS_ASSUME_NONNULL_END
