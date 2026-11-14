#if DEBUG
import SwiftUI
import WidgetKit

/// The widget's views at complication sizes, for screenshots.
struct WidgetGalleryView: View {
    let entry: MoodEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            MoodWidgetView(entry: entry, family: .accessoryInline)
                .font(MoodFont.text(15, bold: true))
            MoodWidgetView(entry: entry, family: .accessoryRectangular)
                .padding(8)
                .frame(height: 76)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Color.white.opacity(0.12)))
            HStack(spacing: 10) {
                MoodWidgetView(entry: entry, family: .accessoryCircular)
                    .frame(width: 50, height: 50)
                MoodWidgetView(entry: entry, family: .accessoryCorner)
                    .frame(width: 34, height: 34)
                Text(verbatim: "#\(entry.tokenId)")
                    .font(MoodFont.text(13, bold: true))
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 6)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .background(Color.black.ignoresSafeArea())
    }
}
#endif
