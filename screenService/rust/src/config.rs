use serde::{Deserialize, Serialize};
use std::{
    env, fs, io,
    path::{Path, PathBuf},
};

pub const VERSION: &str = env!("RELAY_VERSION");

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Settings {
    pub port: Option<u16>,
    pub ffmpeg: Option<String>,
    pub token_file: Option<String>,
    pub log_file: Option<String>,
    pub encoder_pid_file: Option<String>,
    pub stream_config_file: Option<String>,
}

#[derive(Clone, Serialize)]
pub struct Config {
    pub version: &'static str,
    pub port: u16,
    pub ffmpeg: PathBuf,
    pub token_file: PathBuf,
    pub log_file: PathBuf,
    pub encoder_pid_file: PathBuf,
    pub stream_config_file: PathBuf,
    #[serde(skip)]
    pub token: [u8; 64],
}

fn invalid(message: &str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

fn expand(path: &str) -> io::Result<PathBuf> {
    if path.trim().is_empty() || path.contains('\0') {
        return Err(invalid("empty path or NUL"));
    }
    if let Some(suffix) = path.strip_prefix("~/") {
        Ok(PathBuf::from(env::var_os("HOME").ok_or_else(|| invalid("HOME missing"))?).join(suffix))
    } else {
        Ok(path.into())
    }
}

pub fn executable(path: &str) -> io::Result<PathBuf> {
    use std::os::unix::fs::PermissionsExt;
    let expanded = expand(path)?;
    let candidates = if expanded.components().count() > 1 || expanded.is_absolute() {
        vec![expanded]
    } else {
        env::split_paths(&env::var_os("PATH").unwrap_or_default())
            .map(|p| p.join(&expanded))
            .collect()
    };
    candidates
        .into_iter()
        .find(|p| {
            fs::metadata(p)
                .map(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
                .unwrap_or(false)
        })
        .ok_or_else(|| invalid("ffmpeg executable unavailable"))
}

impl Config {
    pub fn load(
        path: Option<&Path>,
        port: Option<u16>,
        ffmpeg: Option<String>,
    ) -> io::Result<Self> {
        let default_path = env::current_exe()?.with_file_name("screen-service.json");
        let selected = path.unwrap_or(&default_path);
        let mut settings: Settings = if path.is_some() || selected.exists() {
            serde_json::from_slice(&fs::read(selected)?).map_err(|e| invalid(&e.to_string()))?
        } else {
            Settings::default()
        };
        let port = port
            .or(settings.port)
            .unwrap_or(env!("SCREEN_PORT").parse().unwrap());
        if !(1..=65533).contains(&port) {
            return Err(invalid("port must be between 1 and 65533"));
        }
        let token_file = expand(
            settings
                .token_file
                .as_deref()
                .unwrap_or("~/.termish-screen.token"),
        )?;
        let bytes = fs::read(&token_file)?;
        let bytes = bytes.trim_ascii();
        if bytes.len() != 64 || !bytes.iter().all(u8::is_ascii_hexdigit) {
            return Err(invalid("invalid screen auth token"));
        }
        Ok(Self {
            version: VERSION,
            port,
            ffmpeg: executable(
                &ffmpeg
                    .or(settings.ffmpeg.take())
                    .unwrap_or_else(|| "ffmpeg".into()),
            )?,
            token: bytes.try_into().unwrap(),
            token_file,
            log_file: expand(settings.log_file.as_deref().unwrap_or(
                if cfg!(target_os = "macos") {
                    "~/Library/Logs/termish-screen.err"
                } else {
                    "~/.termish-screen.err"
                },
            ))?,
            encoder_pid_file: expand(
                settings
                    .encoder_pid_file
                    .as_deref()
                    .unwrap_or("~/.termish-screen-ffmpeg.pid"),
            )?,
            stream_config_file: expand(
                settings
                    .stream_config_file
                    .as_deref()
                    .unwrap_or("~/.termish-screen.conf"),
            )?,
        })
    }
}

#[derive(Debug)]
pub struct StreamSettings {
    pub fps: u32,
    pub scale: String,
}

impl StreamSettings {
    pub fn read(path: &Path) -> Self {
        let mut cfg = Self {
            fps: 30,
            scale: "1280:-2".into(),
        };
        for line in fs::read_to_string(path).unwrap_or_default().lines() {
            if let Some(value) = line.strip_prefix("fps=") {
                cfg.fps = value.parse::<u32>().unwrap_or(30).clamp(1, 120);
            }
            if let Some(value) = line.strip_prefix("scale=") {
                // Filter syntax is deliberately restricted to the mobile presets.
                if value == "native"
                    || value
                        .split_once(':')
                        .map(|(w, h)| {
                            w.parse::<u32>()
                                .map(|w| (2..=16384).contains(&w))
                                .unwrap_or(false)
                                && h == "-2"
                        })
                        .unwrap_or(false)
                {
                    cfg.scale = value.into();
                }
            }
        }
        cfg
    }
    #[cfg(test)]
    pub fn args(&self, bitrate_scale: u32) -> Vec<String> {
        self.args_for_capture(bitrate_scale, false)
    }
    pub fn args_for_capture(&self, bitrate_scale: u32, wayland: bool) -> Vec<String> {
        let base = if self.scale == "native" || self.scale.starts_with("2560") {
            24
        } else if self.scale.starts_with("1920") {
            10
        } else if self.scale.starts_with("1280") {
            6
        } else {
            4
        };
        let mbps = if self.fps >= 120 {
            base * 2
        } else if self.fps >= 60 {
            base * 3 / 2
        } else {
            base
        };
        let kbps = (mbps * 1000 * bitrate_scale.clamp(40, 100) / 100).max(1000);
        let gop = (self.fps / 2).max(15).to_string();
        let filter = if wayland {
            if self.scale == "native" {
                "null".into()
            } else {
                format!("scale={}:flags=lanczos", self.scale)
            }
        } else if self.scale == "native" {
            format!("fps={}", self.fps)
        } else {
            format!("fps={},scale={}:flags=lanczos", self.fps, self.scale)
        };
        let mut args: Vec<String> = if wayland {
            vec![
                "-f".into(),
                "yuv4mpegpipe".into(),
                "-i".into(),
                "pipe:0".into(),
            ]
        } else if cfg!(target_os = "macos") {
            [
                "-f",
                "avfoundation",
                "-capture_cursor",
                "1",
                "-pixel_format",
                "uyvy422",
                "-framerate",
                &self.fps.to_string(),
                "-i",
                "1:none",
            ]
            .map(str::to_owned)
            .to_vec()
        } else {
            vec![
                "-f".into(),
                "x11grab".into(),
                "-framerate".into(),
                self.fps.to_string(),
                "-i".into(),
                format!("{}.0", env::var("DISPLAY").unwrap_or_else(|_| ":0".into())),
            ]
        };
        let mut add = |items: &[&str]| args.extend(items.iter().map(|s| s.to_string()));
        add(&["-hide_banner", "-loglevel", "error", "-vf", &filter]);
        let rate = format!("{kbps}k");
        let buffer = format!("{}k", (kbps / 2).max(500));
        if cfg!(target_os = "macos") {
            add(&[
                "-c:v",
                "h264_videotoolbox",
                "-realtime",
                "1",
                "-b:v",
                &rate,
                "-maxrate",
                &rate,
                "-bufsize",
                &buffer,
                "-g",
                &gop,
                "-pix_fmt",
                "yuv420p",
                "-bsf:v",
                "dump_extra=freq=keyframe,h264_metadata=aud=insert",
            ]);
        } else {
            let crf = if base >= 24 {
                "16"
            } else if base >= 10 {
                "18"
            } else if base >= 6 {
                "20"
            } else {
                "22"
            };
            let threads = if self.fps >= 120 || base >= 24 {
                "4"
            } else if self.fps >= 60 || base >= 10 {
                "2"
            } else {
                "1"
            };
            add(&[
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-crf",
                crf,
                "-maxrate",
                &rate,
                "-bufsize",
                &buffer,
                "-x264opts",
                &format!("sliced-threads=0:rc-lookahead=0:sync-lookahead=0:keyint={gop}:aud=1"),
                "-pix_fmt",
                "yuv420p",
                "-g",
                &gop,
                "-bf",
                "0",
                "-threads",
                threads,
                "-bsf:v",
                "dump_extra=freq=keyframe",
            ]);
        }
        add(&["-f", "h264", "-"]);
        args
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn invalid_settings_and_stream_injection_are_rejected() {
        assert!(serde_json::from_str::<Settings>("{\"port\":true}").is_err());
        assert!(serde_json::from_str::<Settings>("{\"unknown\":1}").is_err());
        let cfg = StreamSettings {
            fps: 120,
            scale: "2560:-2".into(),
        };
        let args = cfg.args(75);
        assert!(args.contains(&"36000k".into()));
        assert!(args.contains(&"60".into()));
    }
}
