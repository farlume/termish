use crate::{
    config::{CaptureSource, Config, StreamSettings},
    management::{Action, Management, Outcome},
    platform::{self, Input},
    protocol::{self, Control, Feedback, Frames},
};
use std::{
    fs::{self, OpenOptions},
    io::{self, Read, Write},
    net::{Shutdown, TcpListener, TcpStream},
    os::fd::AsRawFd,
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc, Mutex,
    },
    thread,
    time::{Duration, Instant},
};

pub static SHUTDOWN: AtomicBool = AtomicBool::new(false);

pub(crate) fn log(config: &Config, message: &str) {
    // Deliberately exclude credentials and packet payloads from diagnostics.
    if let Ok(mut file) = OpenOptions::new()
        .create(true)
        .append(true)
        .open(&config.log_file)
    {
        let _ = writeln!(file, "[rust] {message}");
    }
}

struct Adaptation {
    scale: u32,
    bad: u32,
    healthy: u32,
    last_restart: Option<Instant>,
    restart: Option<&'static str>,
}
impl Default for Adaptation {
    fn default() -> Self {
        Self {
            scale: 100,
            bad: 0,
            healthy: 0,
            last_restart: None,
            restart: None,
        }
    }
}
impl Adaptation {
    fn request(&mut self, reason: &'static str, interval: u64) -> bool {
        if self.restart.is_some()
            || self
                .last_restart
                .is_some_and(|t| t.elapsed() < Duration::from_secs(interval))
        {
            return false;
        }
        self.last_restart = Some(Instant::now());
        self.restart = Some(reason);
        true
    }
    fn feedback(&mut self, feedback: &Feedback) -> bool {
        if feedback.pressured {
            self.bad = self.bad.saturating_add(1);
            self.healthy = 0;
        } else {
            self.bad = 0;
            self.healthy = self.healthy.saturating_add(1);
        }
        let ladder = [100, 75, 55, 40];
        let current = ladder.iter().position(|s| *s == self.scale).unwrap_or(0);
        let next = if self.bad >= 2 && current < 3 {
            ladder[current + 1]
        } else if self.healthy >= 15 && current > 0 {
            ladder[current - 1]
        } else {
            self.scale
        };
        if next != self.scale {
            if self.request("adaptive bitrate", 5) {
                self.scale = next;
                self.bad = 0;
                self.healthy = 0;
                return true;
            }
        } else if feedback.keyframe && feedback.dropped >= 100 {
            return self.request("client keyframe request", 3);
        }
        false
    }
}

struct Session {
    config: Arc<Config>,
    socket: TcpStream,
    stopped: AtomicBool,
    last_heartbeat: Mutex<Instant>,
    encoder: Mutex<Option<Child>>,
    #[cfg(target_os = "linux")]
    capture: Mutex<Option<platform::wayland::Capture>>,
    #[cfg(target_os = "linux")]
    backend: Mutex<Option<crate::encoding::Backend>>,
    adapt: Mutex<Adaptation>,
    input: Mutex<Input>,
}
impl Session {
    fn input_status(&self) -> u8 {
        if self.config.capture_source == CaptureSource::TestPattern {
            2
        } else {
            self.input.lock().unwrap().status()
        }
    }
    fn new(config: Arc<Config>, socket: TcpStream) -> Self {
        Self {
            config,
            socket,
            stopped: AtomicBool::new(false),
            last_heartbeat: Mutex::new(Instant::now()),
            encoder: Mutex::new(None),
            #[cfg(target_os = "linux")]
            capture: Mutex::new(None),
            #[cfg(target_os = "linux")]
            backend: Mutex::new(None),
            adapt: Mutex::new(Adaptation::default()),
            input: Mutex::new(Input::default()),
        }
    }
    fn expired(&self) -> bool {
        self.stopped.load(Ordering::Acquire)
            || self.last_heartbeat.lock().unwrap().elapsed()
                > Duration::from_secs(protocol::LEASE_SECONDS)
    }
    fn kill_encoder(&self) {
        if let Some(child) = self.encoder.lock().unwrap().as_mut() {
            let _ = child.kill();
        }
    }
    fn stop(&self, reason: &str) {
        if self.stopped.swap(true, Ordering::AcqRel) {
            return;
        }
        log(&self.config, &format!("stream stopped: {reason}"));
        let _ = self.socket.shutdown(Shutdown::Both);
        self.input.lock().unwrap().release();
        self.reap_encoder();
    }
    fn reap_encoder(&self) {
        #[cfg(target_os = "linux")]
        {
            self.capture.lock().unwrap().take();
        }
        let mut guard = self.encoder.lock().unwrap();
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
            let _ = child.wait();
            if fs::read_to_string(&self.config.encoder_pid_file)
                .ok()
                .is_some_and(|s| s.trim() == child.id().to_string())
            {
                let _ = fs::remove_file(&self.config.encoder_pid_file);
            }
        }
    }
    fn start_encoder(&self) -> io::Result<std::process::ChildStdout> {
        let mut guard = self.encoder.lock().unwrap();
        if self.stopped.load(Ordering::Acquire) {
            return Err(io::Error::new(
                io::ErrorKind::Interrupted,
                "session stopped",
            ));
        }
        let scale = self.adapt.lock().unwrap().scale;
        let cfg = StreamSettings::read(&self.config.stream_config_file);
        #[cfg(target_os = "linux")]
        let wayland = platform::wayland::active();
        #[cfg(not(target_os = "linux"))]
        let wayland = false;
        #[cfg(target_os = "linux")]
        let backend = if self.config.capture_source == CaptureSource::Desktop {
            let mut selected = self.backend.lock().unwrap();
            if selected.is_none() {
                let backend = crate::encoding::select(
                    &self.config.ffmpeg,
                    self.config.encoder,
                    &self.stopped,
                );
                log(
                    &self.config,
                    &format!("selected encoder={}", backend.codec()),
                );
                *selected = Some(backend);
            }
            selected.as_ref().unwrap().clone()
        } else {
            crate::encoding::Backend::Software
        };
        #[cfg(not(target_os = "linux"))]
        let backend = crate::encoding::Backend::Software;
        if self.stopped.load(Ordering::Acquire) {
            return Err(io::Error::new(
                io::ErrorKind::Interrupted,
                "session stopped",
            ));
        }
        let mut command = Command::new(&self.config.ffmpeg);
        let args = if self.config.capture_source == CaptureSource::TestPattern {
            cfg.args_for_test_pattern()
        } else {
            cfg.args_for_encoder(scale, wayland, &backend)
        };
        command
            .args(args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(
                OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(&self.config.log_file)?,
            );
        #[cfg(target_os = "linux")]
        {
            if wayland && self.config.capture_source == CaptureSource::Desktop {
                let (capture, stdout) =
                    platform::wayland::capture(cfg.fps, &self.config.log_file, &self.stopped)?;
                command.stdin(Stdio::from(stdout));
                *self.capture.lock().unwrap() = Some(capture);
            }
            use std::os::unix::process::CommandExt;
            let parent = unsafe { libc::getpid() };
            // A crashed service must not leave a screen recorder running.
            unsafe {
                command.pre_exec(move || {
                    if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) != 0 {
                        return Err(io::Error::last_os_error());
                    }
                    if libc::getppid() != parent {
                        return Err(io::Error::new(io::ErrorKind::Interrupted, "service exited"));
                    }
                    Ok(())
                });
            }
        }
        let mut child = command.spawn()?;
        let stdout = child.stdout.take().unwrap();
        // Diagnostic PID only; never kill an unrelated process using a stale PID file.
        let _ = fs::write(&self.config.encoder_pid_file, child.id().to_string());
        *guard = Some(child);
        log(
            &self.config,
            &format!(
                "encoder started fps={} scale={} bitrate={}%",
                cfg.fps, cfg.scale, scale
            ),
        );
        Ok(stdout)
    }
    fn request_restart(&self, reason: &'static str, interval: u64) {
        let restart = self.adapt.lock().unwrap().request(reason, interval);
        if restart {
            self.kill_encoder();
        }
    }
    #[cfg(target_os = "linux")]
    fn fallback_hardware(&self) -> bool {
        let mut backend = self.backend.lock().unwrap();
        if backend.as_ref().is_some_and(|b| b.hardware()) {
            log(
                &self.config,
                "hardware encoder stopped producing video; falling back to libx264",
            );
            *backend = Some(crate::encoding::Backend::Software);
            return true;
        }
        false
    }
    fn control(self: Arc<Self>) {
        let Ok(mut reader) = self.socket.try_clone() else {
            self.stop("control clone failed");
            return;
        };
        let _ = reader.set_read_timeout(Some(Duration::from_secs(protocol::LEASE_SECONDS)));
        let mut last_wake = Instant::now();
        while !self.stopped.load(Ordering::Acquire) {
            let data = match protocol::read_packet(&mut reader, 17, protocol::MAX_CONTROL) {
                Ok(data) => data,
                Err(error) => {
                    log(&self.config, &format!("control read failed: {error}"));
                    break;
                }
            };
            if let Some(feedback) = Feedback::parse(&data) {
                *self.last_heartbeat.lock().unwrap() = Instant::now();
                let restart = self.adapt.lock().unwrap().feedback(&feedback);
                if restart {
                    self.kill_encoder();
                }
            } else if let Some(control) = Control::parse(&data) {
                *self.last_heartbeat.lock().unwrap() = Instant::now();
                if control.kind == 13 {
                    if last_wake.elapsed() > Duration::from_secs(15) {
                        platform::wake_display();
                        last_wake = Instant::now();
                    }
                } else if self.config.capture_source == CaptureSource::Desktop {
                    let mut input = self.input.lock().unwrap();
                    if !self.stopped.load(Ordering::Acquire) {
                        input.inject(&control);
                    }
                }
            }
        }
        self.stop("control EOF, timeout or invalid packet");
    }
    fn pump(self: Arc<Self>, initial_status: u8) {
        let mut writer = match self.socket.try_clone() {
            Ok(s) => s,
            Err(_) => {
                self.stop("video clone failed");
                return;
            }
        };
        let mut sequence = 0;
        let mut status = initial_status;
        let mut last_status_check = Instant::now();
        let started = Instant::now();
        let mut sent = 0u64;
        platform::wake_display();
        let result = (|| -> io::Result<()> {
            loop {
                #[cfg(target_os = "macos")]
                if self.config.capture_source == CaptureSource::Desktop
                    && !platform::capture_allowed()
                {
                    protocol::write_status(&mut writer, 4)?;
                    return Err(io::Error::new(
                        io::ErrorKind::PermissionDenied,
                        "screen recording permission missing",
                    ));
                }
                let mut stdout = self.start_encoder()?;
                let mut frames = Frames::default();
                let encoder_started = Instant::now();
                let mut last_data = Instant::now();
                let mut buffer = vec![0; 262144];
                while !self.stopped.load(Ordering::Acquire) && !SHUTDOWN.load(Ordering::Acquire) {
                    if last_status_check.elapsed() >= Duration::from_secs(1) {
                        last_status_check = Instant::now();
                        #[cfg(target_os = "macos")]
                        if self.config.capture_source == CaptureSource::Desktop
                            && !platform::capture_allowed()
                        {
                            protocol::write_status(&mut writer, 4)?;
                            return Err(io::Error::new(
                                io::ErrorKind::PermissionDenied,
                                "screen recording permission revoked",
                            ));
                        }
                        let current = self.input_status();
                        if current != status {
                            status = current;
                            protocol::write_status(&mut writer, status)?;
                            log(&self.config, &format!("input permission status={status}"));
                        }
                    }
                    if self.expired() {
                        return Err(io::Error::new(
                            io::ErrorKind::TimedOut,
                            "client heartbeat expired",
                        ));
                    }
                    let mut pollfd = libc::pollfd {
                        fd: stdout.as_raw_fd(),
                        events: libc::POLLIN,
                        revents: 0,
                    };
                    let available = unsafe { libc::poll(&mut pollfd, 1, 250) };
                    if available < 0 {
                        let error = io::Error::last_os_error();
                        if error.kind() == io::ErrorKind::Interrupted {
                            continue;
                        }
                        return Err(error);
                    }
                    if available == 0 {
                        if self.expired() {
                            return Err(io::Error::new(
                                io::ErrorKind::TimedOut,
                                "client heartbeat expired",
                            ));
                        }
                        if ((sent == 0 && encoder_started.elapsed() > Duration::from_secs(45))
                            || (sent > 0 && last_data.elapsed() > Duration::from_secs(20)))
                            && !platform::display_asleep()
                        {
                            self.request_restart("encoder output watchdog", 3);
                        }
                        continue;
                    }
                    let count = stdout.read(&mut buffer)?;
                    if count == 0 {
                        break;
                    }
                    last_data = Instant::now();
                    for frame in frames.push(&buffer[..count])? {
                        protocol::write_frame(
                            &mut writer,
                            &frame,
                            sequence,
                            started.elapsed().as_micros().min(u128::from(u64::MAX)) as u64,
                        )?;
                        sequence += 1;
                        sent += frame.len() as u64;
                    }
                }
                self.reap_encoder();
                if self.stopped.load(Ordering::Acquire) || SHUTDOWN.load(Ordering::Acquire) {
                    return Ok(());
                }
                let restart = self.adapt.lock().unwrap().restart.take();
                #[cfg(target_os = "linux")]
                if (restart.is_none() || restart == Some("encoder output watchdog"))
                    && self.fallback_hardware()
                {
                    continue;
                }
                if let Some(reason) = restart {
                    log(
                        &self.config,
                        &format!("encoder restarted in-place: {reason}"),
                    );
                } else {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "encoder exited",
                    ));
                }
            }
        })();
        log(
            &self.config,
            &format!(
                "stream ended elapsed={:.1}s sent={sent} result={result:?}",
                started.elapsed().as_secs_f64()
            ),
        );
        self.stop("video ended");
    }
}

pub fn run(config: Config, management: Arc<Management>) -> io::Result<Outcome> {
    if let Some(parent) = config.log_file.parent() {
        fs::create_dir_all(parent)?;
    }
    let probe = TcpListener::bind(("127.0.0.1", config.port))?;
    probe.set_nonblocking(true)?;
    let video = TcpListener::bind(("127.0.0.1", config.port + 2))?;
    video.set_nonblocking(true)?;
    let config = Arc::new(config);
    log(
        &config,
        &format!(
            "service started version={} control_port={} video_port={}",
            config.version,
            config.port,
            config.port + 2
        ),
    );
    let mut active: Option<Arc<Session>> = None;
    management.ready.store(true, Ordering::Release);
    #[cfg(target_os = "macos")]
    let mut capture_permission = crate::permission::CapturePermission::default();
    let outcome = 'server: loop {
        if SHUTDOWN.load(Ordering::Acquire) {
            break Outcome::Quit;
        }
        management.connected.store(
            active.as_ref().is_some_and(|owner| !owner.expired()),
            Ordering::Release,
        );
        while let Some(action) = management.next_request() {
            match action {
                Action::TogglePause | Action::Pause | Action::Resume => {
                    let paused = match action {
                        Action::Pause => true,
                        Action::Resume => false,
                        _ => !management.paused.load(Ordering::Acquire),
                    };
                    management.paused.store(paused, Ordering::Release);
                    if paused {
                        if let Some(owner) = active.take() {
                            owner.stop("paused from menu");
                        }
                        management.connected.store(false, Ordering::Release);
                    }
                    log(
                        &config,
                        if paused {
                            "remote access paused from menu"
                        } else {
                            "remote access resumed from menu"
                        },
                    );
                }
                Action::Disconnect => {
                    if let Some(owner) = active.take() {
                        owner.stop("disconnected from menu");
                    }
                    management.connected.store(false, Ordering::Release);
                }
                Action::Restart => break 'server Outcome::Restart,
                Action::Quit => break 'server Outcome::Quit,
            }
        }
        #[cfg(target_os = "macos")]
        if !config.menu_bar {
            platform::poll_events();
        }
        // Drain liveness probes; they must never start an encoder or capture.
        while probe.accept().is_ok() {}
        let (mut socket, _) = match video.accept() {
            Ok(value) => value,
            Err(e) if e.kind() == io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(30));
                continue;
            }
            Err(e) => return Err(e),
        };
        // macOS may inherit O_NONBLOCK from the acceptor; framed reads/writes
        // below intentionally use blocking sockets with bounded timeouts.
        socket.set_nonblocking(false)?;
        socket.set_nodelay(true)?;
        socket.set_read_timeout(Some(Duration::from_secs(5)))?;
        socket.set_write_timeout(Some(Duration::from_secs(5)))?;
        let packet = protocol::read_packet(&mut socket, protocol::AUTH_SIZE, protocol::AUTH_SIZE);
        if !packet.is_ok_and(|data| protocol::authenticated(&data, &config.token)) {
            log(&config, "authentication rejected");
            let _ = socket.shutdown(Shutdown::Both);
            continue;
        }
        if management.paused.load(Ordering::Acquire) {
            // No new status code or misleading "busy" reason. An authenticated
            // paused connection closes before any encoder, permission request or input.
            let _ = socket.shutdown(Shutdown::Both);
            log(&config, "connection rejected while remote access paused");
            continue;
        }
        if active.as_ref().is_some_and(|owner| !owner.expired()) {
            let _ = socket.write_all(b"THS1\x03");
            let _ = socket.shutdown(Shutdown::Both);
            log(&config, "tcp busy");
            continue;
        }
        if let Some(old) = active.take() {
            old.stop("stale tcp lease replaced");
        }
        #[cfg(target_os = "macos")]
        if config.capture_source == CaptureSource::Desktop && !platform::capture_allowed() {
            let _ = socket.write_all(b"THS1\x04");
            let _ = socket.shutdown(Shutdown::Both);
            log(
                &config,
                "screen recording permission missing; encoder not started",
            );
            capture_permission.check(|| false, platform::request_capture_access);
            continue;
        }
        let session = Arc::new(Session::new(config.clone(), socket));
        let status = session.input_status();
        if (&session.socket)
            .write_all(&[b'T', b'H', b'S', b'1', status])
            .is_err()
        {
            continue;
        }
        active = Some(session.clone());
        management.connected.store(true, Ordering::Release);
        let control = session.clone();
        thread::spawn(move || control.control());
        thread::spawn(move || session.pump(status));
    };
    if let Some(owner) = active {
        owner.stop("service shutdown");
    }
    #[cfg(target_os = "linux")]
    platform::wayland::close();
    log(&config, "service stopped");
    management.ready.store(false, Ordering::Release);
    management.connected.store(false, Ordering::Release);
    Ok(outcome)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Read;

    fn wait_for(mut condition: impl FnMut() -> bool) {
        let end = Instant::now() + Duration::from_secs(5);
        while !condition() {
            assert!(Instant::now() < end, "menu lifecycle action timed out");
            thread::sleep(Duration::from_millis(20));
        }
    }

    struct ManagedFixture {
        config: Config,
        control: Arc<Management>,
        worker: Option<thread::JoinHandle<io::Result<Outcome>>>,
    }
    impl Drop for ManagedFixture {
        fn drop(&mut self) {
            self.control.request(Action::Quit);
            if let Some(worker) = self.worker.take() {
                let _ = worker.join();
            }
            let _ = fs::remove_dir_all(self.config.log_file.parent().unwrap());
        }
    }
    impl ManagedFixture {
        fn start() -> Option<Self> {
            Self::start_attempt(0)
        }
        fn start_attempt(attempt: u8) -> Option<Self> {
            let ffmpeg = crate::config::executable("ffmpeg").ok()?;
            let (probe, video, port) = loop {
                let probe = TcpListener::bind("127.0.0.1:0").unwrap();
                let port = probe.local_addr().unwrap().port();
                if port < 65533 {
                    if let Ok(video) = TcpListener::bind(("127.0.0.1", port + 2)) {
                        break (probe, video, port);
                    }
                }
            };
            let dir =
                std::env::temp_dir().join(format!("termish-menu-{}-{port}", std::process::id()));
            fs::create_dir_all(&dir).unwrap();
            let config = Config {
                encoder: crate::encoding::Preference::Software,
                capture_source: CaptureSource::TestPattern,
                menu_bar: false,
                version: crate::config::VERSION,
                port,
                ffmpeg,
                token: [b'a'; 64],
                token_file: dir.join("token"),
                log_file: dir.join("log"),
                encoder_pid_file: dir.join("encoder.pid"),
                stream_config_file: dir.join("settings"),
            };
            fs::write(&config.stream_config_file, "fps=5\nscale=640:-2\n").unwrap();
            let control = Arc::new(Management::default());
            let worker_control = control.clone();
            let worker_config = config.clone();
            drop((probe, video));
            let worker = thread::spawn(move || run(worker_config, worker_control));
            let mut fixture = Self {
                config,
                control,
                worker: Some(worker),
            };
            wait_for(|| {
                fixture.control.ready.load(Ordering::Acquire)
                    || fixture.worker.as_ref().unwrap().is_finished()
            });
            if !fixture.control.ready.load(Ordering::Acquire) {
                let error = fixture.worker.take().unwrap().join().unwrap().unwrap_err();
                drop(fixture);
                if error.kind() == io::ErrorKind::AddrInUse && attempt < 4 {
                    return Self::start_attempt(attempt + 1);
                }
                panic!("fixture failed to bind: {error}");
            }
            Some(fixture)
        }
        fn authenticate(&self) -> TcpStream {
            let mut socket = TcpStream::connect(("127.0.0.1", self.config.port + 2)).unwrap();
            socket
                .set_read_timeout(Some(Duration::from_secs(5)))
                .unwrap();
            let mut auth = b"THA1".to_vec();
            auth.extend(self.config.token);
            socket
                .write_all(&(auth.len() as u32).to_be_bytes())
                .unwrap();
            socket.write_all(&auth).unwrap();
            socket
        }
        fn stream(&self) -> (TcpStream, i32) {
            let mut socket = self.authenticate();
            let mut status = [0; 5];
            socket.read_exact(&mut status).unwrap();
            assert_eq!(&status, b"THS1\x02");
            let mut header = [0; 4];
            socket.read_exact(&mut header).unwrap();
            let length = u32::from_be_bytes(header) as usize;
            assert!(length < 4 * 1024 * 1024);
            let mut frame = vec![0; length];
            socket.read_exact(&mut frame).unwrap();
            assert_eq!(&frame[..4], b"THV2");
            let pid = fs::read_to_string(&self.config.encoder_pid_file)
                .unwrap()
                .trim()
                .parse()
                .unwrap();
            (socket, pid)
        }
        fn encoder_stopped(&self, pid: i32) {
            wait_for(|| unsafe { libc::kill(pid, 0) } != 0);
            assert!(!self.config.encoder_pid_file.exists());
        }
    }

    #[test]
    fn menu_pause_disconnect_restart_and_quit_clean_up_real_streams() {
        let Some(mut fixture) = ManagedFixture::start() else {
            eprintln!("SKIP menu lifecycle: ffmpeg missing");
            return;
        };
        let (_socket, pid) = fixture.stream();
        fixture.control.request(Action::TogglePause);
        fixture.encoder_stopped(pid);
        assert!(fixture.control.paused.load(Ordering::Acquire));
        assert!(!fixture.control.connected.load(Ordering::Acquire));
        let mut paused = fixture.authenticate();
        assert_eq!(paused.read(&mut [0]).unwrap(), 0);
        assert!(!fixture.config.encoder_pid_file.exists());
        fixture.control.request(Action::TogglePause);
        wait_for(|| !fixture.control.paused.load(Ordering::Acquire));
        let (_socket, pid) = fixture.stream();
        fixture.control.request(Action::Disconnect);
        fixture.encoder_stopped(pid);
        let (_socket, pid) = fixture.stream();
        fixture.control.request(Action::Restart);
        assert_eq!(
            fixture.worker.take().unwrap().join().unwrap().unwrap(),
            Outcome::Restart
        );
        fixture.encoder_stopped(pid);
        assert!(!fixture.control.ready.load(Ordering::Acquire));
        assert!(TcpStream::connect(("127.0.0.1", fixture.config.port + 2)).is_err());
        let mut fixture = ManagedFixture::start().unwrap();
        let (_socket, pid) = fixture.stream();
        fixture.control.request(Action::Quit);
        assert_eq!(
            fixture.worker.take().unwrap().join().unwrap().unwrap(),
            Outcome::Quit
        );
        fixture.encoder_stopped(pid);
        assert!(!fixture.control.connected.load(Ordering::Acquire));
    }
    #[test]
    fn feedback_cooldown_does_not_commit_an_unapplied_bitrate() {
        let mut state = Adaptation::default();
        let pressure = Feedback {
            keyframe: false,
            pressured: true,
            dropped: 150,
        };
        assert!(!state.feedback(&pressure));
        assert!(state.feedback(&pressure));
        assert_eq!(state.scale, 75);
        state.restart.take();
        assert!(!state.feedback(&pressure));
        assert!(!state.feedback(&pressure));
        assert_eq!(state.scale, 75);
        state.last_restart = Some(Instant::now() - Duration::from_secs(6));
        assert!(state.feedback(&pressure));
        assert_eq!(state.scale, 55);
    }
}
