import SwiftUI
import WidgetKit

struct MoodEntry: TimelineEntry {
    let date: Date
    let tokenId: Int
    let traits: String?
    let image: CGImage?
}

struct MoodWidgetView: View {
    let entry: MoodEntry
    let family: WidgetFamily

    var body: some View {
        content
            .containerBackground(for: .widget) {
                if family == .accessoryRectangular {
                    WidgetArtwork(image: entry.image)
                        .blur(radius: 10)
                        .overlay(Color.black.opacity(0.55))
                } else {
                    Color.clear
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch family {
        case .accessoryRectangular:
            HStack(spacing: 8) {
                WidgetArtwork(image: entry.image)
                    .aspectRatio(1, contentMode: .fit)
                    .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))
                VStack(alignment: .leading, spacing: 0) {
                    Text(verbatim: "#\(entry.tokenId)")
                        .font(MoodFont.text(17, bold: true))
                        .widgetAccentable()
                    ForEach(Array(traitLines.enumerated()), id: \.offset) { index, line in
                        Text(line)
                            .lineLimit(1)
                            .minimumScaleFactor(index == 0 ? 0.8 : 0.6)
                    }
                    .font(MoodFont.text(11, bold: true))
                    .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
        case .accessoryCorner:
            WidgetArtwork(image: entry.image)
                .clipShape(Circle())
                .widgetLabel {
                    Text(verbatim: "#\(entry.tokenId)")
                }
        case .accessoryInline:
            Text(verbatim: "Mood #\(entry.tokenId)")
        default:
            WidgetArtwork(image: entry.image)
                .clipShape(Circle())
        }
    }

    /// "Euphoric · Puppy Eyes Big Smile" → ["Euphoric", "Puppy Eyes Big Smile"].
    private var traitLines: [String] {
        guard let traits = entry.traits, !traits.isEmpty else { return ["Moods"] }
        let parts = traits.components(separatedBy: " · ")
        return [parts[0], parts.dropFirst().joined(separator: " · ")].filter { !$0.isEmpty }
    }
}

/// The Mood image, kept in full color on tinted faces where watchOS allows it.
struct WidgetArtwork: View {
    let image: CGImage?

    var body: some View {
        let artwork = (image.map { Image(decorative: $0, scale: 2) } ?? Image("MoodPreviewSmall")).resizable()
        if #available(watchOS 11.0, *) {
            artwork
                .widgetAccentedRenderingMode(.fullColor)
                .aspectRatio(1, contentMode: .fill)
        } else {
            artwork
                .aspectRatio(1, contentMode: .fill)
        }
    }
}
