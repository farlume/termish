mod config;
#[cfg(any(target_os = "macos", test))]
mod permission;
mod platform;
mod protocol;
mod service;
use std::{env, fs, io, path::PathBuf, sync::atomic::Ordering};

extern "C" fn shutdown(_: libc::c_int) {
    service::SHUTDOWN.store(true, Ordering::Release);
}

fn execute() -> Result<(), Box<dyn std::error::Error>> {
    let mut args = env::args().skip(1);
    let mut path = None;
    let mut port = None;
    let mut ffmpeg = None;
    let mut check = false;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--version" => {
                println!("{}", config::VERSION);
                return Ok(());
            }
            "--help" => {
                println!("Termish screen service\n--version --config FILE --port PORT --ffmpeg FILE --check-config\n--write-config FILE PORT FFMPEG\n--write-launch-agent FILE\n--display-state");
                return Ok(());
            }
            "--display-state" => {
                platform::display_state();
                return Ok(());
            }
            "--licenses" => {
                print!(
                    "{}",
                    include_str!("../../../LICENSES/TermishScreen-Rust.txt")
                );
                return Ok(());
            }
            "--write-launch-agent" => {
                use std::io::Write;
                use std::os::unix::fs::OpenOptionsExt;
                let file = PathBuf::from(args.next().ok_or("LaunchAgent path missing")?);
                if args.next().is_some() {
                    return Err("unexpected arguments".into());
                }
                let executable = env::current_exe()?;
                let escaped = executable
                    .to_str()
                    .ok_or("non-UTF-8 executable path")?
                    .replace('&', "&amp;")
                    .replace('<', "&lt;")
                    .replace('>', "&gt;")
                    .replace('"', "&quot;")
                    .replace('\'', "&apos;");
                let plist = format!("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\"><dict>\n<key>Label</key><string>dev.termish.screen</string>\n<key>ProgramArguments</key><array><string>{escaped}</string></array>\n<key>RunAtLoad</key><true/>\n<key>KeepAlive</key><true/>\n</dict></plist>\n");
                let mut output = fs::OpenOptions::new()
                    .write(true)
                    .create(true)
                    .truncate(true)
                    .mode(0o600)
                    .open(file)?;
                output.write_all(plist.as_bytes())?;
                return Ok(());
            }
            "--config" => {
                path = Some(PathBuf::from(
                    args.next().ok_or("--config requires a path")?,
                ))
            }
            "--port" => {
                port = Some(
                    args.next()
                        .ok_or("--port requires a port")?
                        .parse::<u16>()?,
                )
            }
            "--ffmpeg" => ffmpeg = Some(args.next().ok_or("--ffmpeg requires a path")?),
            "--check-config" => check = true,
            "--write-config" => {
                use std::io::Write;
                use std::os::unix::fs::OpenOptionsExt;
                let file = PathBuf::from(args.next().ok_or("configuration path missing")?);
                let port = args
                    .next()
                    .ok_or("configuration port missing")?
                    .parse::<u16>()?;
                let ffmpeg = args.next().ok_or("configuration encoder missing")?;
                if args.next().is_some() {
                    return Err("unexpected arguments".into());
                }
                // Keep user-provided token/log/PID/quality paths on reinstall.
                let mut value = if file.exists() {
                    let _: config::Settings = serde_json::from_slice(&fs::read(&file)?)?;
                    serde_json::from_slice::<serde_json::Value>(&fs::read(&file)?)?
                } else {
                    serde_json::json!({})
                };
                value["port"] = port.into();
                value["ffmpeg"] = ffmpeg.into();
                let temp = file.with_extension(format!("json.tmp.{}", std::process::id()));
                let result = (|| -> io::Result<()> {
                    let mut output = fs::OpenOptions::new()
                        .write(true)
                        .create_new(true)
                        .mode(0o600)
                        .open(&temp)?;
                    output.write_all(serde_json::to_string(&value)?.as_bytes())?;
                    output.sync_all()?;
                    config::Config::load(Some(&temp), None, None)?;
                    fs::rename(&temp, &file)
                })();
                if result.is_err() {
                    let _ = fs::remove_file(temp);
                }
                result?;
                return Ok(());
            }
            _ => return Err(format!("unknown argument: {arg}").into()),
        }
    }
    let config = config::Config::load(path.as_deref(), port, ffmpeg)?;
    if check {
        let mut summary = serde_json::to_value(&config)?;
        summary["token_ready"] = true.into();
        println!("{summary}");
        return Ok(());
    }
    unsafe {
        libc::signal(libc::SIGTERM, shutdown as *const () as libc::sighandler_t);
        libc::signal(libc::SIGINT, shutdown as *const () as libc::sighandler_t);
    }
    service::run(config)?;
    Ok(())
}
fn main() {
    if let Err(error) = execute() {
        eprintln!("screen-service: {error}");
        std::process::exit(2);
    }
}
