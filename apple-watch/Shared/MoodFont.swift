import SwiftUI
import UIKit

/// Brice SemiBold SemiExpanded when it is bundled, otherwise Fredoka, the same bubbly face used on
/// the Pixel watch. Brice is commercially licensed, so it is only bundled from the git-ignored
/// private-fonts/ folder.
enum MoodFont {
    private static let brice = "Brice-SemiBoldSemiExpanded"
    private static let hasBrice = UIFont(name: brice, size: 12) != nil
    /// Brice is about 1.5 times as wide as Fredoka, so it is set smaller to fit the same layouts.
    private static let briceScale: CGFloat = 0.85

    static func text(_ size: CGFloat, bold: Bool = false) -> Font {
        if hasBrice {
            return .custom(brice, size: size * briceScale)
        }
        return .custom(bold ? "Fredoka-Bold" : "Fredoka-Medium", size: size)
    }

    /// Design for the system text around it. A rounded design swaps Brice for SF Rounded, so it is
    /// only used alongside Fredoka.
    static var systemDesign: Font.Design? { hasBrice ? nil : .rounded }
}
