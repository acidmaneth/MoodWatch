import SwiftUI

/// Mirrors the Android Moods app: the current Mood, and ways to change it.
struct MenuView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        List {
            header
                .listRowBackground(Color.clear)

            NavigationLink(value: MenuRoute.collection) {
                Text("Collection (\(model.collectionIds.count))")
            }

            Button("Pick random") { model.pickRandom() }

            Button(model.isCollected ? "In Collection ✓ (remove)" : "Add to Collection") {
                model.toggleCollected()
            }
            .disabled(!model.canCollect)

            NavigationLink(value: MenuRoute.keypad) {
                Text("Type a Mood ID")
            }

            Button("Refresh now") { model.refreshNow() }
        }
        .font(MoodFont.text(16, bold: true))
        .navigationTitle("Moods")
        .containerBackground(Color.black, for: .navigation)
        .navigationDestination(for: MenuRoute.self) { route in
            switch route {
            case .collection: CollectionView()
            case .keypad: KeypadView()
            }
        }
    }

    private var header: some View {
        VStack(spacing: 4) {
            MoodArtwork(image: model.image)
                .aspectRatio(1, contentMode: .fill)
                .frame(width: 72, height: 72)
                .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

            Text(model.title)
                .font(MoodFont.text(17, bold: true))
                .multilineTextAlignment(.center)
                .accessibilityIdentifier("title")

            Text(model.detail)
                .font(MoodFont.text(13))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)

            if model.isLoading {
                ProgressView()
                    .frame(height: 24)
            }
        }
        .frame(maxWidth: .infinity)
    }
}
