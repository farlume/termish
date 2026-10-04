mod adapters;
mod history;
mod store;
mod util;
use serde_json::{json, Value};
use std::{
    fs, io,
    os::{
        fd::AsRawFd,
        unix::{fs::PermissionsExt, net::UnixStream as StdStream, process::CommandExt},
    },
    process::{Command, Stdio},
    sync::Arc,
    time::{Duration, Instant},
};
use tokio::{io::BufReader, net::UnixListener};
use util::*;
const VERSION: &str = env!("CARGO_PKG_VERSION");
const PROTOCOL: u8 = 3;
fn runtime() -> std::path::PathBuf {
    // Separate native ownership from pre-0.9 daemons; legacy records are not migrated.
    bridge_home().join("runtime/rust")
}
fn socket() -> std::path::PathBuf {
    runtime().join("agent.sock")
}
struct Lock(fs::File);
impl Lock {
    fn acquire(name: &str, nonblocking: bool) -> Result<Self> {
        private_dir(&runtime())?;
        let file = fs::OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(runtime().join(name))?;
        if unsafe {
            libc::flock(
                file.as_raw_fd(),
                libc::LOCK_EX | if nonblocking { libc::LOCK_NB } else { 0 },
            )
        } != 0
        {
            return Err(io::Error::last_os_error().into());
        }
        Ok(Self(file))
    }
}
impl Drop for Lock {
    fn drop(&mut self) {
        unsafe {
            libc::flock(self.0.as_raw_fd(), libc::LOCK_UN);
        }
    }
}
fn ready() -> bool {
    StdStream::connect(socket()).is_ok()
}
fn ensure_unlocked() -> Result<()> {
    if ready() {
        return Ok(());
    }
    let _ = fs::remove_file(socket());
    let log = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(runtime().join("agent.log"))?;
    let mut cmd = Command::new(std::env::current_exe()?);
    cmd.arg("serve")
        .stdin(Stdio::null())
        .stdout(log.try_clone()?)
        .stderr(log);
    unsafe {
        cmd.pre_exec(|| {
            if libc::setsid() < 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        });
    }
    let mut child = cmd.spawn()?;
    let end = Instant::now() + Duration::from_secs(5);
    while Instant::now() < end {
        if ready() {
            return Ok(());
        }
        if child.try_wait()?.is_some() {
            break;
        }
        std::thread::sleep(Duration::from_millis(30));
    }
    Err(format!(
        "Agent Bridge did not start; see {}",
        runtime().join("agent.log").display()
    )
    .into())
}
fn ensure() -> Result<()> {
    let _lock = Lock::acquire("agent.lock", false)?;
    ensure_unlocked()
}
fn restart() -> Result<()> {
    let _lock = Lock::acquire("agent.lock", false)?;
    // Ask the owner over its private socket instead of trusting a recycled PID.
    if let Ok(mut socket) = StdStream::connect(socket()) {
        use std::io::Write;
        socket.write_all(b"{\"id\":0,\"method\":\"system.shutdown\"}\n")?;
        let end = Instant::now() + Duration::from_secs(6);
        while Instant::now() < end && ready() {
            std::thread::sleep(Duration::from_millis(50));
        }
        if ready() {
            return Err("old daemon is still stopping".into());
        }
    }
    ensure_unlocked()
}
fn status() -> Value {
    json!({"version":VERSION,"protocolVersion":PROTOCOL,"runtime":"rust","running":ready(),"daemonVersion":fs::read_to_string(runtime().join("agent.version")).ok().map(|s|s.trim().to_owned())})
}
async fn serve() -> Result<()> {
    let _instance = Lock::acquire("agent.instance.lock", true)?;
    let _ = fs::remove_file(socket());
    let listener = UnixListener::bind(socket())?;
    fs::set_permissions(socket(), fs::Permissions::from_mode(0o600))?;
    fs::write(runtime().join("agent.pid"), std::process::id().to_string())?;
    fs::write(runtime().join("agent.version"), VERSION)?;
    let store = store::Store::open()?;
    let stop = Arc::new(tokio::sync::Notify::new());
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    let mut interrupt = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::interrupt())?;
    loop {
        tokio::select! {
            _=term.recv()=>break, _=interrupt.recv()=>break, _=stop.notified()=>break,
            accepted=listener.accept()=> {
                let (socket,_)=accepted?; let store=store.clone(); let stop=stop.clone();
                tokio::spawn(async move {
                    let (reader,mut writer)=socket.into_split(); let mut reader=BufReader::new(reader);
                    let mut events=store.lock().await.events.subscribe();
                    loop {
                        tokio::select! {
                            read=line(&mut reader,MAX_LINE)=> {
                                let request=match read { Ok(Some(v))=>v,Ok(None)=>break,Err(e)=> { let _=send(&mut writer,&error(Value::Null,&e.to_string(),"invalid_request")).await; break; } };
                                let id=request["id"].clone(); let method=text(&request,"method");
                                let params=if request["params"].is_null() {json!({})} else {request["params"].clone()};
                                let response=if !params.is_object() {error(id,"params must be an object","invalid_request")}
                                else if method=="system.hello" {json!({"id":id,"result":{"version":VERSION,"protocolVersion":PROTOCOL,"runtime":"rust"}})}
                                else if method=="system.shutdown" { stop.notify_one(); json!({"id":id,"result":{"ok":true}}) }
                                else {match store::dispatch(store.clone(),method,&params).await {Ok(v)=>json!({"id":id,"result":v}),Err(e)=>{let mut s=Value::String(e.to_string());redact(&mut s,&secrets(&params));error(id,s.as_str().unwrap(),"request_failed")}}};
                                if send(&mut writer,&response).await.is_err() {break;}
                            },
                            event=events.recv()=>match event {Ok(v)=>if send(&mut writer,&v).await.is_err(){break;},Err(tokio::sync::broadcast::error::RecvError::Lagged(_))=>break,Err(_)=>break}
                        }
                    }
                });
            }
        }
    }
    store::shutdown(store).await;
    drop(listener);
    let _ = fs::remove_file(socket());
    let _ = fs::remove_file(runtime().join("agent.pid"));
    let _ = fs::remove_file(runtime().join("agent.version"));
    Ok(())
}
async fn relay() -> Result<()> {
    tokio::task::spawn_blocking(ensure).await??;
    let socket = tokio::net::UnixStream::connect(socket()).await?;
    let (mut reader, mut writer) = socket.into_split();
    let input = async {
        let result = tokio::io::copy(&mut tokio::io::stdin(), &mut writer).await;
        use tokio::io::AsyncWriteExt;
        let _ = writer.shutdown().await;
        result
    };
    let output = async {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        let mut stdout = tokio::io::stdout();
        let mut buf = [0; 65536];
        loop {
            let n = reader.read(&mut buf).await?;
            if n == 0 {
                break;
            }
            stdout.write_all(&buf[..n]).await?;
            stdout.flush().await?;
        }
        Ok::<(), io::Error>(())
    };
    tokio::select! {r=input=>{r?;},r=output=>{r?;}};
    Ok(())
}
#[tokio::main]
async fn main() -> Result<()> {
    unsafe {
        libc::umask(0o077);
    }
    match std::env::args().nth(1).as_deref() {
        Some("serve") => serve().await,
        Some("connect") => relay().await,
        Some("restart") => {
            tokio::task::spawn_blocking(restart).await??;
            println!("{}", status());
            Ok(())
        }
        Some("ensure-running") => {
            tokio::task::spawn_blocking(ensure).await??;
            println!("{}", status());
            Ok(())
        }
        Some("status") => {
            println!("{}", status());
            Ok(())
        }
        Some("--licenses") => {
            print!(
                "{}",
                include_str!("../../../LICENSES/TermishScreen-Rust.txt")
            );
            Ok(())
        }
        Some("--version") => {
            println!("{VERSION}");
            Ok(())
        }
        _ => Err(
            "usage: termish-agent {serve|connect|restart|ensure-running|status|--version}".into(),
        ),
    }
}
