use serde::{Deserialize, Serialize};
use std::{
    env, fs, io,
    path::{Path, PathBuf},
};

pub const VERSION: &str = env!("RELAY_VERSION");

#[derive(Clone, Copy, Default, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum CaptureSource {
    #[default]
    Desktop,
    TestPattern,
}

#[derive(Default, Deserialize)]
#[serde(deny_unknown_fields)]
pub struct Settings {
    #[serde(default)]
    pub capture_source: CaptureSource,
    pub menu_bar: Option<bool>,
    #[serde(default)]
    pub encoder: crate::encoding::Preference,
    pub port: Option<u16>,
    pub ffmpeg: Option<String>,
    pub token_file: Option<String>,
    pub log_file: Option<String>,
    pub encoder_pid_file: Option<String>,
    pub stream_config_file: Option<String>,
}

#[derive(Clone, Serialize)]
pub struct Config {
    pub capture_source: CaptureSource,
    pub menu_bar: bool,
    pub encoder: crate::encoding::Preference,
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

pub fn state_directory(executable: &Path) -> PathBuf {
    let directory = executable.parent().unwrap_or(Path::new("."));
    // The bundled agent keeps the existing account's config/token/log paths.
    // Never put mutable state inside the signed application bundle.
    if directory.file_name().is_some_and(|name| name == "MacOS") {
        if let Some(contents) = directory
            .parent()
            .filter(|p| p.file_name().is_some_and(|name| name == "Contents"))
        {
            if let Some(bundle) = contents
                .parent()
                .filter(|p| p.extension().is_some_and(|ext| ext == "app"))
            {
                return bundle.parent().unwrap_or(directory).to_owned();
            }
        }
    }
    directory.to_owned()
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
        let default_path = state_directory(&env::current_exe()?).join("screen-service.json");
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
            capture_source: settings.capture_source,
            encoder: settings.encoder,
            menu_bar: settings
                .menu_bar
                .unwrap_or(settings.capture_source == CaptureSource::Desktop),
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
    #[cfg(test)]
    pub fn args_for_capture(&self, bitrate_scale: u32, wayland: bool) -> Vec<String> {
        self.args_for_encoder(bitrate_scale, wayland, &crate::encoding::Backend::Software)
    }
    pub fn args_for_encoder(
        &self,
        bitrate_scale: u32,
        wayland: bool,
        backend: &crate::encoding::Backend,
    ) -> Vec<String> {
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
        if !cfg!(target_os = "macos") {
            args.splice(0..0, backend.device_args());
        }
        let filter = if cfg!(target_os = "macos") {
            filter
        } else {
            backend.filter(&filter)
        };
        args.extend(["-hide_banner", "-loglevel", "error", "-vf", &filter].map(str::to_owned));
        let rate = format!("{kbps}k");
        let buffer = format!("{}k", (kbps / 2).max(500));
        if !cfg!(target_os = "macos") && backend.hardware() {
            args.extend(backend.codec_args(&rate, &buffer, &gop));
            args.extend(["-f", "h264", "-"].map(str::to_owned));
            return args;
        }
        let mut add = |items: &[&str]| args.extend(items.iter().map(|s| s.to_string()));
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

    pub fn args_for_test_pattern(&self) -> Vec<String> {
        // Diagnostic video does not read any desktop or inject input. Keep it
        // separate from desktop capture rather than bypassing TCC in tests.
        [
            "-hide_banner",
            "-loglevel",
            "error",
            "-re",
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=160x90:rate=30",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-tune",
            "zerolatency",
            "-pix_fmt",
            "yuv420p",
            "-x264-params",
            "aud=1:repeat-headers=1",
            "-g",
            "15",
            "-f",
            "h264",
            "-",
        ]
        .map(str::to_owned)
        .to_vec()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn bundle_state_stays_outside_signed_contents() {
        assert_eq!(state_directory(Path::new("/Users/test/Library/Application Support/termish/Termish Helper.app/Contents/MacOS/Termish Helper")), PathBuf::from("/Users/test/Library/Application Support/termish"));
        assert_eq!(
            state_directory(Path::new("/tmp/Contents/MacOS/service")),
            PathBuf::from("/tmp/Contents/MacOS")
        );
        assert_eq!(
            state_directory(Path::new("/tmp/screen-service")),
            PathBuf::from("/tmp")
        );
    }
    #[test]
    fn invalid_settings_and_stream_injection_are_rejected() {
        assert!(serde_json::from_str::<Settings>("{\"port\":true}").is_err());
        assert!(serde_json::from_str::<Settings>("{\"unknown\":1}").is_err());
        assert!(matches!(
            serde_json::from_str::<Settings>("{}")
                .unwrap()
                .capture_source,
            CaptureSource::Desktop
        ));
        assert!(serde_json::from_str::<Settings>("{\"capture_source\":\"bypass\"}").is_err());
        let cfg = StreamSettings {
            fps: 120,
            scale: "2560:-2".into(),
        };
        let args = cfg.args(75);
        assert!(args.contains(&"36000k".into()));
        assert!(args.contains(&"60".into()));
    }
}
