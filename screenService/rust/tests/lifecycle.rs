use serde_json::{json, Value};
use std::{
    fs,
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    os::unix::fs::PermissionsExt,
    path::PathBuf,
    process::{Child, Command, Stdio},
    sync::{Mutex, MutexGuard},
    time::{Duration, Instant},
};
struct Service {
    dir: PathBuf,
    child: Child,
    port: u16,
    _guard: MutexGuard<'static, ()>,
}
static SERVICE_LOCK: Mutex<()> = Mutex::new(());
impl Drop for Service {
    fn drop(&mut self) {
        unsafe {
            libc::kill(self.child.id() as i32, libc::SIGTERM);
        }
        let end = Instant::now() + Duration::from_secs(3);
        while matches!(self.child.try_wait(), Ok(None)) && Instant::now() < end {
            std::thread::sleep(Duration::from_millis(20));
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = fs::remove_dir_all(&self.dir);
    }
}
impl Service {
    fn start() -> Option<Self> {
        Self::start_attempt(0)
    }
    fn start_attempt(attempt: u8) -> Option<Self> {
        // Reserve one port pair at a time: another fixture's ephemeral probe port
        // must not consume this fixture's adjacent video port before it binds.
        let guard = SERVICE_LOCK
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let ff = Command::new("sh")
            .args(["-c", "command -v ffmpeg"])
            .output()
            .ok()?;
        if !ff.status.success() {
            eprintln!("SKIP ffmpeg missing");
            return None;
        }
        let ff = String::from_utf8(ff.stdout).ok()?.trim().to_owned();
        let port = loop {
            let l = TcpListener::bind("127.0.0.1:0").unwrap();
            let port = l.local_addr().unwrap().port();
            if port < 65533 && TcpListener::bind(("127.0.0.1", port + 2)).is_ok() {
                break port;
            }
        };
        let dir = std::env::temp_dir().join(format!("ts-{}-{}", std::process::id(), port));
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("token"), "a".repeat(64)).unwrap();
        let wrapper = dir.join("ffmpeg");
        fs::write(
            &wrapper,
            format!(
                "#!/bin/sh\necho $$ > '{}'\nexec '{}' \"$@\"\n",
                dir.join("started").display(),
                ff
            ),
        )
        .unwrap();
        fs::set_permissions(&wrapper, fs::Permissions::from_mode(0o700)).unwrap();
        fs::write(dir.join("config.json"),json!({"capture_source":"test_pattern","port":port,"ffmpeg":wrapper,"token_file":dir.join("token"),"log_file":dir.join("log"),"encoder_pid_file":dir.join("encoder.pid"),"stream_config_file":dir.join("settings")}).to_string()).unwrap();
        let child = Command::new(env!("CARGO_BIN_EXE_termish-screen-service"))
            .args(["--config"])
            .arg(dir.join("config.json"))
            .env_remove("XDG_SESSION_TYPE")
            .stdout(Stdio::null())
            .stderr(Stdio::from(fs::File::create(dir.join("stderr")).unwrap()))
            .spawn()
            .unwrap();
        let mut service = Self {
            dir,
            child,
            port,
            _guard: guard,
        };
        let end = Instant::now() + Duration::from_secs(5);
        // The OS can assign this temporarily released pair to an unrelated
        // concurrent test. Wait for our daemon's own post-bind ready record,
        // not just a port that another process may already be listening on.
        while !fs::read_to_string(service.dir.join("log"))
            .unwrap_or_default()
            .contains("service started version=")
        {
            if let Some(status) = service.child.try_wait().unwrap() {
                let error = fs::read_to_string(service.dir.join("stderr")).unwrap();
                drop(service);
                if error.contains("Address already in use") && attempt < 4 {
                    return Self::start_attempt(attempt + 1);
                }
                panic!("fixture exited {status}: {error}");
            }
            assert!(Instant::now() < end);
            std::thread::sleep(Duration::from_millis(20));
        }
        Some(service)
    }
    fn connect(&self) -> TcpStream {
        let end = Instant::now() + Duration::from_secs(5);
        let s = loop {
            match TcpStream::connect(("127.0.0.1", self.port + 2)) {
                Ok(socket) => break socket,
                Err(error) => {
                    assert!(Instant::now() < end, "video listener not ready: {error}");
                    std::thread::sleep(Duration::from_millis(20));
                }
            }
        };
        s.set_read_timeout(Some(Duration::from_secs(4))).unwrap();
        s.set_write_timeout(Some(Duration::from_secs(4))).unwrap();
        s
    }
    fn auth(&self) -> TcpStream {
        let mut s = self.connect();
        let mut token = b"THA1".to_vec();
        token.extend(vec![b'a'; 64]);
        packet(&mut s, &token);
        let mut status = [0; 5];
        s.read_exact(&mut status).unwrap();
        assert_eq!(&status[..4], b"THS1");
        s
    }
}
fn packet(s: &mut TcpStream, data: &[u8]) {
    s.write_all(&(data.len() as u32).to_be_bytes()).unwrap();
    s.write_all(data).unwrap();
}
fn read_packet(s: &mut TcpStream) -> Vec<u8> {
    let mut head = [0; 4];
    s.read_exact(&mut head).unwrap();
    let size = u32::from_be_bytes(head);
    assert!(size < 4 * 1024 * 1024);
    let mut data = vec![0; size as usize];
    s.read_exact(&mut data).unwrap();
    data
}
fn heartbeat(s: &mut TcpStream) {
    let mut body = b"THC1".to_vec();
    body.push(13);
    body.extend([0; 12]);
    packet(s, &body);
}
#[test]
fn authentication_precedes_encoder_start_and_healthy_owner_is_busy() {
    let Some(service) = Service::start() else {
        return;
    };
    let mut bad = service.connect();
    let mut auth = b"THA1".to_vec();
    auth.extend(vec![b'b'; 64]);
    packet(&mut bad, &auth);
    let mut byte = [0];
    assert_eq!(bad.read(&mut byte).unwrap_or(0), 0);
    assert!(!service.dir.join("started").exists());
    let mut owner = service.auth();
    let frame = read_packet(&mut owner);
    assert_eq!(&frame[..4], b"THV2");
    let mut busy = service.connect();
    let mut auth = b"THA1".to_vec();
    auth.extend(vec![b'a'; 64]);
    packet(&mut busy, &auth);
    let mut status = [0; 5];
    busy.read_exact(&mut status).unwrap();
    assert_eq!(&status, b"THS1\x03");
}
#[test]
fn heartbeat_keeps_lease_alive_and_disconnect_reaps_encoder() {
    let Some(service) = Service::start() else {
        return;
    };
    let mut owner = service.auth();
    let end = Instant::now() + Duration::from_secs(7);
    let mut previous = None;
    while Instant::now() < end {
        heartbeat(&mut owner);
        let frame = read_packet(&mut owner);
        let seq = u64::from_be_bytes(frame[4..12].try_into().unwrap());
        if let Some(old) = previous {
            assert!(seq > old);
        }
        previous = Some(seq);
    }
    let pid: u32 = fs::read_to_string(service.dir.join("started"))
        .unwrap()
        .trim()
        .parse()
        .unwrap();
    drop(owner);
    let end = Instant::now() + Duration::from_secs(4);
    while unsafe { libc::kill(pid as i32, 0) } == 0 {
        assert!(Instant::now() < end, "owned encoder survived disconnect");
        std::thread::sleep(Duration::from_millis(30));
    }
}
#[test]
fn native_configuration_is_atomic_and_preserves_paths() {
    let dir = std::env::temp_dir().join(format!("termish-config-{}", std::process::id()));
    fs::create_dir_all(&dir).unwrap();
    let path = dir.join("screen-service.json");
    fs::write(dir.join("custom-token"), "a".repeat(64)).unwrap();
    fs::write(
        &path,
        json!({"token_file":"~/custom-token","log_file":"~/custom-log"}).to_string(),
    )
    .unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_termish-screen-service"))
        .env("HOME", &dir)
        .arg("--write-config")
        .arg(&path)
        .args(["17321", "/bin/sh"])
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let value: Value = serde_json::from_slice(&fs::read(&path).unwrap()).unwrap();
    assert_eq!(value["token_file"], "~/custom-token");
    assert_eq!(path.metadata().unwrap().permissions().mode() & 0o777, 0o600);
    let before = fs::read(&path).unwrap();
    let status = Command::new(env!("CARGO_BIN_EXE_termish-screen-service"))
        .arg("--write-config")
        .arg(&path)
        .args(["not-a-port", "/bin/sh"])
        .status()
        .unwrap();
    assert!(!status.success());
    assert_eq!(fs::read(&path).unwrap(), before);
    fs::remove_dir_all(dir).unwrap();
}

#[test]
fn feedback_restarts_encoder_in_place_without_resetting_sequence() {
    let Some(service) = Service::start() else {
        return;
    };
    let mut owner = service.auth();
    let first = read_packet(&mut owner);
    let initial_sequence = u64::from_be_bytes(first[4..12].try_into().unwrap());
    let initial_pid = fs::read_to_string(service.dir.join("started")).unwrap();
    let mut feedback = vec![0; 40];
    feedback[..4].copy_from_slice(b"THF1");
    feedback[4] = 1;
    feedback[5] = 1;
    feedback[20..24].copy_from_slice(&120u32.to_be_bytes());
    packet(&mut owner, &feedback);
    packet(&mut owner, &feedback);
    let end = Instant::now() + Duration::from_secs(5);
    loop {
        heartbeat(&mut owner);
        let frame = read_packet(&mut owner);
        assert!(u64::from_be_bytes(frame[4..12].try_into().unwrap()) > initial_sequence);
        if fs::read_to_string(service.dir.join("started")).unwrap() != initial_pid && frame[20] == 1
        {
            break;
        }
        assert!(
            Instant::now() < end,
            "encoder did not recover in the same connection"
        );
    }
}

#[cfg(target_os = "linux")]
#[test]
#[ignore = "Requires a disposable Xvfb display, ffmpeg, xev, xdotool and xclip"]
fn real_x11_capture_keyboard_clipboard_and_disconnect_releases_drag() {
    let service = Service::start().expect("ffmpeg required");
    let ffmpeg = Command::new("sh")
        .args(["-c", "command -v ffmpeg"])
        .output()
        .unwrap();
    let mut config: Value =
        serde_json::from_slice(&fs::read(service.dir.join("config.json")).unwrap()).unwrap();
    config["ffmpeg"] = json!(String::from_utf8(ffmpeg.stdout).unwrap().trim());
    config["capture_source"] = json!("desktop");
    // Restart with real capture configuration; the synthetic fixture never accesses a desktop.
    let mut service = service;
    unsafe {
        libc::kill(service.child.id() as i32, libc::SIGTERM);
    }
    service.child.wait().unwrap();
    fs::write(service.dir.join("config.json"), config.to_string()).unwrap();
    fs::write(service.dir.join("settings"), "fps=15\nscale=640:-2\n").unwrap();
    let events = service.dir.join("events");
    struct Window(Child);
    impl Drop for Window {
        fn drop(&mut self) {
            let _ = self.0.kill();
            let _ = self.0.wait();
        }
    }
    let _window = Window(
        Command::new("stdbuf")
            .args([
                "-oL",
                "xev",
                "-name",
                "TermishRustTest",
                "-geometry",
                "640x360+0+0",
            ])
            .stdout(fs::File::create(&events).unwrap())
            .stderr(Stdio::null())
            .spawn()
            .unwrap(),
    );
    let output = Command::new("xdotool")
        .args(["search", "--sync", "--name", "TermishRustTest"])
        .output()
        .unwrap();
    let window = String::from_utf8(output.stdout).unwrap();
    assert!(Command::new("xdotool")
        .args(["windowfocus", window.lines().next().unwrap()])
        .status()
        .unwrap()
        .success());
    service.child = Command::new(env!("CARGO_BIN_EXE_termish-screen-service"))
        .arg("--config")
        .arg(service.dir.join("config.json"))
        .env("XDG_SESSION_TYPE", "x11")
        .stdout(Stdio::null())
        .stderr(Stdio::inherit())
        .spawn()
        .unwrap();
    let end = Instant::now() + Duration::from_secs(5);
    while TcpStream::connect(("127.0.0.1", service.port)).is_err() {
        assert!(Instant::now() < end);
        std::thread::sleep(Duration::from_millis(20));
    }
    let mut owner = service.auth();
    let first = read_packet(&mut owner);
    assert_eq!(&first[..4], b"THV2");
    assert_eq!(first[20], 1);
    let control = |socket: &mut TcpStream, kind: u8, extra: i32, text: &str| {
        let mut body = b"THC1".to_vec();
        body.push(kind);
        body.extend(0.2f32.to_be_bytes());
        body.extend(0.2f32.to_be_bytes());
        body.extend(extra.to_be_bytes());
        body.extend(text.as_bytes());
        packet(socket, &body);
    };
    control(&mut owner, 5, 48, "");
    control(&mut owner, 4, 0, "你好 Rust 🌍");
    let end = Instant::now() + Duration::from_secs(4);
    while !fs::read_to_string(&events).unwrap().contains("Tab") {
        assert!(Instant::now() < end);
        heartbeat(&mut owner);
        std::thread::sleep(Duration::from_millis(20));
    }
    // Tab and text are separate control messages. Observing Tab does not mean
    // the text message has been processed and xclip has taken ownership yet.
    let end = Instant::now() + Duration::from_secs(4);
    loop {
        let clipboard = Command::new("xclip")
            .args(["-selection", "clipboard", "-o"])
            .output()
            .unwrap();
        if clipboard.status.success()
            && String::from_utf8_lossy(&clipboard.stdout) == "你好 Rust 🌍"
        {
            break;
        }
        assert!(Instant::now() < end, "clipboard text did not arrive");
        heartbeat(&mut owner);
        std::thread::sleep(Duration::from_millis(20));
    }
    control(&mut owner, 10, 0, "");
    let end = Instant::now() + Duration::from_secs(3);
    while !fs::read_to_string(&events).unwrap().contains("ButtonPress") {
        assert!(Instant::now() < end);
        heartbeat(&mut owner);
        std::thread::sleep(Duration::from_millis(20));
    }
    drop(owner);
    let end = Instant::now() + Duration::from_secs(4);
    while !fs::read_to_string(&events)
        .unwrap()
        .contains("ButtonRelease")
    {
        assert!(Instant::now() < end);
        std::thread::sleep(Duration::from_millis(20));
    }
    let mut decoder = Command::new(config["ffmpeg"].as_str().unwrap())
        .args([
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "h264",
            "-i",
            "pipe:0",
            "-f",
            "null",
            "-",
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::inherit())
        .spawn()
        .unwrap();
    decoder
        .stdin
        .take()
        .unwrap()
        .write_all(&first[21..])
        .unwrap();
    assert!(decoder.wait().unwrap().success());
}

#[test]
fn missing_heartbeat_expires_connection_and_reaps_encoder() {
    let Some(service) = Service::start() else {
        return;
    };
    let mut owner = service.auth();
    read_packet(&mut owner);
    let pid: i32 = fs::read_to_string(service.dir.join("started"))
        .unwrap()
        .trim()
        .parse()
        .unwrap();
    let end = Instant::now() + Duration::from_secs(9);
    loop {
        let mut head = [0; 4];
        match owner.read_exact(&mut head) {
            Ok(()) => {
                let size = u32::from_be_bytes(head) as usize;
                assert!(size < 4 * 1024 * 1024);
                let mut data = vec![0; size];
                if owner.read_exact(&mut data).is_err() {
                    break;
                }
            }
            Err(_) => break,
        }
        assert!(
            Instant::now() < end,
            "stale client retained video ownership"
        );
    }
    let end = Instant::now() + Duration::from_secs(3);
    while unsafe { libc::kill(pid, 0) } == 0 {
        assert!(Instant::now() < end, "encoder survived lease expiry");
        std::thread::sleep(Duration::from_millis(20));
    }
}
