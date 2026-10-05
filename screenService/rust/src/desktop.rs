//! Native Linux status notifier and desktop management share the service's lifecycle.
mod strings;
use crate::{
    config::{CaptureSource, Config, VERSION},
    desktop_strings::AppStrings,
    management::{Action, Management, Outcome},
    platform::{self, Input},
    service,
};
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs, io,
    path::PathBuf,
    process::{Command, Stdio},
    sync::{
        atomic::{AtomicU32, Ordering},
        Arc,
    },
    thread,
    time::Duration,
};
use zbus::{
    connection::Builder,
    fdo,
    zvariant::{OwnedObjectPath, OwnedValue, Str, Value as Variant},
    Connection, Proxy,
};

const ITEM: &str = "/StatusNotifierItem";
const MENU: &str = "/Menu";
const CONTROL: &str = "/Termish";
const INTERFACE: &str = "dev.termish.ScreenService";
fn name(port: u16) -> String {
    format!("dev.termish.ScreenService.p{port}")
}
fn runtime() -> io::Result<tokio::runtime::Runtime> {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
}
fn chinese() -> bool {
    ["LC_ALL", "LC_MESSAGES", "LANG"]
        .iter()
        .find_map(|k| std::env::var(k).ok().filter(|v| !v.is_empty()))
        .is_some_and(|s| s.starts_with("zh"))
}
fn data_directory() -> PathBuf {
    std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            PathBuf::from(std::env::var_os("HOME").unwrap_or_default()).join(".local/share")
        })
}
fn icon() -> PathBuf {
    data_directory().join("icons/hicolor/scalable/apps/termish-helper.svg")
}
fn write_atomic(path: &std::path::Path, text: &str) -> io::Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    fs::create_dir_all(
        path.parent()
            .ok_or_else(|| io::Error::other("missing parent"))?,
    )?;
    let temporary = path.with_extension(format!("tmp.{}", std::process::id()));
    let result = (|| {
        let mut file = fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o644)
            .open(&temporary)?;
        file.write_all(text.as_bytes())?;
        file.sync_all()?;
        fs::rename(&temporary, path)
    })();
    let _ = fs::remove_file(temporary);
    result
}
fn desktop_quote(path: &str) -> String {
    format!(
        "\"{}\"",
        path.replace('\\', "\\\\\\\\")
            .replace('"', "\\\\\"")
            .replace('`', "\\\\`")
            .replace('$', "\\\\$")
            .replace('%', "%%")
    )
}
pub fn install() -> io::Result<()> {
    let executable = std::env::current_exe()?;
    let path = executable
        .to_str()
        .ok_or_else(|| io::Error::other("non-UTF-8 executable"))?;
    if path.chars().any(char::is_control) || path.contains('=') {
        return Err(io::Error::other(
            "executable path contains control characters",
        ));
    }
    write_atomic(&icon(), include_str!("../../linux/icon.svg"))?;
    let en = strings::AppStrings::for_chinese(false);
    let zh = strings::AppStrings::for_chinese(true);
    write_atomic(
        &data_directory().join("applications/dev.termish.screen.desktop"),
        &format!("[Desktop Entry]\nType=Application\nName={}\nName[zh_CN]={}\nComment={}\nComment[zh_CN]={}\nExec={} --manage\nIcon=termish-helper\nTerminal=false\nCategories=Network;RemoteAccess;\nStartupNotify=true\n",
            en.launcher_name, zh.launcher_name, en.launcher_comment, zh.launcher_comment, desktop_quote(path)),
    )
}

#[derive(Clone)]
struct Item {
    id: i32,
    title: String,
    enabled: bool,
    separator: bool,
}
impl Item {
    fn row(id: i32, title: impl Into<String>, enabled: bool) -> Self {
        Self {
            id,
            title: title.into(),
            enabled,
            separator: false,
        }
    }
    fn separator(id: i32) -> Self {
        Self {
            id,
            title: String::new(),
            enabled: false,
            separator: true,
        }
    }
    fn properties(&self, names: &[String]) -> HashMap<String, OwnedValue> {
        let mut values = HashMap::new();
        values.insert(
            "label".into(),
            OwnedValue::from(Str::from(self.title.clone())),
        );
        values.insert("enabled".into(), self.enabled.into());
        values.insert("visible".into(), true.into());
        if self.separator {
            values.insert("type".into(), OwnedValue::from(Str::from("separator")));
        }
        if !names.is_empty() {
            values.retain(|key, _| names.contains(key));
        }
        values
    }
}
struct Desktop {
    revision: AtomicU32,
    config: Config,
    control: Arc<Management>,
    strings: strings::AppStrings,
    base: AppStrings,
}
impl Desktop {
    fn state(&self) -> &'static str {
        if !self.control.ready.load(Ordering::Acquire) {
            self.base.starting
        } else if self.control.paused.load(Ordering::Acquire) {
            self.base.paused
        } else if self.control.connected.load(Ordering::Acquire) {
            self.base.connected
        } else {
            self.base.idle
        }
    }
    fn items(&self) -> Vec<Item> {
        let ready = self.control.ready.load(Ordering::Acquire);
        let connected = self.control.connected.load(Ordering::Acquire);
        let paused = self.control.paused.load(Ordering::Acquire);
        let diagnostic = self.config.capture_source == CaptureSource::TestPattern;
        let authorized = if platform::wayland::active() {
            platform::wayland::authorized()
        } else {
            Input::default().status() == 0
        };
        let recording = if diagnostic {
            self.base.recording_diagnostic
        } else if platform::wayland::active() && !authorized {
            self.strings.portal_pending
        } else if authorized {
            self.base.recording_ready
        } else {
            self.strings.desktop_unavailable
        };
        let input = if diagnostic {
            self.base.control_diagnostic
        } else if authorized {
            self.base.control_ready
        } else if platform::wayland::active() {
            self.strings.portal_pending
        } else {
            self.strings.desktop_unavailable
        };
        let mut rows = vec![
            Item::row(10, format!("Termish Helper · {VERSION}"), false),
            Item::row(11, self.state(), false),
            Item::separator(20),
            Item::row(2, self.base.disconnect, ready && connected),
            Item::row(
                1,
                if paused {
                    self.base.resume
                } else {
                    self.base.pause
                },
                ready,
            ),
            Item::separator(21),
            Item::row(100, recording, !diagnostic),
            Item::row(101, input, !diagnostic),
        ];
        if !diagnostic {
            let display = platform::display::state();
            if display.locked == Some(true) {
                rows.push(Item::row(12, self.strings.locked, false));
            }
            if display.asleep == Some(true) {
                rows.push(Item::row(13, self.strings.asleep, false));
            }
        }
        rows.extend([
            Item::row(3, self.base.restart, ready),
            Item::separator(22),
            Item::row(102, self.base.logs, true),
            Item::row(103, self.base.directory, true),
            Item::row(104, self.strings.manage, true),
            Item::separator(23),
            Item::row(4, self.base.quit, true),
        ]);
        rows
    }
    fn status(&self) -> Value {
        json!({"version":VERSION,"status":self.state(),"ready":self.control.ready.load(Ordering::Acquire),"connected":self.control.connected.load(Ordering::Acquire),"paused":self.control.paused.load(Ordering::Acquire),"encoder":self.config.encoder,"items":self.items().into_iter().map(|i|json!({"id":i.id,"title":i.title,"enabled":i.enabled,"separator":i.separator})).collect::<Vec<_>>()})
    }
    fn act(&self, id: i32) -> fdo::Result<()> {
        if matches!(id, 5 | 6) && self.control.ready.load(Ordering::Acquire) {
            self.control.request(Action::from_raw(id as u32).unwrap());
            return Ok(());
        }
        if !self
            .items()
            .iter()
            .any(|item| item.id == id && item.enabled)
        {
            return Err(fdo::Error::InvalidArgs("action unavailable".into()));
        }
        if let Some(action) = u32::try_from(id).ok().and_then(Action::from_raw) {
            self.control.request(action);
            return Ok(());
        }
        let mut command = match id {
            100 | 101 => {
                let mut cmd = Command::new("zenity");
                cmd.args([
                    "--info",
                    "--no-markup",
                    "--title",
                    self.strings.permissions,
                    "--text",
                    if platform::wayland::active() {
                        self.strings.wayland_permissions
                    } else {
                        self.strings.x11_permissions
                    },
                ]);
                cmd
            }
            102 | 103 => {
                let mut cmd = Command::new("xdg-open");
                cmd.arg(if id == 102 {
                    self.config.log_file.clone()
                } else {
                    crate::config::state_directory(
                        &std::env::current_exe().map_err(|e| fdo::Error::Failed(e.to_string()))?,
                    )
                });
                cmd
            }
            104 => {
                let mut cmd = Command::new(
                    std::env::current_exe().map_err(|e| fdo::Error::Failed(e.to_string()))?,
                );
                cmd.args(std::env::args_os().skip(1)).arg("--manage");
                cmd
            }
            _ => return Err(fdo::Error::InvalidArgs("unknown action".into())),
        };
        let mut child = command
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| fdo::Error::Failed(e.to_string()))?;
        thread::spawn(move || {
            let _ = child.wait();
        });
        Ok(())
    }
}
struct LocalControl(Arc<Desktop>);
#[zbus::interface(name = "dev.termish.ScreenService")]
impl LocalControl {
    fn status(&self, chinese: bool) -> String {
        // A management window may be opened with a different locale than the
        // service's SSH/autostart environment. Localize the same authoritative state.
        let localized = Desktop {
            revision: AtomicU32::new(self.0.revision.load(Ordering::Acquire)),
            config: self.0.config.clone(),
            control: self.0.control.clone(),
            base: AppStrings::for_chinese(chinese),
            strings: strings::AppStrings::for_chinese(chinese),
        };
        localized.status().to_string()
    }
    fn action(&self, id: i32) -> fdo::Result<()> {
        self.0.act(id)
    }
}
type Pixmaps = Vec<(i32, i32, Vec<u8>)>;
struct Notifier(Arc<Desktop>);
#[zbus::interface(name = "org.kde.StatusNotifierItem")]
impl Notifier {
    #[zbus(property)]
    fn category(&self) -> &str {
        "SystemServices"
    }
    #[zbus(property)]
    fn id(&self) -> &str {
        "termish-helper"
    }
    #[zbus(property)]
    fn title(&self) -> &str {
        "Termish Helper"
    }
    #[zbus(property)]
    fn status(&self) -> &str {
        if self.0.control.paused.load(Ordering::Acquire) {
            "NeedsAttention"
        } else {
            "Active"
        }
    }
    #[zbus(property)]
    fn window_id(&self) -> u32 {
        0
    }
    #[zbus(property)]
    fn icon_name(&self) -> String {
        icon().to_string_lossy().into_owned()
    }
    #[zbus(property)]
    fn icon_theme_path(&self) -> String {
        icon().parent().unwrap().to_string_lossy().into_owned()
    }
    #[zbus(property)]
    fn icon_pixmap(&self) -> Pixmaps {
        Vec::new()
    }
    #[zbus(property)]
    fn overlay_icon_name(&self) -> &str {
        ""
    }
    #[zbus(property)]
    fn overlay_icon_pixmap(&self) -> Pixmaps {
        Vec::new()
    }
    #[zbus(property)]
    fn attention_icon_name(&self) -> String {
        self.icon_name()
    }
    #[zbus(property)]
    fn attention_icon_pixmap(&self) -> Pixmaps {
        Vec::new()
    }
    #[zbus(property)]
    fn attention_movie_name(&self) -> &str {
        ""
    }
    #[zbus(property, name = "ToolTip")]
    fn tool_tip(&self) -> (String, Pixmaps, String, String) {
        (
            self.icon_name(),
            Vec::new(),
            "Termish Helper".into(),
            self.0.state().into(),
        )
    }
    #[zbus(property)]
    fn item_is_menu(&self) -> bool {
        true
    }
    #[zbus(property)]
    fn menu(&self) -> OwnedObjectPath {
        OwnedObjectPath::try_from(MENU).unwrap()
    }
    fn activate(&self, _x: i32, _y: i32) -> fdo::Result<()> {
        self.0.act(104)
    }
    fn secondary_activate(&self, _x: i32, _y: i32) -> fdo::Result<()> {
        self.0.act(104)
    }
    fn context_menu(&self, _x: i32, _y: i32) -> fdo::Result<()> {
        self.0.act(104)
    }
    fn scroll(&self, _delta: i32, _orientation: &str) {}
}
type Layout = (i32, HashMap<String, OwnedValue>, Vec<OwnedValue>);
struct DbusMenu(Arc<Desktop>);
impl DbusMenu {
    fn properties(&self, id: i32, names: &[String]) -> fdo::Result<HashMap<String, OwnedValue>> {
        if id == 0 {
            let mut p = HashMap::new();
            if names.is_empty() || names.iter().any(|n| n == "children-display") {
                p.insert(
                    "children-display".into(),
                    OwnedValue::from(Str::from("submenu")),
                );
            }
            return Ok(p);
        }
        self.0
            .items()
            .into_iter()
            .find(|i| i.id == id)
            .map(|i| i.properties(names))
            .ok_or_else(|| fdo::Error::InvalidArgs("unknown menu item".into()))
    }
}
#[zbus::interface(name = "com.canonical.dbusmenu")]
impl DbusMenu {
    #[zbus(property)]
    fn version(&self) -> u32 {
        3
    }
    #[zbus(property)]
    fn text_direction(&self) -> &str {
        "ltr"
    }
    #[zbus(property)]
    fn status(&self) -> &str {
        "normal"
    }
    #[zbus(property)]
    fn icon_theme_path(&self) -> Vec<String> {
        vec![icon().parent().unwrap().to_string_lossy().into_owned()]
    }
    fn get_layout(
        &self,
        parent_id: i32,
        recursion_depth: i32,
        property_names: Vec<String>,
    ) -> fdo::Result<(u32, Layout)> {
        let children = if parent_id == 0 && recursion_depth != 0 {
            self.0
                .items()
                .into_iter()
                .map(|i| {
                    OwnedValue::try_from(Variant::new((
                        i.id,
                        i.properties(&property_names),
                        Vec::<OwnedValue>::new(),
                    )))
                    .map_err(|e| fdo::Error::Failed(e.to_string()))
                })
                .collect::<fdo::Result<Vec<_>>>()?
        } else {
            Vec::new()
        };
        Ok((
            self.0.revision.load(Ordering::Acquire),
            (
                parent_id,
                self.properties(parent_id, &property_names)?,
                children,
            ),
        ))
    }
    fn get_group_properties(
        &self,
        ids: Vec<i32>,
        property_names: Vec<String>,
    ) -> fdo::Result<Vec<(i32, HashMap<String, OwnedValue>)>> {
        let ids = if ids.is_empty() {
            self.0.items().iter().map(|i| i.id).collect()
        } else {
            ids
        };
        ids.into_iter()
            .map(|id| Ok((id, self.properties(id, &property_names)?)))
            .collect()
    }
    fn get_property(&self, id: i32, name: &str) -> fdo::Result<OwnedValue> {
        self.properties(id, &[])?
            .remove(name)
            .ok_or_else(|| fdo::Error::InvalidArgs("unknown menu property".into()))
    }
    fn event(
        &self,
        id: i32,
        event_id: &str,
        _data: OwnedValue,
        _timestamp: u32,
    ) -> fdo::Result<()> {
        if event_id == "clicked" {
            self.0.act(id)
        } else {
            Ok(())
        }
    }
    fn event_group(&self, events: Vec<(i32, String, OwnedValue, u32)>) -> Vec<i32> {
        events
            .into_iter()
            .filter_map(|(id, event, data, t)| self.event(id, &event, data, t).err().map(|_| id))
            .collect()
    }
    fn about_to_show(&self, id: i32) -> fdo::Result<bool> {
        self.properties(id, &[])?;
        Ok(true)
    }
    fn about_to_show_group(&self, ids: Vec<i32>) -> (Vec<i32>, Vec<i32>) {
        ids.into_iter()
            .partition(|id| self.properties(*id, &[]).is_ok())
    }
}

async fn connection(desktop: Arc<Desktop>) -> zbus::Result<Connection> {
    let connection = Builder::session()?
        .name(name(desktop.config.port))?
        .serve_at(CONTROL, LocalControl(desktop.clone()))?
        .serve_at(ITEM, Notifier(desktop.clone()))?
        .serve_at(MENU, DbusMenu(desktop))?
        .build()
        .await?;
    Ok(connection)
}
async fn register(connection: &Connection) -> zbus::Result<()> {
    let proxy = Proxy::new(
        connection,
        "org.kde.StatusNotifierWatcher",
        "/StatusNotifierWatcher",
        "org.kde.StatusNotifierWatcher",
    )
    .await?;
    proxy
        .call::<_, _, ()>(
            "RegisterStatusNotifierItem",
            &(connection.unique_name().unwrap().as_str(),),
        )
        .await
}
pub fn run(config: Config, control: Arc<Management>) -> io::Result<Outcome> {
    let desktop = Arc::new(Desktop {
        revision: AtomicU32::new(1),
        config: config.clone(),
        control: control.clone(),
        strings: strings::AppStrings::for_chinese(chinese()),
        base: AppStrings::for_chinese(chinese()),
    });
    if config.menu_bar {
        if let Err(e) = write_atomic(&icon(), include_str!("../../linux/icon.svg")) {
            service::log(&config, &format!("tray icon unavailable: {e}"));
        }
    }
    let worker = thread::spawn(move || service::run(config, control));
    let frontend = (|| -> io::Result<()> {
        runtime()?.block_on(async {
            let connection =
                tokio::time::timeout(Duration::from_secs(3), connection(desktop.clone()))
                    .await
                    .map_err(|e| io::Error::other(e.to_string()))?
                    .map_err(|e| io::Error::other(e.to_string()))?;
            let mut previous = Value::Null;
            let mut ticks = 0;
            while !worker.is_finished() {
                if desktop.config.menu_bar {
                    if ticks % 5 == 0 {
                        let _ = tokio::time::timeout(Duration::from_secs(1), register(&connection))
                            .await;
                    }
                    // Compare structured values; randomized map iteration must not
                    // trigger repeated menu rebuilds while a user clicks an item.
                    let status = desktop.status();
                    if status != previous {
                        previous = status;
                        let revision = desktop
                            .revision
                            .fetch_add(1, Ordering::AcqRel)
                            .wrapping_add(1);
                        let _ = connection
                            .emit_signal(
                                None::<&str>,
                                MENU,
                                "com.canonical.dbusmenu",
                                "LayoutUpdated",
                                &(revision, 0i32),
                            )
                            .await;
                        let _ = connection
                            .emit_signal(
                                None::<&str>,
                                ITEM,
                                "org.kde.StatusNotifierItem",
                                "NewToolTip",
                                &(),
                            )
                            .await;
                        let _ = connection
                            .emit_signal(
                                None::<&str>,
                                ITEM,
                                "org.kde.StatusNotifierItem",
                                "NewStatus",
                                &(if desktop.control.paused.load(Ordering::Acquire) {
                                    "NeedsAttention"
                                } else {
                                    "Active"
                                },),
                            )
                            .await;
                    }
                }
                ticks += 1;
                tokio::time::sleep(Duration::from_secs(1)).await;
            }
            Ok(())
        })
    })();
    if let Err(e) = frontend {
        service::log(
            &desktop.config,
            &format!("desktop management unavailable; remote service continues: {e}"),
        );
    }
    worker
        .join()
        .unwrap_or_else(|_| Err(io::Error::other("screen service thread panicked")))
}

async fn remote(connection: &Connection, port: u16, action: Option<i32>) -> zbus::Result<String> {
    let proxy = Proxy::new(connection, name(port), CONTROL, INTERFACE).await?;
    if let Some(id) = action {
        proxy.call::<_, _, ()>("Action", &(id,)).await?;
        Ok(String::new())
    } else {
        proxy.call("Status", &(chinese(),)).await
    }
}
pub fn control(config: &Config, action: Option<i32>) -> io::Result<String> {
    runtime()?
        .block_on(async {
            tokio::time::timeout(Duration::from_secs(3), async {
                let connection = Connection::session().await?;
                remote(&connection, config.port, action).await
            })
            .await
            .map_err(|e| zbus::Error::Failure(e.to_string()))?
        })
        .map_err(|e| io::Error::other(e.to_string()))
}
pub fn manage(config: &Config) -> io::Result<()> {
    let strings = strings::AppStrings::for_chinese(chinese());
    let base = AppStrings::for_chinese(chinese());
    loop {
        let state = control(config, None)
            .ok()
            .and_then(|s| serde_json::from_str::<Value>(&s).ok());
        let mut dialog = Command::new("zenity");
        let summary = state
            .as_ref()
            .map(|s| {
                s["items"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter(|row| matches!(row["id"].as_i64(), Some(11 | 12 | 13 | 100 | 101)))
                    .filter_map(|row| row["title"].as_str())
                    .collect::<Vec<_>>()
                    .join("\n")
            })
            .unwrap_or_else(|| strings.stopped.to_owned());
        dialog.args([
            "--list",
            "--no-markup",
            "--title",
            "Termish Helper",
            "--text",
            &summary,
            "--column",
            "ID",
            "--column",
            strings.action,
            "--hide-column",
            "1",
            "--print-column",
            "1",
            "--width",
            "460",
            "--height",
            "460",
            "--ok-label",
            strings.execute,
            "--cancel-label",
            strings.close,
        ]);
        if let Some(state) = state {
            if let Some(rows) = state["items"].as_array() {
                for row in rows
                    .iter()
                    .filter(|r| r["enabled"] == true && r["id"] != 104)
                {
                    dialog
                        .arg(row["id"].to_string())
                        .arg(row["title"].as_str().unwrap_or_default());
                }
            }
        } else {
            dialog.args([
                "200",
                strings.start,
                "102",
                base.logs,
                "103",
                base.directory,
            ]);
        }
        let output = dialog
            .output()
            .map_err(|e| io::Error::other(format!("{}: {e}", strings.gui_missing)))?;
        if !output.status.success() {
            return Ok(());
        }
        let Ok(id) = String::from_utf8_lossy(&output.stdout)
            .trim()
            .parse::<i32>()
        else {
            continue;
        };
        if id == 200 {
            let installed_default = config.port == env!("SCREEN_PORT").parse::<u16>().unwrap()
                && std::env::current_exe()?
                    .file_name()
                    .is_some_and(|n| n == "screen-service")
                && !std::env::args()
                    .any(|a| matches!(a.as_str(), "--config" | "--port" | "--ffmpeg"));
            let started = installed_default
                && crate::process::output(
                    Command::new("systemctl").args([
                        "--user",
                        "start",
                        "dev.termish.screen.service",
                    ]),
                    Duration::from_secs(3),
                    None,
                )
                .is_ok_and(|r| r.0);
            if !started {
                let mut child = Command::new(std::env::current_exe()?)
                    .args(std::env::args_os().skip(1).filter(|a| a != "--manage"))
                    .env_remove("TERMISH_SYSTEMD_SERVICE")
                    .stdin(Stdio::null())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .spawn()?;
                thread::spawn(move || {
                    let _ = child.wait();
                });
            }
            thread::sleep(Duration::from_millis(300));
        } else if id == 102 || id == 103 {
            let mut child = Command::new("xdg-open")
                .arg(if id == 102 {
                    config.log_file.clone()
                } else {
                    crate::config::state_directory(&std::env::current_exe()?)
                })
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()?;
            thread::spawn(move || {
                let _ = child.wait();
            });
        } else if let Err(e) = control(config, Some(id)) {
            Command::new("zenity")
                .args([
                    "--error",
                    "--no-markup",
                    "--title",
                    "Termish Helper",
                    "--text",
                    &e.to_string(),
                ])
                .status()?;
        }
        if id == 4 {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(100));
    }
}
