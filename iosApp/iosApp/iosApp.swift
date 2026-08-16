import SwiftUI
import Termish

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

@main
struct TermishIosApp: App {
    var body: some Scene {
        WindowGroup {
            ComposeView()
                // 忽略全部安全区：Compose 自己处理状态栏/Home 指示条与键盘 insets，
                // 否则 SwiftUI 默认把内容框在安全区内，顶部状态栏/底部指示条露出
                // UIWindow 的白色背景（暗色模式下头部/底部发白）
                .ignoresSafeArea()
        }
    }
}
