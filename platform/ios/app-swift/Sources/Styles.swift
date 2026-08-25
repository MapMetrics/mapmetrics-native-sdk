import Foundation

/// The MapMetrics demo style. No credential, and none needed.
///
/// This is what the examples in this app render. Every one of them used to
/// point at somebody else's tile server -- MapLibre's demotiles, Americana,
/// OpenFreeMap, VersaTiles, Protomaps with an inline key -- which meant the
/// showcase app for OUR renderer was a showcase for their infrastructure, and
/// broke whenever they rotated a key or moved a URL.
///
/// It is rate-limited, capped at zoom 12 and watermarked: enough to show a map
/// rendering, deliberately not enough to ship. Get a real key at
/// https://mapatlas.eu .
///
/// NOT FOR OFFLINE EXAMPLES. OfflinePackExample and ManageOfflineRegionsExample
/// download whole regions -- thousands of tiles at once, past zoom 12 -- which
/// the demo endpoint is built to refuse. Those two keep a third-party style.
let MAPMETRICS_DEMO_STYLE = URL(string: "https://gateway.mapmetrics-atlas.net/demo/style.json")

// #-example-code(ExampleStyles)

let DEMOTILES_STYLE = URL(string: "https://demotiles.maplibre.org/style.json")
let AMERICANA_STYLE = URL(string: "https://americanamap.org/style.json")
let OPENFREEMAP_LIBERTY_STYLE = URL(string: "https://tiles.openfreemap.org/styles/liberty")
let OPENFREEMAP_BRIGHT_STYLE = URL(string: "https://tiles.openfreemap.org/styles/bright")
let VERSATILES_COLORFUL_STYLE = URL(string: "https://tiles.versatiles.org/assets/styles/colorful.json")

// #-end-example-code

private func protomaps(_ style: String) -> URL? {
    URL(string: "https://api.protomaps.com/styles/v2/\(style).json?key=e761cc7daedf832a")
}

let PROTOMAPS_LIGHT_STYLE = protomaps("light")
let PROTOMAPS_DARK_STYLE = protomaps("dark")
let PROTOMAPS_GRAYSCALE_STYLE = protomaps("grayscale")
let PROTOMAPS_WHITE_STYLE = protomaps("white")
let PROTOMAPS_BLACK_STYLE = protomaps("black")
