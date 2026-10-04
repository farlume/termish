use crate::{
    config::{Config, StreamSettings},
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

fn log(config: &Config, message: &str) {
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
    adapt: Mutex<Adaptation>,
    input: Mutex<Input>,
}
impl Session {
    fn new(config: Arc<Config>, socket: TcpStream) -> Self {
        Self {
            config,
            socket,
            stopped: AtomicBool::new(false),
            last_heartbeat: Mutex::new(Instant::now()),
            encoder: Mutex::new(None),
            #[cfg(target_os = "linux")]
            capture: Mutex::new(None),
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
        let mut command = Command::new(&self.config.ffmpeg);
        command
            .args(cfg.args_for_capture(scale, wayland))
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
            if wayland {
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
                } else {
                    let mut input = self.input.lock().unwrap();
                    if !self.stopped.load(Ordering::Acquire) {
                        input.inject(&control);
                    }
                }
            }
        }
        self.stop("control EOF, timeout or invalid packet");
    }
    fn pump(self: Arc<Self>) {
        let mut writer = match self.socket.try_clone() {
            Ok(s) => s,
            Err(_) => {
                self.stop("video clone failed");
                return;
            }
        };
        let mut sequence = 0;
        let started = Instant::now();
        let mut sent = 0u64;
        platform::wake_display();
        let result = (|| -> io::Result<()> {
            loop {
                let mut stdout = self.start_encoder()?;
                let mut frames = Frames::default();
                let encoder_started = Instant::now();
                let mut last_data = Instant::now();
                let mut buffer = vec![0; 262144];
                while !self.stopped.load(Ordering::Acquire) && !SHUTDOWN.load(Ordering::Acquire) {
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

pub fn run(config: Config) -> io::Result<()> {
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
    while !SHUTDOWN.load(Ordering::Acquire) {
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
        if active.as_ref().is_some_and(|owner| !owner.expired()) {
            let _ = socket.write_all(b"THS1\x03");
            let _ = socket.shutdown(Shutdown::Both);
            log(&config, "tcp busy");
            continue;
        }
        if let Some(old) = active.take() {
            old.stop("stale tcp lease replaced");
        }
        let session = Arc::new(Session::new(config.clone(), socket));
        let status = session.input.lock().unwrap().status();
        if (&session.socket)
            .write_all(&[b'T', b'H', b'S', b'1', status])
            .is_err()
        {
            continue;
        }
        active = Some(session.clone());
        let control = session.clone();
        thread::spawn(move || control.control());
        thread::spawn(move || session.pump());
    }
    if let Some(owner) = active {
        owner.stop("service shutdown");
    }
    #[cfg(target_os = "linux")]
    platform::wayland::close();
    log(&config, "service stopped");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
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
