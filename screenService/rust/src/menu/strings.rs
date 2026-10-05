//! Native desktop strings follow the same bilingual AppStrings convention as the phone.
pub struct AppStrings {
    pub starting: &'static str,
    pub idle: &'static str,
    pub connected: &'static str,
    pub paused: &'static str,
    pub pause: &'static str,
    pub resume: &'static str,
    pub disconnect: &'static str,
    pub recording_ready: &'static str,
    pub recording_missing: &'static str,
    pub control_ready: &'static str,
    pub control_missing: &'static str,
    pub recording_diagnostic: &'static str,
    pub control_diagnostic: &'static str,
    pub restart: &'static str,
    pub logs: &'static str,
    pub directory: &'static str,
    pub quit: &'static str,
}
impl AppStrings {
    pub fn for_chinese(chinese: bool) -> Self {
        if chinese {
            Self {
                starting: "服务启动中",
                idle: "等待远程连接",
                connected: "远程画面已连接",
                paused: "远程访问已暂停",
                pause: "暂停远程访问",
                resume: "恢复远程访问",
                disconnect: "断开当前连接",
                recording_ready: "录屏权限：已授权",
                recording_missing: "录屏权限：未生效（打开设置）",
                control_ready: "控制权限：已授权",
                control_missing: "控制权限：未生效（打开设置）",
                recording_diagnostic: "录屏：诊断模式",
                control_diagnostic: "控制：诊断模式",
                restart: "重启服务／刷新权限",
                logs: "查看服务日志",
                directory: "打开服务目录",
                quit: "退出 Termish Helper",
            }
        } else {
            Self {
                starting: "Starting service",
                idle: "Waiting for a connection",
                connected: "Remote connection active",
                paused: "Remote access paused",
                pause: "Pause remote access",
                resume: "Resume remote access",
                disconnect: "Disconnect current session",
                recording_ready: "Screen recording: authorized",
                recording_missing: "Screen recording: unavailable (open settings)",
                control_ready: "Control: authorized",
                control_missing: "Control: unavailable (open settings)",
                recording_diagnostic: "Screen recording: diagnostic mode",
                control_diagnostic: "Control: diagnostic mode",
                restart: "Restart service / refresh permissions",
                logs: "View service log",
                directory: "Open service folder",
                quit: "Quit Termish Helper",
            }
        }
    }
}
