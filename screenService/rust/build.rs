fn main() {
    println!("cargo:rerun-if-changed=../service.properties");
    let source = std::fs::read_to_string("../service.properties").unwrap();
    for name in ["RELAY_VERSION", "SCREEN_PORT"] {
        let value = source
            .lines()
            .find_map(|line| line.strip_prefix(&format!("{name}=")))
            .unwrap();
        let _: u16 = value.parse().expect("numeric service metadata");
        println!("cargo:rustc-env={name}={value}");
    }
    if std::env::var("CARGO_CFG_TARGET_OS").unwrap() == "macos" {
        build_menu();
    }
}

fn run(command: &mut std::process::Command) {
    assert!(
        command.status().expect("run Xcode tool").success(),
        "native menu build failed"
    );
}

fn build_menu() {
    use std::{env, path::PathBuf, process::Command};
    println!("cargo:rerun-if-changed=../macos/MenuBar.m");
    println!("cargo:rerun-if-changed=../macos/Capture.m");
    let sdk = Command::new("xcrun")
        .args(["--sdk", "macosx", "--show-sdk-path"])
        .output()
        .expect("Xcode macOS SDK");
    assert!(sdk.status.success(), "Xcode macOS SDK unavailable");
    let sdk = String::from_utf8(sdk.stdout).unwrap();
    let target = match env::var("CARGO_CFG_TARGET_ARCH").unwrap().as_str() {
        "aarch64" => "arm64-apple-macos11.0",
        "x86_64" => "x86_64-apple-macos10.13",
        arch => panic!("unsupported macOS architecture: {arch}"),
    };
    let out = PathBuf::from(env::var_os("OUT_DIR").unwrap());
    let object = out.join("MenuBar.o");
    run(Command::new("xcrun")
        .args([
            "clang",
            "-target",
            target,
            "-isysroot",
            sdk.trim(),
            "-fobjc-arc",
            "-fblocks",
            "-O2",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-c",
            "../macos/MenuBar.m",
            "-o",
        ])
        .arg(&object));
    let capture = out.join("Capture.o");
    run(Command::new("xcrun")
        .args([
            "clang",
            "-target",
            target,
            "-isysroot",
            sdk.trim(),
            "-fobjc-arc",
            "-fblocks",
            "-O2",
            "-Wall",
            "-Wextra",
            "-Werror",
            "-c",
            "../macos/Capture.m",
            "-o",
        ])
        .arg(&capture));
    run(Command::new("xcrun")
        .args(["libtool", "-static", "-o"])
        .arg(out.join("libtermish_menu.a"))
        .arg(object)
        .arg(capture));
    println!("cargo:rustc-link-search=native={}", out.display());
    println!("cargo:rustc-link-lib=static=termish_menu");
    println!("cargo:rustc-link-lib=framework=AppKit");
    for framework in ["VideoToolbox", "CoreMedia", "CoreVideo", "IOSurface"] {
        println!("cargo:rustc-link-lib=framework={framework}");
    }
    println!("cargo:rustc-link-arg=-Wl,-weak_framework,ScreenCaptureKit");
    // Objective-C availability guards use Clang's platform-version helper.
    // Rust's -nodefaultlibs otherwise omits it in optimized builds.
    let runtime = Command::new("xcrun")
        .args(["clang", "--print-runtime-dir"])
        .output()
        .expect("Clang runtime directory");
    assert!(runtime.status.success(), "Clang runtime unavailable");
    println!(
        "cargo:rustc-link-search=native={}",
        String::from_utf8(runtime.stdout).unwrap().trim()
    );
    println!("cargo:rustc-link-lib=static=clang_rt.osx");
}
