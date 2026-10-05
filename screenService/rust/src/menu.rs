use crate::desktop_strings::AppStrings;
use crate::{
    config::{CaptureSource, Config, VERSION},
    management::{Action, Management, Outcome},
    platform::{self, Input},
    service,
};
use serde_json::{json, Value};
use std::{
    ffi::{c_char, CString},
    io,
    sync::{atomic::Ordering, Arc, OnceLock},
    thread,
    time::{Duration, Instant},
};

extern "C" {
    fn termish_menu_create(paths: *const c_char, callback: extern "C" fn(u32)) -> bool;
    fn termish_menu_update(model: *const c_char);
    fn termish_menu_poll();
    fn termish_menu_destroy();
    fn termish_menu_chinese() -> bool;
}
static CONTROL: OnceLock<Arc<Management>> = OnceLock::new();
extern "C" fn requested(raw: u32) {
    if let (Some(control), Some(action)) = (CONTROL.get(), Action::from_raw(raw)) {
        control.request(action);
    }
}
fn item(title: &str, action: u32, enabled: bool) -> Value {
    json!({"title":title,"action":action,"enabled":enabled})
}
fn model(
    control: &Management,
    strings: &AppStrings,
    diagnostic: bool,
    recording: bool,
    input: bool,
) -> Value {
    let ready = control.ready.load(Ordering::Acquire);
    let paused = control.paused.load(Ordering::Acquire);
    let connected = control.connected.load(Ordering::Acquire);
    let status = if !ready {
        strings.starting
    } else if paused {
        strings.paused
    } else if connected {
        strings.connected
    } else {
        strings.idle
    };
    let recording = if diagnostic {
        strings.recording_diagnostic
    } else if recording {
        strings.recording_ready
    } else {
        strings.recording_missing
    };
    let input = if diagnostic {
        strings.control_diagnostic
    } else if input {
        strings.control_ready
    } else {
        strings.control_missing
    };
    json!({
        "tooltip":format!("Termish Helper · {status}"),"connected":connected,"paused":paused,
        "items":[
            item(&format!("Termish Helper · {VERSION}"),0,false),
            item(status,0,false),
            {"separator":true},
            item(strings.disconnect,Action::Disconnect as u32,ready && connected),
            item(if paused { strings.resume } else { strings.pause },Action::TogglePause as u32,ready),
            {"separator":true},
            item(recording,100,!diagnostic),
            item(input,101,!diagnostic),
            item(strings.restart,Action::Restart as u32,ready),
            {"separator":true},
            item(strings.logs,102,true),
            item(strings.directory,103,true),
            {"separator":true},
            item(strings.quit,Action::Quit as u32,true)
        ]
    })
}

pub fn run(config: Config, control: Arc<Management>) -> io::Result<Outcome> {
    let diagnostic = config.capture_source == CaptureSource::TestPattern;
    let paths = CString::new(json!({
        "log":config.log_file,"directory":crate::config::state_directory(&std::env::current_exe()?)
    }).to_string()).unwrap();
    CONTROL
        .set(control.clone())
        .map_err(|_| io::Error::other("menu already initialized"))?;
    if !unsafe { termish_menu_create(paths.as_ptr(), requested) } {
        return Err(io::Error::other("could not create menu bar status item"));
    }
    let strings = AppStrings::for_chinese(unsafe { termish_menu_chinese() });
    let worker_control = control.clone();
    let worker = thread::spawn(move || service::run(config, worker_control));
    let mut previous = String::new();
    let mut permission_check = Instant::now() - Duration::from_secs(2);
    let mut recording = false;
    let mut input = false;
    while !worker.is_finished() {
        if permission_check.elapsed() >= Duration::from_secs(1) {
            permission_check = Instant::now();
            if !diagnostic {
                recording = platform::capture_allowed();
                input = Input::default().status() == 0;
            }
        }
        let snapshot = model(&control, &strings, diagnostic, recording, input).to_string();
        if snapshot != previous {
            let encoded = CString::new(snapshot.as_str()).unwrap();
            unsafe { termish_menu_update(encoded.as_ptr()) };
            previous = snapshot;
        }
        platform::poll_events();
        unsafe { termish_menu_poll() };
        thread::sleep(Duration::from_millis(15));
    }
    let result = worker
        .join()
        .unwrap_or_else(|_| Err(io::Error::other("screen service thread panicked")));
    unsafe { termish_menu_destroy() };
    result
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn menu_reflects_authoritative_lifecycle_and_independent_permissions() {
        let control = Management::default();
        control.ready.store(true, Ordering::Release);
        let strings = AppStrings::for_chinese(false);
        let state = model(&control, &strings, false, true, false);
        assert_eq!(state["items"][1]["title"], strings.idle);
        assert_eq!(state["items"][3]["enabled"], false);
        assert_eq!(state["items"][6]["title"], strings.recording_ready);
        assert_eq!(state["items"][7]["title"], strings.control_missing);
        control.connected.store(true, Ordering::Release);
        assert_eq!(
            model(&control, &strings, false, true, true)["items"][3]["enabled"],
            true
        );
        control.paused.store(true, Ordering::Release);
        let state = model(&control, &AppStrings::for_chinese(true), false, true, true);
        assert_eq!(state["items"][1]["title"], "远程访问已暂停");
        assert_eq!(state["items"][4]["title"], "恢复远程访问");
        let state = model(&control, &strings, true, false, false);
        assert_eq!(state["items"][6]["enabled"], false);
        assert_eq!(state["items"][7]["enabled"], false);
    }
}
