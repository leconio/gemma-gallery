import SwiftUI
import GemmaShared

@main
struct GemmaPocketApp: App {
    @StateObject private var services = IOSServices()
    var body: some Scene {
        WindowGroup {
            SharedScreen(services: services).ignoresSafeArea()
                .preferredColorScheme(.light)
        }
    }
}

private struct SharedScreen: UIViewControllerRepresentable {
    let services: IOSServices
    func makeUIViewController(context: Context) -> UIViewController {
        let controller = MainViewControllerKt.MainViewController(host: services,
            autoSmoke: ProcessInfo.processInfo.arguments.contains("--self-test"))
        services.presenter = controller
        return controller
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
