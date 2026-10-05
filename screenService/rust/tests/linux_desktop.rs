#![cfg(target_os = "linux")]
use serde_json::{json, Value};
use std::{
    collections::HashMap,
    fs,
    io::{BufRead, BufReader, Read, Write},
    net::{TcpListener, TcpStream},
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::{Arc, Mutex},
    time::{Duration, Instant},
};
use zbus::{connection::Builder, zvariant::OwnedValue, Proxy};

struct Fixture {
    directory: PathBuf,
    address: String,
    bus: Child,
    service: Child,
    port: u16,
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = self.service.kill();
        let _ = self.service.wait();
        let _ = self.bus.kill();
        let _ = self.bus.wait();
        let _ = fs::remove_dir_all(&self.directory);
    }
}
impl Fixture {
    fn start(managed: bool) -> Self {
        let port = loop {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            let port = listener.local_addr().unwrap().port();
            if port < 65533 && TcpListener::bind(("127.0.0.1", port + 2)).is_ok() {
                break port;
            }
        };
        let directory =
            std::env::temp_dir().join(format!("termish-desktop-{}-{port}", std::process::id()));
        fs::create_dir_all(&directory).unwrap();
        fs::write(directory.join("token"), "a".repeat(64)).unwrap();
        fs::write(
            directory.join("config.json"),
            json!({
                "port": port, "capture_source":"test_pattern", "menu_bar":true,
                "ffmpeg":"/usr/bin/ffmpeg", "token_file":directory.join("token"),
                "log_file":directory.join("log"), "encoder_pid_file":directory.join("encoder.pid"),
                "stream_config_file":directory.join("settings")
            })
            .to_string(),
        )
        .unwrap();
        fs::write(directory.join("settings"), "fps=15\nscale=640:-2\n").unwrap();
        let mut bus = Command::new("dbus-daemon")
            .args(["--session", "--nofork", "--print-address=1"])
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap();
        let mut address = String::new();
        BufReader::new(bus.stdout.take().unwrap())
            .read_line(&mut address)
            .unwrap();
        let address = address.trim().to_owned();
        let service = Self::command_at(&directory, &address)
            .env("TERMISH_SYSTEMD_SERVICE", if managed { "1" } else { "0" })
            .stdout(Stdio::null())
            .stderr(fs::File::create(directory.join("stderr")).unwrap())
            .spawn()
            .unwrap();
        Self {
            directory,
            address,
            bus,
            service,
            port,
        }
    }
    fn command_at(directory: &std::path::Path, address: &str) -> Command {
        let mut command = Command::new(env!("CARGO_BIN_EXE_termish-screen-service"));
        command
            .arg("--config")
            .arg(directory.join("config.json"))
            .env("HOME", directory)
            .env("XDG_DATA_HOME", directory.join("data"))
            .env("DBUS_SESSION_BUS_ADDRESS", address)
            .env("LANG", "en_US.UTF-8")
            .env_remove("DISPLAY")
            .env_remove("WAYLAND_DISPLAY")
            .env_remove("XDG_SESSION_TYPE");
        command
    }
    fn command(&self) -> Command {
        Self::command_at(&self.directory, &self.address)
    }
    async fn status(&self) -> Value {
        let end = Instant::now() + Duration::from_secs(6);
        loop {
            let output = self
                .command()
                .args(["--control", "status"])
                .output()
                .unwrap();
            if output.status.success() {
                let state: Value = serde_json::from_slice(&output.stdout).unwrap();
                if state["ready"] == true {
                    return state;
                }
            }
            assert!(
                Instant::now() < end,
                "service not ready: {}",
                fs::read_to_string(self.directory.join("stderr")).unwrap()
            );
            tokio::time::sleep(Duration::from_millis(30)).await;
        }
    }
    async fn wait_state(&self, key: &str, value: bool) {
        let end = Instant::now() + Duration::from_secs(5);
        while self.status().await[key] != value {
            assert!(Instant::now() < end, "{key} did not become {value}");
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    }
    fn action(&self, action: &str) {
        let output = self.command().args(["--control", action]).output().unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
    }
    fn auth(&self) -> TcpStream {
        let mut socket = TcpStream::connect(("127.0.0.1", self.port + 2)).unwrap();
        socket
            .set_read_timeout(Some(Duration::from_secs(3)))
            .unwrap();
        let mut auth = b"THA1".to_vec();
        auth.extend([b'a'; 64]);
        socket
            .write_all(&(auth.len() as u32).to_be_bytes())
            .unwrap();
        socket.write_all(&auth).unwrap();
        socket
    }
    fn exited(&mut self, expected: i32) {
        let end = Instant::now() + Duration::from_secs(5);
        loop {
            if let Some(status) = self.service.try_wait().unwrap() {
                assert_eq!(status.code(), Some(expected));
                return;
            }
            assert!(Instant::now() < end, "service failed to exit");
            std::thread::sleep(Duration::from_millis(20));
        }
    }
}
struct Watcher(Arc<Mutex<Vec<String>>>);
#[zbus::interface(name = "org.kde.StatusNotifierWatcher")]
impl Watcher {
    fn register_status_notifier_item(&self, service: &str) {
        self.0.lock().unwrap().push(service.to_owned());
    }
}
type Layout = (i32, HashMap<String, OwnedValue>, Vec<OwnedValue>);

struct Window(Child);
impl Drop for Window {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

#[test]
#[ignore = "Requires a disposable Xvfb display, zenity, xdotool and FFmpeg"]
fn management_window_opens_without_a_tray_host_and_closes_without_stopping_the_service() {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let mut fixture = Fixture::start(false);
            fixture.status().await;
            let translated = fixture
                .command()
                .env("LC_ALL", "zh_CN.UTF-8")
                .args(["--control", "status"])
                .output()
                .unwrap();
            assert!(translated.status.success());
            assert!(String::from_utf8(translated.stdout)
                .unwrap()
                .contains("暂停远程访问"));
            let display = std::env::var("DISPLAY").expect("isolated display required");
            for language in ["en_US.UTF-8", "zh_CN.UTF-8"] {
                let mut window = Window(
                    fixture
                        .command()
                        .arg("--manage")
                        .env("DISPLAY", &display)
                        .env("LANG", language)
                        .stdout(Stdio::null())
                        .stderr(fs::File::create(fixture.directory.join("gui-stderr")).unwrap())
                        .spawn()
                        .unwrap(),
                );
                let end = Instant::now() + Duration::from_secs(5);
                let id = loop {
                    let found = Command::new("xdotool")
                        .args(["search", "--onlyvisible", "--name", "^Termish Helper$"])
                        .output()
                        .unwrap();
                    if found.status.success() {
                        break String::from_utf8(found.stdout)
                            .unwrap()
                            .lines()
                            .next()
                            .unwrap()
                            .to_owned();
                    }
                    assert!(
                        Instant::now() < end && window.0.try_wait().unwrap().is_none(),
                        "management window failed to open for {language}: {}",
                        fs::read_to_string(fixture.directory.join("gui-stderr")).unwrap()
                    );
                    tokio::time::sleep(Duration::from_millis(30)).await;
                };
                // Exportable visual evidence belongs outside the project, if requested.
                if let Some(directory) = std::env::var_os("TERMISH_UI_SCREENSHOT_DIR") {
                    let path = PathBuf::from(directory).join(if language.starts_with("zh") {
                        "manage-zh.png"
                    } else {
                        "manage-en.png"
                    });
                    tokio::time::sleep(Duration::from_millis(200)).await;
                    assert!(Command::new("ffmpeg")
                        .args([
                            "-hide_banner",
                            "-loglevel",
                            "error",
                            "-f",
                            "x11grab",
                            "-i",
                            &display,
                            "-frames:v",
                            "1",
                            "-threads",
                            "1",
                            "-y"
                        ])
                        .arg(path)
                        .status()
                        .unwrap()
                        .success());
                }
                assert!(Command::new("xdotool")
                    .args(["windowfocus", "--sync", &id, "key", "Escape"])
                    .status()
                    .unwrap()
                    .success());
                let end = Instant::now() + Duration::from_secs(3);
                while window.0.try_wait().unwrap().is_none() {
                    assert!(Instant::now() < end, "close failed for {language}");
                    tokio::time::sleep(Duration::from_millis(30)).await;
                }
                assert_eq!(fixture.status().await["paused"], false);
            }
            fixture.action("quit");
            fixture.exited(0);
        });
}

#[test]
#[ignore = "Requires dbus-daemon and FFmpeg; uses a private session bus, never the real desktop"]
fn tray_wire_protocol_and_lifecycle_controls_work_without_a_tray_host() {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let mut fixture = Fixture::start(false);
            fixture.status().await;
            let installed = fixture.command().arg("--install-desktop").output().unwrap();
            assert!(installed.status.success());
            let launcher = fixture
                .directory
                .join("data/applications/dev.termish.screen.desktop");
            assert!(Command::new("desktop-file-validate")
                .arg(&launcher)
                .status()
                .unwrap()
                .success());
            assert!(fixture
                .directory
                .join("data/icons/hicolor/scalable/apps/termish-helper.svg")
                .exists());
            // The watcher starts after the service, just as a shell/extension may start late.
            let registrations = Arc::new(Mutex::new(Vec::new()));
            let watcher = Builder::address(fixture.address.as_str())
                .unwrap()
                .name("org.kde.StatusNotifierWatcher")
                .unwrap()
                .serve_at("/StatusNotifierWatcher", Watcher(registrations.clone()))
                .unwrap()
                .build()
                .await
                .unwrap();
            let connection = Builder::address(fixture.address.as_str())
                .unwrap()
                .build()
                .await
                .unwrap();
            let end = Instant::now() + Duration::from_secs(7);
            while registrations.lock().unwrap().is_empty() {
                assert!(Instant::now() < end, "late tray host was not discovered");
                tokio::time::sleep(Duration::from_millis(30)).await;
            }
            let name = format!("dev.termish.ScreenService.p{}", fixture.port);
            let notifier = Proxy::new(
                &connection,
                name.as_str(),
                "/StatusNotifierItem",
                "org.kde.StatusNotifierItem",
            )
            .await
            .unwrap();
            assert_eq!(
                notifier.get_property::<String>("Title").await.unwrap(),
                "Termish Helper"
            );
            assert!(notifier.get_property::<bool>("ItemIsMenu").await.unwrap());
            let menu = Proxy::new(
                &connection,
                name.as_str(),
                "/Menu",
                "com.canonical.dbusmenu",
            )
            .await
            .unwrap();
            let (revision, layout): (u32, Layout) = menu
                .call("GetLayout", &(0i32, -1i32, Vec::<String>::new()))
                .await
                .unwrap();
            assert_eq!(layout.0, 0);
            assert!(layout.2.len() > 10);
            // Read-only status and diagnostic permission rows reject injected click events.
            assert!(menu
                .call::<_, _, ()>("Event", &(100i32, "clicked", OwnedValue::from(0i32), 0u32))
                .await
                .is_err());
            let _: () = menu
                .call("Event", &(1i32, "clicked", OwnedValue::from(0i32), 0u32))
                .await
                .unwrap();
            fixture.wait_state("paused", true).await;
            fixture.action("pause");
            fixture.wait_state("paused", true).await;
            let mut refused = fixture.auth();
            assert_eq!(refused.read(&mut [0; 5]).unwrap_or_default(), 0);
            assert!(!fixture.directory.join("encoder.pid").exists());
            tokio::time::sleep(Duration::from_millis(1200)).await;
            let (next, _): (u32, Layout) = menu
                .call("GetLayout", &(0i32, -1i32, Vec::<String>::new()))
                .await
                .unwrap();
            assert!(next > revision, "menu revision did not change after pause");
            fixture.action("resume");
            fixture.action("resume");
            fixture.wait_state("paused", false).await;
            let mut stream = fixture.auth();
            let mut status = [0; 5];
            stream.read_exact(&mut status).unwrap();
            assert_eq!(&status, b"THS1\x02");
            let mut head = [0; 4];
            stream.read_exact(&mut head).unwrap();
            let mut frame = vec![0; u32::from_be_bytes(head) as usize];
            stream.read_exact(&mut frame).unwrap();
            assert_eq!(&frame[..4], b"THV2");
            fixture.wait_state("connected", true).await;
            let pid: i32 = fs::read_to_string(fixture.directory.join("encoder.pid"))
                .unwrap()
                .trim()
                .parse()
                .unwrap();
            fixture.action("disconnect");
            fixture.wait_state("connected", false).await;
            let end = Instant::now() + Duration::from_secs(3);
            while unsafe { libc::kill(pid, 0) } == 0 {
                assert!(Instant::now() < end, "disconnect leaked encoder");
                tokio::time::sleep(Duration::from_millis(20)).await;
            }
            // A restarted watcher also receives registration; old hosts cannot strand the icon.
            drop(watcher);
            let restarted = Arc::new(Mutex::new(Vec::new()));
            let _watcher = Builder::address(fixture.address.as_str())
                .unwrap()
                .name("org.kde.StatusNotifierWatcher")
                .unwrap()
                .serve_at("/StatusNotifierWatcher", Watcher(restarted.clone()))
                .unwrap()
                .build()
                .await
                .unwrap();
            let end = Instant::now() + Duration::from_secs(7);
            while restarted.lock().unwrap().is_empty() {
                assert!(
                    Instant::now() < end,
                    "restarted tray host was not discovered"
                );
                tokio::time::sleep(Duration::from_millis(30)).await;
            }
            fixture.action("quit");
            fixture.exited(0);
            assert!(!fixture.directory.join("encoder.pid").exists());
        });
}

#[test]
#[ignore = "Requires dbus-daemon and FFmpeg; uses a private session bus"]
fn systemd_restart_exits_unsuccessfully_and_quit_exits_successfully() {
    tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .unwrap()
        .block_on(async {
            let mut fixture = Fixture::start(true);
            fixture.status().await;
            fixture.action("restart");
            fixture.exited(1);
            // A systemd-managed process leaves respawning to its supervisor.
            assert!(TcpStream::connect(("127.0.0.1", fixture.port)).is_err());
            fixture.service = fixture
                .command()
                .env("TERMISH_SYSTEMD_SERVICE", "1")
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn()
                .unwrap();
            fixture.status().await;
            fixture.action("quit");
            fixture.exited(0);
        });
}
