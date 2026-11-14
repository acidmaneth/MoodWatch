#if DEBUG
import SwiftUI

/// Launch arguments for screenshots and UI checks in Debug builds, e.g.
/// `xcrun simctl launch <udid> com.moodwatch.app.watch -screen collection -seedCollection 904,1247,3094`.
///
/// - `-screen menu|keypad|collection|widgets`: opens that screen.
/// - `-token 1184`: loads that Mood.
/// - `-seedCollection 904,1247,3094`: replaces the Collection, newest first.
/// - `-keypadDigits 555`: pre-fills the number pad.
/// - `-alwaysOn YES`: renders the face dimmed, as in always-on mode.
enum DebugLaunch {
    private static var defaults: UserDefaults { .standard }

    static var alwaysOn: Bool { defaults.bool(forKey: "alwaysOn") }
    static var keypadDigits: String { defaults.string(forKey: "keypadDigits") ?? "" }

    @MainActor
    static func apply(to model: AppModel) {
        if let token = defaults.string(forKey: "token").flatMap(Int.init) {
            model.show(token)
        }
        if let seed = defaults.string(forKey: "seedCollection") {
            model.debugReplaceCollection(seed.split(separator: ",").compactMap { Int($0) })
        }
        switch defaults.string(forKey: "screen") {
        case "menu":
            model.openMenu()
        case "keypad":
            model.openMenu()
            model.menuPath = [.keypad]
        case "collection":
            model.openMenu()
            model.menuPath = [.collection]
        case "widgets":
            model.showingWidgetGallery = true
        default:
            break
        }
    }
}
#endif
