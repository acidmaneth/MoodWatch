import SwiftUI
import WidgetKit

@main
struct MoodsWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "MoodsWidget", provider: MoodProvider()) { entry in
            MoodWidgetEntryView(entry: entry)
        }
        .configurationDisplayName("Moods NFT")
        .description("Shows the Mood on your Moods app.")
        .supportedFamilies([.accessoryRectangular, .accessoryCircular, .accessoryCorner, .accessoryInline])
    }
}

struct MoodProvider: TimelineProvider {
    /// Short timeouts keep a stale-data fetch within the widget's time budget.
    private static let repository = MoodRepository(api: MoodAPI(requestTimeout: 8, resourceTimeout: 12))

    func placeholder(in context: Context) -> MoodEntry {
        MoodEntry(date: Date(), tokenId: Moods.defaultTokenId, traits: "Euphoric · Puppy Eyes Big Smile", image: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (MoodEntry) -> Void) {
        completion(Self.entry(family: context.family, displaySize: context.displaySize))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<MoodEntry>) -> Void) {
        let family = context.family, displaySize = context.displaySize, isPreview = context.isPreview
        // WidgetKit accepts the completion from any thread.
        nonisolated(unsafe) let completion = completion
        Task {
            if !isPreview {
                await Self.repository.checkForDrift()
            }
            let now = Date()
            let entry = Self.entry(family: family, displaySize: displaySize, at: now)
            completion(Timeline(entries: [entry], policy: .after(Self.nextReload(after: now))))
        }
    }

    /// Every 10 minutes while today's change is still expected, otherwise just after the next one.
    static func nextReload(after now: Date) -> Date {
        let store = MoodStore.shared
        if !store.isDoneToday(now), now >= DriftClock.windowStart(on: now), now < DriftClock.giveUpTime(on: now) {
            return now.addingTimeInterval(10 * 60)
        }
        return DriftClock.nextSettle(after: now).addingTimeInterval(5 * 60)
    }

    /// Widget images over the family's pixel area fail to render, so the image is decoded at
    /// the size it is shown (watch screens are @2x).
    static func entry(family: WidgetFamily, displaySize size: CGSize, at date: Date = Date()) -> MoodEntry {
        let store = MoodStore.shared
        let side = family == .accessoryRectangular ? size.height : max(size.width, size.height)
        let pixels = min(240, max(64, Int((side * 2).rounded(.up))))
        return MoodEntry(
            date: date,
            tokenId: store.tokenId,
            traits: store.currentInfo?.traits,
            image: store.currentImage.flatMap { ImageTools.cgImage(from: $0, maxPixelSize: pixels) }
        )
    }
}

private struct MoodWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: MoodEntry

    var body: some View {
        MoodWidgetView(entry: entry, family: family)
    }
}
