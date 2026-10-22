import SwiftUI
import Shared

/// Die iPhone-App ist nur eine Hülle: Spiel und Oberfläche kommen aus dem gemeinsamen Kotlin-Code (Shared).
@main
struct WhoAmIApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                .ignoresSafeArea(.all)
                .onOpenURL { url in
                    // Einladungslink whoami://join?code=ABCD
                    MainViewControllerKt.handleOpenUrl(url: url.absoluteString)
                }
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        // „-autostart“: Probestart im Simulator (CI) startet direkt ein Einzelspiel
        MainViewControllerKt.MainViewController(autoStart: ProcessInfo.processInfo.arguments.contains("-autostart"))
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
