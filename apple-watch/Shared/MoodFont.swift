import SwiftUI

/// Fredoka, the same bubbly face used on the Pixel watch.
enum MoodFont {
    static func text(_ size: CGFloat, bold: Bool = false) -> Font {
        .custom(bold ? "Fredoka-Bold" : "Fredoka-Medium", size: size)
    }
}
