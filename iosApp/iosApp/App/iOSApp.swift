import SwiftUI
import SharedLogic

@main
struct iOSApp: App {
    /// App-scoped shared graph (settings persistence, processing pipeline). Creating it starts
    /// the settings preload off the main thread (NFR-01).
    private let graph: SharedGraph
    @StateObject private var store: MirrorStore

    init() {
        let graph = SharedGraph(keyValueStore: UserDefaultsKeyValueStore(defaults: .standard))
        self.graph = graph
        _store = StateObject(wrappedValue: AppEnvironment.makeStore(graph: graph))
    }

    var body: some Scene {
        WindowGroup {
            RootView(store: store)
        }
    }
}
