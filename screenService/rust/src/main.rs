mod config;
#[cfg(target_os = "linux")]
mod desktop;
#[path = "menu/strings.rs"]
mod desktop_strings;
mod encoding;
mod management;
#[cfg(target_os = "macos")]
mod menu;
#[cfg(any(target_os = "macos", test))]
mod permission;
mod platform;
#[cfg(any(target_os = "linux", test))]
mod process;
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
    #[cfg(target_os = "linux")]
    let (mut manage, mut install_desktop, mut check_encoder, mut control) =
        (false, false, false, None);
    while let Some(arg) = args.next() {
        match arg.as_str() {
            #[cfg(target_os = "linux")]
            "--manage" => manage = true,
            #[cfg(target_os = "linux")]
            "--install-desktop" => install_desktop = true,
            #[cfg(target_os = "linux")]
            "--check-encoder" => check_encoder = true,
            #[cfg(target_os = "linux")]
            "--control" => {
                control = Some(
                    match args.next().ok_or("control action missing")?.as_str() {
                        "status" => 0,
                        "toggle" => 1,
                        "disconnect" => 2,
                        "restart" => 3,
                        "quit" => 4,
                        "pause" => 5,
                        "resume" => 6,
                        _ => return Err("unknown control action".into()),
                    },
                )
            }
            "--version" => {
                println!("{}", config::VERSION);
                return Ok(());
            }
            "--help" => {
                println!("Termish screen service\n--version --config FILE --port PORT --ffmpeg FILE --check-config\n--write-config FILE PORT FFMPEG\n--write-launch-agent FILE\n--display-state\nLinux: --install-desktop --manage --check-encoder --control {{status|pause|resume|toggle|disconnect|restart|quit}}");
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
                let plist = format!("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<plist version=\"1.0\"><dict>\n<key>Label</key><string>dev.termish.screen</string>\n<key>ProgramArguments</key><array><string>{escaped}</string></array>\n<key>RunAtLoad</key><true/>\n<key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>\n</dict></plist>\n");
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
    #[cfg(target_os = "linux")]
    {
        if check_encoder {
            let backend = encoding::select(
                &config.ffmpeg,
                config.encoder,
                &std::sync::atomic::AtomicBool::new(false),
            );
            println!(
                "{}",
                serde_json::json!({"requested":config.encoder,"selected":backend.codec()})
            );
            return Ok(());
        }
        if install_desktop {
            desktop::install()?;
            return Ok(());
        }
        if let Some(action) = control {
            let result = desktop::control(&config, if action == 0 { None } else { Some(action) })?;
            if !result.is_empty() {
                println!("{result}");
            }
            return Ok(());
        }
        if manage {
            desktop::manage(&config)?;
            return Ok(());
        }
    }
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
    let management = std::sync::Arc::new(management::Management::default());
    #[cfg(target_os = "macos")]
    let outcome = if config.menu_bar {
        menu::run(config, management)?
    } else {
        service::run(config, management)?
    };
    #[cfg(target_os = "linux")]
    let outcome = desktop::run(config, management)?;
    if outcome == management::Outcome::Restart {
        let managed = env::var("XPC_SERVICE_NAME").is_ok_and(|name| {
            name == "dev.termish.screen" || name.starts_with("dev.termish.screen.")
        }) || env::var("TERMISH_SYSTEMD_SERVICE").is_ok_and(|value| value == "1");
        if managed {
            // launchd/systemd restart unsuccessful exits, but leave menu Quit alone.
            std::process::exit(1);
        }
        std::process::Command::new(env::current_exe()?)
            .args(env::args_os().skip(1))
            .spawn()?;
    }
    Ok(())
}
fn main() {
    if let Err(error) = execute() {
        eprintln!("screen-service: {error}");
        std::process::exit(2);
    }
}
