pub struct AppStrings {
    pub launcher_name: &'static str,
    pub launcher_comment: &'static str,
    pub manage: &'static str,
    pub portal_pending: &'static str,
    pub desktop_unavailable: &'static str,
    pub locked: &'static str,
    pub asleep: &'static str,
    pub permissions: &'static str,
    pub wayland_permissions: &'static str,
    pub x11_permissions: &'static str,
    pub stopped: &'static str,
    pub start: &'static str,
    pub action: &'static str,
    pub execute: &'static str,
    pub close: &'static str,
    pub gui_missing: &'static str,
}
impl AppStrings {
    pub fn for_chinese(zh: bool) -> Self {
        if zh {
            Self {
                launcher_name: "Termish 服务管理",
                launcher_comment: "管理此电脑的远程访问",
                manage: "管理服务",
                portal_pending: "录屏与控制：等待桌面授权",
                desktop_unavailable: "当前图形桌面不可用",
                locked: "电脑已锁屏",
                asleep: "显示器已休眠",
                permissions: "录屏与远程控制权限",
                wayland_permissions: "连接远程画面时，请在电脑的系统授权窗口中选择屏幕，并允许远程控制。若已取消或权限失效，请重启服务后重新连接。锁屏期间能否采集和输入由当前桌面管理；此服务不修改锁屏安全设置。",
                x11_permissions: "服务需要在当前账号的图形会话中运行，并能访问其 X11 显示和输入接口。请先登录桌面，若仍不可用，请查看服务日志并重启服务。",
                stopped: "服务已停止",
                start: "启动服务",
                action: "操作",
                execute: "执行",
                close: "关闭",
                gui_missing: "无法打开管理窗口，请安装 zenity；也可通过 --control 管理服务",
            }
        } else {
            Self {
                launcher_name: "Termish Helper",
                launcher_comment: "Manage remote access to this computer",
                manage: "Manage service",
                portal_pending: "Recording and control: awaiting desktop consent",
                desktop_unavailable: "Graphical desktop unavailable",
                locked: "Computer is locked",
                asleep: "Display is asleep",
                permissions: "Screen recording and remote control",
                wayland_permissions: "When connecting, select a screen and allow remote control in the computer's system consent dialog. If consent was cancelled or is no longer valid, restart the service and reconnect. Capture and input while locked are controlled by your desktop. This service does not change lock-screen security settings.",
                x11_permissions: "The service must run in your graphical session and have access to its X11 display and input interfaces. Sign into the desktop first. If unavailable, check the service log and restart.",
                stopped: "Service stopped",
                start: "Start service",
                action: "Action",
                execute: "Apply",
                close: "Close",
                gui_missing: "Cannot open the management window. Install zenity, or manage the service with --control",
            }
        }
    }
}
