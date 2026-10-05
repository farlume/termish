//! RemoteDesktop portal consent scopes both the PipeWire stream and input.
use crate::protocol::Control;
use futures_util::StreamExt;
use std::{
    collections::HashMap,
    io,
    os::fd::AsRawFd,
    process::{Child, ChildStdout, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex, OnceLock,
    },
    time::Duration,
};
use zbus::{
    zvariant::{OwnedFd, OwnedObjectPath, OwnedValue},
    Connection, MatchRule, MessageStream, Proxy,
};
type Options = HashMap<String, OwnedValue>;
type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
const DEST: &str = "org.freedesktop.portal.Desktop";
const PATH: &str = "/org/freedesktop/portal/desktop";
const REMOTE: &str = "org.freedesktop.portal.RemoteDesktop";
const SCREEN: &str = "org.freedesktop.portal.ScreenCast";
pub fn active() -> bool {
    std::env::var("XDG_SESSION_TYPE").is_ok_and(|s| s.eq_ignore_ascii_case("wayland"))
}
fn token() -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static SEQ: AtomicU64 = AtomicU64::new(0);
    format!(
        "termish_{}_{}",
        std::process::id(),
        SEQ.fetch_add(1, Ordering::Relaxed)
    )
}
async fn request(
    conn: &Connection,
    interface: &str,
    method: &str,
    session: Option<&OwnedObjectPath>,
    mut opts: Options,
    cancelled: &AtomicBool,
) -> Result<Options> {
    let handle = token();
    let sender = conn
        .unique_name()
        .ok_or("D-Bus name unavailable")?
        .as_str()
        .trim_start_matches(':')
        .replace('.', "_");
    let expected = format!("/org/freedesktop/portal/desktop/request/{sender}/{handle}");
    opts.insert(
        "handle_token".into(),
        OwnedValue::from(zbus::zvariant::Str::from(handle.as_str())),
    );
    let rule = MatchRule::builder()
        .msg_type(zbus::message::Type::Signal)
        .sender(DEST)?
        .path(expected.as_str())?
        .interface("org.freedesktop.portal.Request")?
        .member("Response")?
        .build();
    // Subscribe before invoking: a portal can reply before the method returns.
    let mut responses = MessageStream::for_match_rule(rule, conn, Some(1)).await?;
    let proxy = Proxy::new(conn, DEST, PATH, interface).await?;
    let pending = async {
        let actual: OwnedObjectPath = match session {
            None => proxy.call(method, &(opts,)).await?,
            Some(session) if method == "Start" => proxy.call(method, &(session, "", opts)).await?,
            Some(session) => proxy.call(method, &(session, opts)).await?,
        };
        if actual.as_str() != expected {
            return Err("portal request handle mismatch".into());
        }
        let message = responses
            .next()
            .await
            .ok_or("portal response stream ended")??;
        let (code, result): (u32, Options) = message.body().deserialize()?;
        if code != 0 {
            return Err(format!("portal authorization denied ({code})").into());
        }
        Ok(result)
    };
    let result = wait_for_portal(pending, cancelled).await;
    if result.is_err() {
        if let Ok(proxy) = Proxy::new(
            conn,
            DEST,
            expected.as_str(),
            "org.freedesktop.portal.Request",
        )
        .await
        {
            let _ =
                tokio::time::timeout(Duration::from_secs(1), proxy.call::<_, _, ()>("Close", &()))
                    .await;
        }
    }
    result
}
async fn wait_for_portal<T>(
    work: impl std::future::Future<Output = Result<T>>,
    cancelled: &AtomicBool,
) -> Result<T> {
    tokio::select! {
        biased;
        _ = async {
            loop {
                if cancelled.load(Ordering::Acquire) || crate::service::SHUTDOWN.load(Ordering::Acquire) { break; }
                tokio::time::sleep(Duration::from_millis(50)).await;
            }
        } => Err("portal authorization cancelled".into()),
        result = tokio::time::timeout(Duration::from_secs(90), work) => result.map_err(|_| "portal authorization timed out")?,
    }
}
struct Portal {
    runtime: tokio::runtime::Runtime,
    conn: Connection,
    session: OwnedObjectPath,
    node: u32,
    width: f64,
    height: f64,
    serial: Option<u64>,
    left: bool,
    right: bool,
}
impl Portal {
    fn create(cancelled: &AtomicBool) -> Result<Self> {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()?;
        let (conn, session, node, width, height, serial) = runtime.block_on(async {
            let conn = Connection::session().await?;
            let mut options = Options::new();
            let session_token = token();
            options.insert(
                "session_handle_token".into(),
                OwnedValue::from(zbus::zvariant::Str::from(session_token.as_str())),
            );
            let created = request(&conn, REMOTE, "CreateSession", None, options, cancelled).await?;
            let session: OwnedObjectPath = created
                .get("session_handle")
                .ok_or("missing portal session")?
                .try_clone()?
                .try_into()?;
            let result: Result<(u32, f64, f64, Option<u64>)> = async {
                request(
                    &conn,
                    REMOTE,
                    "SelectDevices",
                    Some(&session),
                    HashMap::from([("types".into(), OwnedValue::from(3u32))]),
                    cancelled,
                )
                .await?;
                request(
                    &conn,
                    SCREEN,
                    "SelectSources",
                    Some(&session),
                    HashMap::from([
                        ("types".into(), OwnedValue::from(1u32)),
                        ("multiple".into(), OwnedValue::from(false)),
                        ("cursor_mode".into(), OwnedValue::from(2u32)),
                    ]),
                    cancelled,
                )
                .await?;
                let started = request(
                    &conn,
                    REMOTE,
                    "Start",
                    Some(&session),
                    Options::new(),
                    cancelled,
                )
                .await?;
                let streams: Vec<(u32, Options)> = started
                    .get("streams")
                    .ok_or("portal returned no monitor stream")?
                    .try_clone()?
                    .try_into()?;
                let (node, props) = streams.first().ok_or("portal returned no monitor stream")?;
                let size: Option<(i32, i32)> = props
                    .get("logical_size")
                    .or_else(|| props.get("size"))
                    .and_then(|v| v.try_clone().ok())
                    .and_then(|v| v.try_into().ok());
                let (w, h) = size.unwrap_or((1, 1));
                let serial = props
                    .get("pipewire-serial")
                    .and_then(|v| u64::try_from(v).ok());
                Ok((*node, f64::from(w.max(1)), f64::from(h.max(1)), serial))
            }
            .await;
            match result {
                Ok((node, w, h, serial)) => Ok((conn, session, node, w, h, serial)),
                Err(e) => {
                    if let Ok(proxy) = Proxy::new(
                        &conn,
                        DEST,
                        session.as_str(),
                        "org.freedesktop.portal.Session",
                    )
                    .await
                    {
                        let _ = tokio::time::timeout(
                            Duration::from_secs(3),
                            proxy.call::<_, _, ()>("Close", &()),
                        )
                        .await;
                    }
                    Err(e)
                }
            }
        })?;
        Ok(Self {
            runtime,
            conn,
            session,
            node,
            width,
            height,
            serial,
            left: false,
            right: false,
        })
    }
    fn call<B: serde::Serialize + zbus::zvariant::DynamicType>(
        &self,
        method: &str,
        body: &B,
    ) -> Result<()> {
        self.runtime.block_on(async {
            let proxy = Proxy::new(&self.conn, DEST, PATH, REMOTE).await?;
            tokio::time::timeout(Duration::from_secs(3), proxy.call::<_, _, ()>(method, body))
                .await??;
            Ok(())
        })
    }
    fn motion(&self, x: f32, y: f32) -> Result<()> {
        let px = (f64::from(x.clamp(0., 1.)) * self.width).min(self.width - 0.001);
        let py = (f64::from(y.clamp(0., 1.)) * self.height).min(self.height - 0.001);
        self.call(
            "NotifyPointerMotionAbsolute",
            &(&self.session, Options::new(), self.node, px, py),
        )
    }
    fn button(&mut self, code: i32, down: bool) -> Result<()> {
        self.call(
            "NotifyPointerButton",
            &(&self.session, Options::new(), code, u32::from(down)),
        )?;
        if code == 0x110 {
            self.left = down;
        } else {
            self.right = down;
        }
        Ok(())
    }
    fn key(&self, key: u32, down: bool) -> Result<()> {
        self.call(
            "NotifyKeyboardKeysym",
            &(&self.session, Options::new(), key as i32, u32::from(down)),
        )
    }
    fn inject(&mut self, c: &Control<'_>) -> Result<()> {
        match c.kind {
            0 | 11 => self.motion(c.x, c.y)?,
            1 | 10 | 2 | 12 | 6 | 7 | 8 | 9 => {
                self.motion(c.x, c.y)?;
                let code = if [6, 7, 9].contains(&c.kind) {
                    0x111
                } else {
                    0x110
                };
                if [8, 9].contains(&c.kind) {
                    self.button(code, true)?;
                    self.button(code, false)?;
                } else {
                    self.button(code, [1, 10, 6].contains(&c.kind))?;
                }
            }
            3 => self.call(
                "NotifyPointerAxis",
                &(
                    &self.session,
                    Options::new(),
                    0f64,
                    -f64::from(c.extra) * 15.,
                ),
            )?,
            4 => {
                for ch in c.text.chars() {
                    let point = ch as u32;
                    let key = if point <= 0xff {
                        point
                    } else {
                        0x01000000 | point
                    };
                    self.key(key, true)?;
                    self.key(key, false)?;
                }
            }
            5 => {
                if let Some(key) = super::linux::keysym(c.extra) {
                    let modifiers: Vec<_> = [(55, 1), (56, 2), (59, 4), (58, 8)]
                        .into_iter()
                        .filter(|(_, bit)| (c.x as u32) & bit != 0)
                        .filter_map(|(k, _)| super::linux::keysym(k))
                        .collect();
                    for k in &modifiers {
                        self.key(*k, true)?;
                    }
                    let result = self.key(key, true).and_then(|_| self.key(key, false));
                    for k in modifiers.into_iter().rev() {
                        let _ = self.key(k, false);
                    }
                    result?;
                }
            }
            _ => (),
        }
        Ok(())
    }
}
static PORTAL: OnceLock<Mutex<Option<Portal>>> = OnceLock::new();
fn portal() -> &'static Mutex<Option<Portal>> {
    PORTAL.get_or_init(|| Mutex::new(None))
}
pub fn authorized() -> bool {
    portal().try_lock().is_ok_and(|p| p.is_some())
}
pub fn inject(c: &Control<'_>) -> Result<()> {
    let mut guard = portal().lock().map_err(|_| "portal lock poisoned")?;
    if let Some(p) = guard.as_mut() {
        p.inject(c)?;
    }
    Ok(())
}
pub fn release() -> Result<()> {
    let mut guard = portal().lock().map_err(|_| "portal lock poisoned")?;
    if let Some(p) = guard.as_mut() {
        if p.left {
            p.button(0x110, false)?;
        }
        if p.right {
            p.button(0x111, false)?;
        }
    }
    Ok(())
}
pub fn close() {
    if let Ok(mut guard) = portal().lock() {
        if let Some(p) = guard.take() {
            let _ = p.runtime.block_on(async {
                let proxy = Proxy::new(
                    &p.conn,
                    DEST,
                    p.session.as_str(),
                    "org.freedesktop.portal.Session",
                )
                .await?;
                tokio::time::timeout(Duration::from_secs(3), proxy.call::<_, _, ()>("Close", &()))
                    .await??;
                Ok::<(), Box<dyn std::error::Error + Send + Sync>>(())
            });
        }
    }
}
pub struct Capture {
    pub child: Child,
}
impl Drop for Capture {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}
pub fn capture(
    fps: u32,
    log: &std::path::Path,
    cancelled: &AtomicBool,
) -> io::Result<(Capture, ChildStdout)> {
    let mut guard = portal()
        .lock()
        .map_err(|_| io::Error::other("portal lock poisoned"))?;
    if guard.is_none() {
        *guard = Some(Portal::create(cancelled).map_err(|e| io::Error::other(e.to_string()))?);
    }
    let p = guard.as_ref().unwrap();
    let fd: OwnedFd = p
        .runtime
        .block_on(async {
            let proxy = Proxy::new(&p.conn, DEST, PATH, SCREEN).await?;
            wait_for_portal(
                async {
                    Ok(proxy
                        .call::<_, _, OwnedFd>("OpenPipeWireRemote", &(&p.session, Options::new()))
                        .await?)
                },
                cancelled,
            )
            .await
        })
        .map_err(|e| io::Error::other(e.to_string()))?;
    let raw = fd.as_raw_fd();
    let target = p
        .serial
        .map(|s| format!("target-object={s}"))
        .unwrap_or_else(|| format!("path={}", p.node));
    let mut cmd = Command::new("gst-launch-1.0");
    cmd.args(["-q", "pipewiresrc"])
        .arg(format!("fd={raw}"))
        .arg(target)
        .arg("do-timestamp=true")
        .arg(format!("keepalive-time={}", (1000 / fps.max(1)).max(16)))
        .args([
            "!",
            "queue",
            "leaky=downstream",
            "max-size-buffers=2",
            "!",
            "videoconvert",
            "!",
            "videorate",
            "!",
        ])
        .arg(format!("video/x-raw,format=I420,framerate={fps}/1"))
        .args(["!", "y4menc", "!", "fdsink", "fd=1"])
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(
            std::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(log)?,
        );
    use std::os::unix::process::CommandExt;
    let parent = unsafe { libc::getpid() };
    unsafe {
        cmd.pre_exec(move || {
            if libc::fcntl(raw, libc::F_SETFD, 0) < 0
                || libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) != 0
            {
                return Err(io::Error::last_os_error());
            }
            if libc::getppid() != parent {
                return Err(io::Error::other("service exited"));
            }
            Ok(())
        });
    }
    let mut child = cmd.spawn()?;
    let stdout = child.stdout.take().unwrap();
    Ok((Capture { child }, stdout))
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn cancelled_authorization_does_not_wait_for_portal_timeout() {
        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .unwrap();
        let cancelled = AtomicBool::new(false);
        runtime.block_on(async {
            let pending = async { std::future::pending::<Result<()>>().await };
            let cancel = async {
                tokio::time::sleep(Duration::from_millis(20)).await;
                cancelled.store(true, Ordering::Release);
            };
            let (result, _) = tokio::join!(wait_for_portal(pending, &cancelled), cancel);
            assert_eq!(
                result.unwrap_err().to_string(),
                "portal authorization cancelled"
            );
        });
    }
    use std::{
        fs,
        io::{BufRead, BufReader, Read, Write},
        os::{fd::FromRawFd, unix::fs::PermissionsExt},
        sync::{Arc, Mutex},
    };
    #[derive(Clone)]
    struct Remote {
        events: Arc<Mutex<Vec<String>>>,
    }
    const SESSION: &str = "/org/freedesktop/portal/desktop/session/mock";
    async fn response(
        conn: &Connection,
        header: &zbus::message::Header<'_>,
        options: Options,
        result: Options,
    ) -> zbus::fdo::Result<OwnedObjectPath> {
        let sender = header
            .sender()
            .ok_or_else(|| zbus::fdo::Error::Failed("missing sender".into()))?
            .as_str()
            .trim_start_matches(':')
            .replace('.', "_");
        let token = options
            .get("handle_token")
            .and_then(|v| <&str>::try_from(v).ok())
            .ok_or_else(|| zbus::fdo::Error::Failed("missing handle token".into()))?;
        let path = format!("/org/freedesktop/portal/desktop/request/{sender}/{token}");
        conn.emit_signal(
            None::<&str>,
            path.as_str(),
            "org.freedesktop.portal.Request",
            "Response",
            &(0u32, result),
        )
        .await
        .map_err(|e| zbus::fdo::Error::Failed(e.to_string()))?;
        Ok(OwnedObjectPath::try_from(path).unwrap())
    }
    #[zbus::interface(name = "org.freedesktop.portal.RemoteDesktop")]
    impl Remote {
        async fn create_session(
            &self,
            options: Options,
            #[zbus(connection)] conn: &Connection,
            #[zbus(header)] header: zbus::message::Header<'_>,
        ) -> zbus::fdo::Result<OwnedObjectPath> {
            self.events.lock().unwrap().push("create".into());
            response(
                conn,
                &header,
                options,
                HashMap::from([(
                    "session_handle".into(),
                    zbus::zvariant::Value::from(OwnedObjectPath::try_from(SESSION).unwrap())
                        .try_to_owned()
                        .unwrap(),
                )]),
            )
            .await
        }
        async fn select_devices(
            &self,
            _session: OwnedObjectPath,
            options: Options,
            #[zbus(connection)] conn: &Connection,
            #[zbus(header)] header: zbus::message::Header<'_>,
        ) -> zbus::fdo::Result<OwnedObjectPath> {
            assert_eq!(
                options.get("types").and_then(|v| u32::try_from(v).ok()),
                Some(3)
            );
            self.events.lock().unwrap().push("devices".into());
            response(conn, &header, options, Options::new()).await
        }
        async fn start(
            &self,
            _session: OwnedObjectPath,
            _parent: String,
            options: Options,
            #[zbus(connection)] conn: &Connection,
            #[zbus(header)] header: zbus::message::Header<'_>,
        ) -> zbus::fdo::Result<OwnedObjectPath> {
            self.events.lock().unwrap().push("start".into());
            let size = zbus::zvariant::Value::from((1920i32, 1080i32))
                .try_to_owned()
                .unwrap();
            let props = HashMap::from([
                ("logical_size".to_owned(), size),
                ("pipewire-serial".to_owned(), OwnedValue::from(123456u64)),
            ]);
            let streams = zbus::zvariant::Value::from(vec![(42u32, props)])
                .try_to_owned()
                .unwrap();
            response(
                conn,
                &header,
                options,
                HashMap::from([("streams".into(), streams)]),
            )
            .await
        }
        fn notify_pointer_motion_absolute(
            &self,
            _session: OwnedObjectPath,
            _options: Options,
            node: u32,
            x: f64,
            y: f64,
        ) {
            assert_eq!(node, 42);
            assert!((0. ..1920.).contains(&x) && (0. ..1080.).contains(&y));
            self.events.lock().unwrap().push("motion".into());
        }
        fn notify_pointer_button(
            &self,
            _session: OwnedObjectPath,
            _options: Options,
            code: i32,
            state: u32,
        ) {
            self.events
                .lock()
                .unwrap()
                .push(format!("button:{code}:{state}"));
        }
        fn notify_keyboard_keysym(
            &self,
            _session: OwnedObjectPath,
            _options: Options,
            key: i32,
            state: u32,
        ) {
            self.events
                .lock()
                .unwrap()
                .push(format!("key:{key}:{state}"));
        }
        fn notify_pointer_axis(
            &self,
            _session: OwnedObjectPath,
            _options: Options,
            _x: f64,
            _y: f64,
        ) {
        }
    }
    struct Screen {
        events: Arc<Mutex<Vec<String>>>,
    }
    #[zbus::interface(name = "org.freedesktop.portal.ScreenCast")]
    impl Screen {
        async fn select_sources(
            &self,
            _session: OwnedObjectPath,
            options: Options,
            #[zbus(connection)] conn: &Connection,
            #[zbus(header)] header: zbus::message::Header<'_>,
        ) -> zbus::fdo::Result<OwnedObjectPath> {
            self.events.lock().unwrap().push("sources".into());
            response(conn, &header, options, Options::new()).await
        }
        fn open_pipe_wire_remote(&self, _session: OwnedObjectPath, _options: Options) -> OwnedFd {
            self.events.lock().unwrap().push("pipewire".into());
            let mut descriptors = [0; 2];
            assert_eq!(
                unsafe { libc::pipe2(descriptors.as_mut_ptr(), libc::O_CLOEXEC) },
                0
            );
            let mut output = unsafe { fs::File::from_raw_fd(descriptors[1]) };
            output.write_all(b"consented-video").unwrap();
            OwnedFd::from(unsafe { std::os::fd::OwnedFd::from_raw_fd(descriptors[0]) })
        }
    }
    struct Session {
        events: Arc<Mutex<Vec<String>>>,
    }
    #[zbus::interface(name = "org.freedesktop.portal.Session")]
    impl Session {
        fn close(&self) {
            self.events.lock().unwrap().push("close".into());
        }
    }
    #[test]
    #[ignore = "Requires isolated Linux D-Bus; run with --ignored --test-threads=1"]
    fn portal_scopes_video_and_input_and_passes_owned_fd() {
        let mut bus = Command::new("dbus-daemon")
            .args(["--session", "--nofork", "--print-address=1"])
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .unwrap();
        let mut address = String::new();
        BufReader::new(bus.stdout.take().unwrap())
            .read_line(&mut address)
            .unwrap();
        let address = address.trim().to_owned();
        struct BusGuard(Child);
        impl Drop for BusGuard {
            fn drop(&mut self) {
                let _ = self.0.kill();
                let _ = self.0.wait();
            }
        }
        let _bus = BusGuard(bus);
        let dir = std::env::temp_dir().join(format!("termish-portal-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let gst = dir.join("gst-launch-1.0");
        fs::write(&gst,"#!/bin/sh\nfor arg in \"$@\"; do case \"$arg\" in fd=*) FD=${arg#fd=}; break ;; esac; done\ncat /proc/self/fd/\"$FD\"\n").unwrap();
        fs::set_permissions(gst, fs::Permissions::from_mode(0o700)).unwrap();
        let events = Arc::new(Mutex::new(Vec::new()));
        let server_events = events.clone();
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let (stop_tx, stop_rx) = tokio::sync::oneshot::channel();
        let server_address = address.clone();
        let thread = std::thread::spawn(move || {
            let runtime = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .unwrap();
            runtime.block_on(async {
                let _conn = zbus::connection::Builder::address(server_address.as_str())
                    .unwrap()
                    .name(DEST)
                    .unwrap()
                    .serve_at(
                        PATH,
                        Remote {
                            events: server_events.clone(),
                        },
                    )
                    .unwrap()
                    .serve_at(
                        PATH,
                        Screen {
                            events: server_events.clone(),
                        },
                    )
                    .unwrap()
                    .serve_at(
                        SESSION,
                        Session {
                            events: server_events,
                        },
                    )
                    .unwrap()
                    .build()
                    .await
                    .unwrap();
                ready_tx.send(()).unwrap();
                let _ = stop_rx.await;
            });
        });
        ready_rx.recv().unwrap();
        let original_path = std::env::var("PATH").unwrap_or_default();
        std::env::set_var("DBUS_SESSION_BUS_ADDRESS", &address);
        std::env::set_var("PATH", format!("{}:{original_path}", dir.display()));
        let (capture, mut stdout) = capture(30, &dir.join("log"), &AtomicBool::new(false)).unwrap();
        let mut bytes = Vec::new();
        stdout.read_to_end(&mut bytes).unwrap();
        assert_eq!(bytes, b"consented-video");
        inject(&Control {
            kind: 1,
            x: 1.,
            y: 1.,
            extra: 0,
            text: "",
        })
        .unwrap();
        inject(&Control {
            kind: 4,
            x: 0.,
            y: 0.,
            extra: 0,
            text: "中🙂",
        })
        .unwrap();
        inject(&Control {
            kind: 5,
            x: 0.,
            y: 0.,
            extra: 48,
            text: "",
        })
        .unwrap();
        release().unwrap();
        drop(capture);
        close();
        let events = events.lock().unwrap();
        assert_eq!(
            &events[..5],
            &["create", "devices", "sources", "start", "pipewire"]
        );
        assert!(events.contains(&"button:272:0".into()));
        assert!(events.contains(&format!("key:{}:1", 0x01000000u32 | '中' as u32)));
        assert!(events.contains(&"key:65289:1".into()));
        assert_eq!(events.last().unwrap(), "close");
        drop(events);
        std::env::set_var("PATH", original_path);
        let _ = stop_tx.send(());
        thread.join().unwrap();
        fs::remove_dir_all(dir).unwrap();
    }
}
