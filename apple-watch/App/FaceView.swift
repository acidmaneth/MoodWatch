import SwiftUI

/// The main screen: the Mood edge to edge, with the date at the top and the time at the bottom.
struct FaceView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.isLuminanceReduced) private var isLuminanceReduced
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        face
        .sheet(isPresented: $model.showingMenu) {
            NavigationStack(path: $model.menuPath) {
                MenuView()
            }
            .environmentObject(model)
        }
        .onChange(of: scenePhase, initial: true) { _, phase in
            model.scenePhaseChanged(phase)
        }
        #if DEBUG
        .task { DebugLaunch.apply(to: model) }
        .fullScreenCover(isPresented: $model.showingWidgetGallery) {
            WidgetGalleryView(entry: model.widgetEntry)
        }
        #endif
    }

    private var face: some View {
        ZStack {
            Color.black
                .overlay {
                    MoodArtwork(image: model.image)
                        .aspectRatio(1, contentMode: .fill)
                }
                .overlay {
                    // Always-on: dim to roughly the 12% luminance the Android face uses.
                    Color.black.opacity(isDimmed ? 0.88 : 0)
                }
                .clipped()

            TimelineView(.everyMinute) { context in
                ClockText(date: context.date)
            }
        }
        .ignoresSafeArea()
        // The public status-bar API is unavailable on watchOS. This is what hides the corner clock.
        ._statusBarHidden(true)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("face")
        .onTapGesture { model.openMenu() }
    }

    private var isDimmed: Bool {
        #if DEBUG
        if DebugLaunch.alwaysOn { return true }
        #endif
        return isLuminanceReduced
    }
}

struct ClockText: View {
    let date: Date

    var body: some View {
        VStack {
            Text(date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()))
                .font(MoodFont.text(20))
                .padding(.top, 18)
            Spacer()
            Text(date.formatted(.dateTime.hour(.defaultDigits(amPM: .omitted)).minute(.twoDigits)))
                .font(MoodFont.text(32, bold: true))
                .monospacedDigit()
                .padding(.bottom, 14)
        }
        .lineLimit(1)
        .minimumScaleFactor(0.6)
        .foregroundStyle(.white)
        .shadow(color: .black.opacity(0.38), radius: 4, x: 0, y: 1)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The saved Mood, or the bundled preview before the first download.
struct MoodArtwork: View {
    let image: CGImage?

    var body: some View {
        if let image {
            Image(decorative: image, scale: 1).resizable()
        } else {
            Image("MoodPreview").resizable()
        }
    }
}
