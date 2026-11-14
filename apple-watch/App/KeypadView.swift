import SwiftUI

/// A number pad for typing a Mood ID. It starts empty each time, like the Android app.
struct KeypadView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var digits = Self.initialDigits

    private static let maxDigits = 6
    private static let rows = [["1", "2", "3"], ["4", "5", "6"], ["7", "8", "9"], ["C", "0", "⌫"]]
    private let spacing: CGFloat = 4

    private static var initialDigits: String {
        #if DEBUG
        DebugLaunch.keypadDigits
        #else
        ""
        #endif
    }

    var body: some View {
        GeometryReader { geometry in
            let rowHeight = max(20, (geometry.size.height - spacing * 4) / 5)
            VStack(spacing: spacing) {
                ForEach(Self.rows, id: \.self) { row in
                    HStack(spacing: spacing) {
                        ForEach(row, id: \.self) { key in
                            Button { press(key) } label: {
                                keyLabel(key)
                            }
                            .buttonStyle(KeyButtonStyle())
                            .accessibilityLabel(accessibilityName(key))
                        }
                    }
                    .frame(height: rowHeight)
                }

                Button("Show this Mood", action: submit)
                    .buttonStyle(KeyButtonStyle(fill: .accentColor, font: MoodFont.text(15, bold: true)))
                    .frame(height: rowHeight)
                    .padding(.horizontal, 10)
                    .disabled(digits.isEmpty)
                    .opacity(digits.isEmpty ? 0.45 : 1)
            }
        }
        .padding(.bottom, 10)
        .ignoresSafeArea(edges: .bottom)
        .navigationTitle(digits.isEmpty ? "Mood ID" : "#" + digits)
        .containerBackground(Color.black, for: .navigation)
    }

    @ViewBuilder
    private func keyLabel(_ key: String) -> some View {
        switch key {
        case "⌫": Image(systemName: "delete.left")
        case "C": Text("C").foregroundStyle(.secondary)
        default: Text(key)
        }
    }

    private func accessibilityName(_ key: String) -> String {
        switch key {
        case "⌫": "Delete"
        case "C": "Clear"
        default: key
        }
    }

    private func press(_ key: String) {
        switch key {
        case "⌫": if !digits.isEmpty { digits.removeLast() }
        case "C": digits = ""
        default:
            guard digits.count < Self.maxDigits, !(digits.isEmpty && key == "0") else { return }
            digits += key
        }
    }

    private func submit() {
        guard let id = Int(digits) else { return }
        model.show(id)
        dismiss()
    }
}

struct KeyButtonStyle: ButtonStyle {
    var fill: Color = Color.white.opacity(0.16)
    var font: Font = MoodFont.text(20, bold: true)

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(font)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
            .foregroundStyle(.white)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(fill)
                    .opacity(configuration.isPressed ? 0.5 : 1)
            )
            .contentShape(Rectangle())
    }
}
