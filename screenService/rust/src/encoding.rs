use serde::{Deserialize, Serialize};
use std::path::PathBuf;
#[cfg(any(target_os = "linux", test))]
use std::{
    path::Path,
    process::Command,
    sync::atomic::{AtomicBool, Ordering},
    time::{Duration, Instant},
};

#[derive(Clone, Copy, Debug, Default, Deserialize, Serialize, PartialEq)]
#[serde(rename_all = "snake_case")]
pub enum Preference {
    #[default]
    Auto,
    Software,
    Nvenc,
    Qsv,
    Vaapi,
}

#[derive(Clone, Debug, PartialEq)]
#[cfg_attr(not(any(target_os = "linux", test)), allow(dead_code))]
pub enum Backend {
    Software,
    Nvenc,
    Qsv(PathBuf),
    Vaapi(PathBuf),
}
impl Backend {
    pub fn codec(&self) -> &'static str {
        match self {
            Self::Software => "libx264",
            Self::Nvenc => "h264_nvenc",
            Self::Qsv(_) => "h264_qsv",
            Self::Vaapi(_) => "h264_vaapi",
        }
    }
    pub fn hardware(&self) -> bool {
        *self != Self::Software
    }
    pub fn device_args(&self) -> Vec<String> {
        match self {
            Self::Qsv(path) => vec![
                "-init_hw_device".into(),
                format!("vaapi=va:{}", path.display()),
                "-init_hw_device".into(),
                "qsv=hw@va".into(),
                "-filter_hw_device".into(),
                "hw".into(),
            ],
            Self::Vaapi(path) => vec!["-vaapi_device".into(), path.display().to_string()],
            _ => Vec::new(),
        }
    }
    pub fn filter(&self, base: &str) -> String {
        match self {
            Self::Qsv(_) => format!("{base},format=nv12,hwupload=extra_hw_frames=16"),
            Self::Vaapi(_) => format!("{base},format=nv12,hwupload"),
            _ => base.to_owned(),
        }
    }
    pub fn codec_args(&self, rate: &str, buffer: &str, gop: &str) -> Vec<String> {
        let mut args = vec![
            "-c:v",
            self.codec(),
            "-b:v",
            rate,
            "-maxrate",
            rate,
            "-bufsize",
            buffer,
            "-g",
            gop,
            "-bf",
            "0",
        ];
        match self {
            Self::Nvenc => args.extend([
                "-preset",
                "p1",
                "-tune",
                "ull",
                "-rc",
                "cbr",
                "-zerolatency",
                "1",
                "-rc-lookahead",
                "0",
                "-pix_fmt",
                "yuv420p",
            ]),
            Self::Qsv(_) => args.extend(["-preset", "veryfast", "-async_depth", "1"]),
            Self::Vaapi(_) => (),
            Self::Software => (),
        }
        args.extend([
            "-bsf:v",
            "dump_extra=freq=keyframe,h264_metadata=aud=insert",
        ]);
        args.into_iter().map(str::to_owned).collect()
    }
}

#[cfg(any(target_os = "linux", test))]
pub fn select(ffmpeg: &Path, preference: Preference, cancelled: &AtomicBool) -> Backend {
    if preference == Preference::Software {
        return Backend::Software;
    }
    let started = Instant::now();
    let available = crate::process::output(
        Command::new(ffmpeg).args(["-hide_banner", "-encoders"]),
        Duration::from_secs(1),
        Some(cancelled),
    )
    .unwrap_or_default();
    if !available.0 {
        return Backend::Software;
    }
    let mut devices: Vec<_> = std::fs::read_dir("/dev/dri")
        .into_iter()
        .flatten()
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| {
            p.file_name()
                .is_some_and(|n| n.to_string_lossy().starts_with("renderD"))
        })
        .collect();
    devices.sort();
    devices.truncate(4);
    let mut candidates = Vec::new();
    if matches!(preference, Preference::Auto | Preference::Nvenc) {
        candidates.push(Backend::Nvenc);
    }
    // Try both APIs on each device before moving to another GPU. A hung QSV
    // driver must not consume the whole budget before its VAAPI alternative.
    for device in devices {
        if matches!(preference, Preference::Auto | Preference::Qsv) {
            candidates.push(Backend::Qsv(device.clone()));
        }
        if matches!(preference, Preference::Auto | Preference::Vaapi) {
            candidates.push(Backend::Vaapi(device));
        }
    }
    for backend in candidates {
        if cancelled.load(Ordering::Acquire) || started.elapsed() >= Duration::from_secs(5) {
            break;
        }
        if !available
            .1
            .lines()
            .any(|line| line.split_whitespace().nth(1) == Some(backend.codec()))
        {
            continue;
        }
        let mut command = Command::new(ffmpeg);
        command
            .args(["-hide_banner", "-loglevel", "error"])
            .args(backend.device_args())
            .args([
                "-f",
                "lavfi",
                "-i",
                "color=size=320x180:rate=30",
                "-frames:v",
                "1",
                "-vf",
            ])
            .arg(backend.filter("null"))
            .args(backend.codec_args("2000k", "1000k", "15"))
            .args(["-f", "h264", "-"]);
        if crate::process::output(&mut command, Duration::from_secs(1), Some(cancelled))
            .is_ok_and(|r| r.0)
        {
            return backend;
        }
    }
    Backend::Software
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn hardware_frames_are_uploaded_to_the_selected_device_and_remain_low_latency_h264() {
        let qsv = Backend::Qsv("/dev/dri/renderD129".into());
        assert!(qsv
            .device_args()
            .contains(&"vaapi=va:/dev/dri/renderD129".into()));
        assert!(qsv
            .filter("scale=1280:-2")
            .ends_with("hwupload=extra_hw_frames=16"));
        for backend in [
            qsv,
            Backend::Vaapi("/dev/dri/renderD128".into()),
            Backend::Nvenc,
        ] {
            let args = backend.codec_args("6000k", "3000k", "15");
            assert!(args.windows(2).any(|a| a == ["-bf", "0"]));
            assert!(args.iter().any(|a| a.contains("aud=insert")));
        }
    }
    #[test]
    fn unavailable_or_cancelled_hardware_uses_software_without_requiring_a_gpu() {
        assert_eq!(
            select(
                Path::new("/nonexistent/ffmpeg"),
                Preference::Auto,
                &AtomicBool::new(false)
            ),
            Backend::Software
        );
        assert_eq!(
            select(
                Path::new("/nonexistent/ffmpeg"),
                Preference::Software,
                &AtomicBool::new(false)
            ),
            Backend::Software
        );
    }
    #[test]
    fn advertised_codec_must_initialize_and_probing_is_cancellable() {
        use std::os::unix::fs::PermissionsExt;
        let directory =
            std::env::temp_dir().join(format!("termish-encoder-probe-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        let ffmpeg = directory.join("ffmpeg");
        std::fs::write(&ffmpeg, "#!/bin/sh\ncase \"$*\" in *-encoders*) printf ' V..... h264_nvenc test\\n';; *) exit 1;; esac\n").unwrap();
        std::fs::set_permissions(&ffmpeg, std::fs::Permissions::from_mode(0o700)).unwrap();
        assert_eq!(
            select(&ffmpeg, Preference::Auto, &AtomicBool::new(false)),
            Backend::Software
        );
        std::fs::write(&ffmpeg, "#!/bin/sh\ncase \"$*\" in *-encoders*) printf ' V..... h264_nvenc test\\n';; *) exec sleep 10;; esac\n").unwrap();
        let cancelled = AtomicBool::new(false);
        let started = Instant::now();
        std::thread::scope(|scope| {
            scope.spawn(|| {
                std::thread::sleep(Duration::from_millis(50));
                cancelled.store(true, Ordering::Release);
            });
            assert_eq!(
                select(&ffmpeg, Preference::Auto, &cancelled),
                Backend::Software
            );
        });
        assert!(started.elapsed() < Duration::from_secs(1));
        std::fs::remove_dir_all(directory).unwrap();
    }
}
