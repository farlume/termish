use std::{
    process::Command,
    sync::{Mutex, OnceLock},
    time::{Duration, Instant},
};
#[derive(Clone, Copy, Default)]
pub struct State {
    pub locked: Option<bool>,
    pub asleep: Option<bool>,
}
static CACHE: OnceLock<Mutex<(Instant, State)>> = OnceLock::new();

fn property(output: &str, name: &str) -> Option<String> {
    output
        .lines()
        .find_map(|line| line.strip_prefix(&format!("{name}=")).map(str::to_owned))
}
fn locked(output: &str) -> Option<bool> {
    if !matches!(property(output, "Type").as_deref(), Some("x11" | "wayland"))
        || property(output, "Remote").as_deref() != Some("no")
        || property(output, "Class").as_deref() != Some("user")
        || property(output, "Active").as_deref() != Some("yes")
    {
        return None;
    }
    match property(output, "LockedHint").as_deref() {
        Some("yes") => Some(true),
        Some("no") => Some(false),
        _ => None,
    }
}
fn asleep(output: &str) -> Option<bool> {
    if output.contains("Monitor is On") {
        Some(false)
    } else if ["Monitor is Off", "Monitor is Standby", "Monitor is Suspend"]
        .iter()
        .any(|s| output.contains(s))
    {
        Some(true)
    } else {
        None
    }
}
fn query() -> State {
    let started = Instant::now();
    let mut state = State::default();
    let limit = Duration::from_millis(500);
    let sessions = crate::process::output(
        Command::new("loginctl").args(["list-sessions", "--no-legend", "--no-pager"]),
        limit,
        None,
    )
    .unwrap_or_default();
    if sessions.0 {
        let uid = unsafe { libc::geteuid() }.to_string();
        for line in sessions
            .1
            .lines()
            .filter(|line| line.split_whitespace().nth(1) == Some(uid.as_str()))
            .take(16)
        {
            if started.elapsed() > Duration::from_millis(800) {
                break;
            }
            let Some(id) = line.split_whitespace().next() else {
                continue;
            };
            let info = crate::process::output(
                Command::new("loginctl").args([
                    "show-session",
                    id,
                    "--no-pager",
                    "-p",
                    "Type",
                    "-p",
                    "Remote",
                    "-p",
                    "Active",
                    "-p",
                    "LockedHint",
                    "-p",
                    "Class",
                ]),
                limit,
                None,
            )
            .unwrap_or_default();
            if info.0 && locked(&info.1).is_some() {
                state.locked = locked(&info.1);
                break;
            }
        }
    }
    if !super::wayland::active() && std::env::var_os("DISPLAY").is_some() {
        let dpms =
            crate::process::output(Command::new("xset").arg("q"), limit, None).unwrap_or_default();
        if dpms.0 {
            state.asleep = asleep(&dpms.1);
        }
    }
    state
}

pub fn state() -> State {
    let cache = CACHE
        .get_or_init(|| Mutex::new((Instant::now() - Duration::from_secs(2), State::default())));
    let mut cache = cache.lock().unwrap_or_else(|e| e.into_inner());
    if cache.0.elapsed() >= Duration::from_secs(1) {
        cache.1 = query();
        cache.0 = Instant::now();
    }
    cache.1
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn only_an_active_local_user_desktop_provides_lock_state() {
        let desktop = "Type=wayland\nClass=user\nRemote=no\nActive=yes\nLockedHint=yes\n";
        assert_eq!(locked(desktop), Some(true));
        assert_eq!(
            locked(&desktop.replace("LockedHint=yes", "LockedHint=no")),
            Some(false)
        );
        for (from, to) in [
            ("Class=user", "Class=greeter"),
            ("Type=wayland", "Type=tty"),
            ("Remote=no", "Remote=yes"),
            ("Active=yes", "Active=no"),
            ("LockedHint=yes", "LockedHint=unknown"),
        ] {
            assert_eq!(locked(&desktop.replace(from, to)), None);
        }
        assert_eq!(locked(""), None);
    }
    #[test]
    fn power_state_is_unknown_without_dpms_and_does_not_infer_idle_means_asleep() {
        assert_eq!(asleep("Monitor is On"), Some(false));
        for state in ["Off", "Standby", "Suspend"] {
            assert_eq!(asleep(&format!("Monitor is {state}")), Some(true));
        }
        assert_eq!(asleep("timeout: 600; screen saver active"), None);
    }
}
