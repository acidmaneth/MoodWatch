import SwiftUI

/// The user's saved Moods. Tapping one puts it on the face and returns to the face.
struct CollectionView: View {
    @EnvironmentObject private var model: AppModel
    @State private var pendingRemoval: Int?

    var body: some View {
        List {
            Section {
                ForEach(model.collectionIds, id: \.self) { id in
                    row(id)
                }
            } header: {
                Text(model.collectionIds.isEmpty
                     ? "No Moods saved yet. Tap “Add to Collection” in the Moods app to save the one on your face."
                     : "Tap to show · hold to remove")
                    .font(MoodFont.text(13))
                    .foregroundStyle(.secondary)
                    .textCase(nil)
                    .multilineTextAlignment(.center)
                    .frame(maxWidth: .infinity)
            } footer: {
                if let message = model.collectionMessage {
                    Text(message)
                        .foregroundStyle(.red)
                        .frame(maxWidth: .infinity)
                }
            }
        }
        .font(MoodFont.text(16, bold: true))
        .navigationTitle("Collection")
        .containerBackground(Color.black, for: .navigation)
        .alert(
            pendingRemoval.map { "Remove #\($0) from your Collection?" } ?? "",
            isPresented: Binding(
                get: { pendingRemoval != nil },
                set: { if !$0 { pendingRemoval = nil } }
            ),
            presenting: pendingRemoval
        ) { id in
            Button("Remove", role: .destructive) { model.removeFromCollection(id) }
            Button("Cancel", role: .cancel) {}
        }
    }

    private func row(_ id: Int) -> some View {
        let isCurrent = id == model.tokenId
        return CollectionRow(id: id, isCurrent: isCurrent, isSwitching: model.switchingTo == id)
            .contentShape(Rectangle())
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(.isButton)
            .accessibilityIdentifier("row-\(id)")
            .onTapGesture { model.switchTo(id) }
            .onLongPressGesture { pendingRemoval = id }
            .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button(role: .destructive) {
                    model.removeFromCollection(id)
                } label: {
                    Label("Remove", systemImage: "trash")
                }
            }
            .listRowBackground(
                RoundedRectangle(cornerRadius: 24, style: .continuous)
                    .fill(Color.white.opacity(isCurrent ? 0.2 : 0.12))
                    .overlay {
                        if isCurrent {
                            RoundedRectangle(cornerRadius: 24, style: .continuous)
                                .strokeBorder(Color.white.opacity(0.7), lineWidth: 1.5)
                        }
                    }
            )
    }
}

struct CollectionRow: View {
    @EnvironmentObject private var model: AppModel
    let id: Int
    let isCurrent: Bool
    let isSwitching: Bool

    @State private var traits: String?
    @State private var image: CGImage?

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if let image {
                    Image(decorative: image, scale: 1).resizable().scaledToFill()
                } else {
                    Color.white.opacity(0.15)
                }
            }
            .frame(width: 40, height: 40)
            .clipShape(Circle())

            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: isCurrent ? "#\(id) ✓" : "#\(id)")
                    .font(MoodFont.text(17, bold: true))
                if let traits {
                    Text(traits)
                        .font(MoodFont.text(13))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
            }

            Spacer(minLength: 0)

            if isSwitching {
                ProgressView()
                    .frame(width: 20, height: 20)
            }
        }
        .padding(.vertical, 4)
        .task(id: id) { await loadThumb() }
    }

    /// Shows the cached thumbnail, and fetches a new one if Moods have changed since.
    private func loadThumb() async {
        let cached = model.cachedThumb(id)
        if let cached {
            show(cached)
            if cached.isFresh { return }
        }
        if let fresh = try? await model.fetchThumb(id) {
            show(fresh)
        }
    }

    private func show(_ thumb: CollectionThumb) {
        traits = thumb.traits
        image = ImageTools.cgImage(from: thumb.image)
    }
}
