#![cfg(target_os = "macos")]
use std::{fs, process::Command};

#[test]
fn native_menu_dispatches_enabled_actions_and_updates_status_icon() {
    let binary = std::env::temp_dir().join(format!("termish-menu-smoke-{}", std::process::id()));
    let output = Command::new("xcrun")
        .args([
            "clang",
            "-fobjc-arc",
            "-fblocks",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-framework",
            "AppKit",
            "-framework",
            "CoreGraphics",
        ])
        .arg(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../macos/MenuBarTest.m"
        ))
        .arg("-o")
        .arg(&binary)
        .output()
        .unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let output = Command::new(&binary).output().unwrap();
    fs::remove_file(binary).unwrap();
    if output.status.code() == Some(77) {
        eprintln!("SKIP native menu: no WindowServer session");
        return;
    }
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
}
