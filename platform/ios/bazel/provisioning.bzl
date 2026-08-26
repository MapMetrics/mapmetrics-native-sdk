load("@darwin_config//:config.bzl", "APPLE_MOBILE_PROVISIONING_PROFILE_NAME", "APPLE_MOBILE_PROVISIONING_PROFILE_TEAM_ID")
load("@rules_apple//apple:apple.bzl", "local_provisioning_profile")
load("@rules_xcodeproj//xcodeproj:defs.bzl", "xcode_provisioning_profile")

def configure_device_profiles():
    local_provisioning_profile(
        name = "provisioning_profile",
        profile_name = APPLE_MOBILE_PROVISIONING_PROFILE_NAME,
        team_id = APPLE_MOBILE_PROVISIONING_PROFILE_TEAM_ID,
    )

    xcode_provisioning_profile(
        name = "xcode_profile",
        managed_by_xcode = True,
        provisioning_profile = ":provisioning_profile",
        visibility = ["//visibility:public"],
    )

def device_only_provisioning_profile():
    """Attach a provisioning profile on device builds, and nothing on simulator.

    Every ios_application here used to set `provisioning_profile =
    "xcode_profile"` unconditionally. Because that is a dependency edge, bazel
    resolved `local_provisioning_profile` before it ever considered the target
    platform -- so a SIMULATOR build failed on missing device signing assets:

        error: no provisioning profile was found named
               'iOS Team Provisioning Profile: *'

    Simulators do not verify code signatures, so nobody needs an Apple
    Developer account, a team ID, or an installed profile to run these example
    apps. Requiring one made the apps unbuildable for anyone without the exact
    profile named in platform/darwin/bazel/config.bzl, which is per-machine and
    gitignored -- so in practice, unbuildable for anyone but the person who set
    it up.

    Device builds are unaffected: they still resolve :xcode_profile and still
    fail loudly when signing assets are missing, which is correct, because a
    device build genuinely cannot be installed without them.
    """
    return select({
        "@build_bazel_apple_support//constraints:device": ":xcode_profile",
        "//conditions:default": None,
    })
