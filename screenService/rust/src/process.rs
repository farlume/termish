//! Bounded helper processes never inherit the connection's stdin or leave a child running.
use std::{
    fs,
    io::{self, Read},
    os::unix::fs::OpenOptionsExt,
    process::{Command, Stdio},
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
    thread,
    time::{Duration, Instant},
};

static SERIAL: AtomicU64 = AtomicU64::new(0);
pub fn output(
    command: &mut Command,
    timeout: Duration,
    cancelled: Option<&AtomicBool>,
) -> io::Result<(bool, String)> {
    let path = std::env::temp_dir().join(format!(
        "termish-helper-{}-{}",
        std::process::id(),
        SERIAL.fetch_add(1, Ordering::Relaxed)
    ));
    let file = fs::OpenOptions::new()
        .read(true)
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&path)?;
    // Unlink immediately; output remains accessible through this private file descriptor.
    fs::remove_file(path)?;
    let mut child = command
        .stdin(Stdio::null())
        .stdout(file.try_clone()?)
        .stderr(file.try_clone()?)
        .spawn()?;
    let started = Instant::now();
    let success = loop {
        match child.try_wait() {
            Ok(Some(status)) => break status.success(),
            Ok(None) => (),
            Err(error) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(error);
            }
        }
        if started.elapsed() >= timeout || cancelled.is_some_and(|c| c.load(Ordering::Acquire)) {
            let _ = child.kill();
            let _ = child.wait();
            break false;
        }
        thread::sleep(Duration::from_millis(15));
    };
    use std::io::{Seek, SeekFrom};
    let mut file = file;
    file.seek(SeekFrom::Start(0))?;
    let mut bytes = Vec::new();
    file.take(65536).read_to_end(&mut bytes)?;
    Ok((success, String::from_utf8_lossy(&bytes).into_owned()))
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn output_does_not_deadlock_on_full_pipes_or_inherit_stdin() {
        let result = output(
            Command::new("sh").args(["-c", "cat; head -c 100000 /dev/zero; printf error >&2"]),
            Duration::from_secs(2),
            None,
        )
        .unwrap();
        assert!(result.0);
        assert_eq!(result.1.len(), 65536);
    }
    #[test]
    fn timeout_and_cancellation_reap_the_child() {
        for cancelled in [false, true] {
            let started = Instant::now();
            let result = output(
                Command::new("sleep").arg("10"),
                Duration::from_millis(50),
                Some(&AtomicBool::new(cancelled)),
            )
            .unwrap();
            assert!(!result.0);
            assert!(started.elapsed() < Duration::from_secs(1));
        }
    }
}
