//! Host build tool; remote machines receive executables and never need a compiler.
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::{
    collections::BTreeMap,
    env, fs, io,
    path::{Path, PathBuf},
    process::{Command, Stdio},
};

type Result<T> = std::result::Result<T, Box<dyn std::error::Error>>;
const TARGETS: [(&str, &str); 4] = [
    ("aarch64-apple-darwin", "Darwin-arm64"),
    ("x86_64-apple-darwin", "Darwin-x86_64"),
    ("aarch64-unknown-linux-musl", "Linux-aarch64"),
    ("x86_64-unknown-linux-musl", "Linux-x86_64"),
];
#[derive(Clone, Serialize, Deserialize)]
struct Binary {
    file: String,
    sha256: String,
    size: usize,
}
#[derive(Serialize, Deserialize)]
struct Manifest {
    source_sha256: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    macos_signing_identity: Option<String>,
    binaries: BTreeMap<String, Binary>,
}
fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .parent()
        .unwrap()
        .parent()
        .unwrap()
        .to_owned()
}
fn tool(name: &str) -> PathBuf {
    let path = PathBuf::from(env::var("HOME").unwrap_or_default())
        .join(".cargo/bin")
        .join(name);
    if path.is_file() {
        path
    } else {
        PathBuf::from(name)
    }
}
fn run(cmd: &mut Command) -> Result<()> {
    if !cmd.status()?.success() {
        return Err(format!("command failed: {cmd:?}").into());
    }
    Ok(())
}
fn hash(data: &[u8]) -> String {
    format!("{:x}", Sha256::digest(data))
}
fn rust_sources(dir: &Path, out: &mut Vec<PathBuf>) -> Result<()> {
    for item in fs::read_dir(dir)? {
        let path = item?.path();
        if path.is_dir() {
            rust_sources(&path, out)?;
        } else if path.extension().is_some_and(|s| s == "rs") {
            out.push(path);
        }
    }
    Ok(())
}
fn prefix(service: &Path) -> &'static str {
    if service.components().any(|c| c.as_os_str() == "agentBridge") {
        "termish-agent"
    } else {
        "termish-screen"
    }
}
fn source_hash(service: &Path) -> Result<String> {
    let mut files = Vec::new();
    rust_sources(&service.join("rust/src"), &mut files)?;
    files.sort();
    files.extend([
        service.join("rust/build.rs"),
        service.join("rust/Cargo.toml"),
        service.join("rust/Cargo.lock"),
        service.join("service.properties"),
        root().join("LICENSES/TermishScreen-Rust.txt"),
    ]);
    if prefix(service) == "termish-screen" {
        files.extend([
            service.join("macos/Info.plist"),
            service.join("macos/AppIcon.icns"),
        ]);
    }
    let mut digest = Sha256::new();
    for path in files {
        // License is outside the service directory. Keep names stable across CI checkouts.
        let name = path
            .strip_prefix(service)
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_else(|_| "../LICENSES/TermishScreen-Rust.txt".into());
        digest.update(name.as_bytes());
        if path.is_file() {
            digest.update(fs::read(path)?);
        }
    }
    Ok(format!("{:x}", digest.finalize()))
}
fn validate_binary(dir: &Path, target: &str, binary: &Binary) -> Result<()> {
    if !TARGETS.iter().any(|(_, name)| *name == target)
        || binary.file != format!("{}-{target}", prefix(dir))
    {
        return Err("invalid target manifest".into());
    }
    let bytes = fs::read(dir.join(&binary.file))?;
    if hash(&bytes) != binary.sha256 || bytes.len() != binary.size {
        return Err("invalid binary checksum".into());
    }
    Ok(())
}
fn read_manifest(service: &Path) -> Result<Manifest> {
    let dir = service.join("build/binaries");
    let manifest: Manifest = serde_json::from_slice(&fs::read(dir.join("manifest.json"))?)?;
    if manifest.source_sha256 != source_hash(service)? {
        return Err("stale service payloads; run screenServiceRustBuild".into());
    }
    for (target, binary) in &manifest.binaries {
        validate_binary(&dir, target, binary)?;
    }
    Ok(manifest)
}
fn build(service_name: &str, all: bool) -> Result<()> {
    let service = root().join(service_name);
    let fingerprint = source_hash(&service)?;
    let mut signing_identity = None;
    if service_name == "screenService" && env::consts::OS == "macos" {
        signing_identity = env::var("TERMISH_SCREEN_MACOS_SIGN_IDENTITY")
            .ok()
            .filter(|value| !value.trim().is_empty());
        if signing_identity.as_deref() == Some("-") {
            return Err(
                "Use a certificate identity, not ad hoc signing, to preserve macOS consent".into(),
            );
        }
    }
    let dir = service.join("build/binaries");
    fs::create_dir_all(&dir)?;
    let output = Command::new(tool("rustup"))
        .args(["target", "list", "--installed"])
        .output()?;
    if !output.status.success() {
        return Err("rustup target list failed".into());
    }
    let installed = String::from_utf8(output.stdout)?;
    let mut binaries = BTreeMap::new();
    let manifest_path = dir.join("manifest.json");
    if manifest_path.exists() {
        let old: Manifest = serde_json::from_slice(&fs::read(&manifest_path)?)?;
        // Linux consumers retain the signing metadata of imported Mac payloads.
        if env::consts::OS != "macos" {
            signing_identity = old.macos_signing_identity.clone();
        }
        if old.source_sha256 == fingerprint && old.macos_signing_identity == signing_identity {
            for (target, binary) in old.binaries {
                validate_binary(&dir, &target, &binary)?;
                binaries.insert(target, binary);
            }
        }
    }
    let mut count = 0;
    for (target, name) in TARGETS {
        if !all
            && (!installed.lines().any(|line| line == target)
                || (target.contains("apple") && env::consts::OS != "macos"))
        {
            continue;
        }
        let mut command = Command::new(tool("cargo"));
        command
            .args(["rustc", "--locked", "--manifest-path"])
            .arg(service.join("rust/Cargo.toml"))
            .args(["--release", "--target", target]);
        if target.contains("linux-musl") {
            command.args(["--", "-C", "linker=rust-lld"]);
        }
        run(&mut command)?;
        let source = service.join(format!(
            "rust/target/{target}/release/{}",
            if service_name == "agentBridge" {
                "termish-agent"
            } else {
                "termish-screen-service"
            }
        ));
        let file = format!("{}-{name}", prefix(&service));
        let payload = dir.join(&file);
        if target.contains("apple") && service_name == "screenService" {
            package_macos_helper(
                &service,
                name,
                &source,
                &payload,
                signing_identity.as_deref(),
            )?;
        } else {
            write_payload(&payload, &fs::read(source)?)?;
        }
        let bytes = fs::read(&payload)?;
        binaries.insert(
            name.into(),
            Binary {
                file,
                sha256: hash(&bytes),
                size: bytes.len(),
            },
        );
        count += 1;
    }
    if count == 0 {
        return Err("Install a supported Rust target (see docs/screen-service.md)".into());
    }
    if source_hash(&service)? != fingerprint {
        return Err("Service sources changed during build; rerun the build".into());
    }
    let manifest = Manifest {
        source_sha256: fingerprint,
        macos_signing_identity: signing_identity,
        binaries,
    };
    write_changed(
        &manifest_path,
        &(serde_json::to_string_pretty(&manifest)? + "\n"),
    )?;
    Ok(())
}

fn package_macos_helper(
    service: &Path,
    target: &str,
    executable: &Path,
    payload: &Path,
    identity: Option<&str>,
) -> Result<()> {
    let bundle = service.join(format!("build/apps/{target}/Termish Helper.app"));
    if bundle.exists() {
        fs::remove_dir_all(&bundle)?;
    }
    let contents = bundle.join("Contents");
    fs::create_dir_all(contents.join("MacOS"))?;
    fs::create_dir_all(contents.join("Resources"))?;
    write_payload(
        &contents.join("MacOS/Termish Helper"),
        &fs::read(executable)?,
    )?;
    let version = properties(service)?["RELAY_VERSION"].to_string();
    let plist =
        fs::read_to_string(service.join("macos/Info.plist"))?.replace("@RELAY_VERSION@", &version);
    fs::write(contents.join("Info.plist"), plist)?;
    fs::copy(
        service.join("macos/AppIcon.icns"),
        contents.join("Resources/AppIcon.icns"),
    )?;
    // Sign the complete copied bundle, including icon and metadata. Signing a
    // bare binary first and adding resources remotely loses their integrity.
    let mut sign = Command::new("/usr/bin/codesign");
    sign.args([
        "--force",
        "--sign",
        identity.unwrap_or("-"),
        "--identifier",
        "dev.termish.screen-service",
    ]);
    if identity.is_some() {
        sign.args(["--options", "runtime", "--timestamp"]);
    }
    run(sign.arg(&bundle))?;
    run(Command::new("/usr/bin/codesign")
        .args(["--verify", "--strict"])
        .arg(&bundle))?;
    let archive = payload.with_extension(format!("upload-{}", std::process::id()));
    run(Command::new("/usr/bin/tar")
        .arg("-czf")
        .arg(&archive)
        .arg("-C")
        .arg(&bundle)
        .arg("Contents"))?;
    fs::rename(archive, payload)?;
    Ok(())
}
fn properties(service: &Path) -> Result<BTreeMap<String, u16>> {
    fs::read_to_string(service.join("service.properties"))?
        .lines()
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(|line| {
            let (key, value) = line.split_once('=').ok_or("invalid service metadata")?;
            Ok((key.into(), value.parse()?))
        })
        .collect()
}
fn kotlin_literal(s: &str) -> String {
    serde_json::to_string(s).unwrap().replace('$', "\\$")
}
fn write_changed(path: &Path, value: &str) -> Result<()> {
    if fs::read_to_string(path).ok().as_deref() != Some(value) {
        fs::create_dir_all(path.parent().unwrap())?;
        fs::write(path, value)?;
    }
    Ok(())
}
fn write_payload(path: &Path, bytes: &[u8]) -> Result<()> {
    // Replacing the inode preserves macOS executable signature/cache semantics.
    // Overwriting a previously executed Mach-O in place can cause SIGKILL.
    let temporary = path.with_extension(format!("upload-{}", std::process::id()));
    fs::write(&temporary, bytes)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&temporary, fs::Permissions::from_mode(0o700))?;
    }
    fs::rename(temporary, path)?;
    Ok(())
}
fn pack() -> Result<()> {
    let service = root().join("screenService");
    let metadata = properties(&service)?;
    let manifest = read_manifest(&service)?;
    let mut script = fs::read_to_string(service.join("install.sh"))?;
    if !script.contains("@NATIVE_SOURCE@") {
        return Err("missing native installer marker".into());
    }
    script = script.replace(
        "@NATIVE_SOURCE@",
        fs::read_to_string(service.join("native-install.sh"))?.trim_end(),
    );
    for key in ["RELAY_VERSION", "SCREEN_PORT"] {
        script = script.replace(&format!("@{key}@"), &metadata[key].to_string());
    }
    let mut shell = Command::new("/bin/sh")
        .arg("-n")
        .stdin(Stdio::piped())
        .spawn()?;
    use io::Write;
    shell.stdin.take().unwrap().write_all(script.as_bytes())?;
    if !shell.wait()?.success() {
        return Err("installer shell syntax error".into());
    }
    let resources = root().join("composeApp/src/commonMain/composeResources/files/termish-screen");
    fs::create_dir_all(&resources)?;
    for item in fs::read_dir(&resources)? {
        let path = item?.path();
        if !manifest
            .binaries
            .values()
            .any(|b| Some(b.file.as_str()) == path.file_name().and_then(|s| s.to_str()))
        {
            fs::remove_file(path)?;
        }
    }
    let mut entries = Vec::new();
    for (target, binary) in &manifest.binaries {
        let source = service.join("build/binaries").join(&binary.file);
        let destination = resources.join(&binary.file);
        write_payload(&destination, &fs::read(source)?)?;
        entries.push(format!(
            "        {} to Binary({}, {}, {})",
            kotlin_literal(target),
            kotlin_literal(&binary.file),
            kotlin_literal(&binary.sha256),
            binary.size
        ));
    }
    let chars: Vec<char> = script.chars().collect();
    let literals = chars
        .chunks(4096)
        .map(|chunk| {
            format!(
                "            {}",
                kotlin_literal(&chunk.iter().collect::<String>())
            )
        })
        .collect::<Vec<_>>()
        .join(",\n");
    let output = format!(
        r#"// Generated by tools/service-build; edit screenService/ instead.
package dev.termish.screen

internal object ScreenServiceAssets {{
    const val RELAY_VERSION = {version}
    const val SCREEN_PORT = {port}
    data class Binary(val filename: String, val sha256: String, val size: Int)
    private val binaries = mapOf<String, Binary>(
{entries}
    )
    fun binaryFor(os: String?, arch: String?): Binary? {{
        val normalized = when (arch) {{
            "arm64", "aarch64" -> if (os == "Darwin") "arm64" else "aarch64"
            "amd64", "x86_64" -> "x86_64"
            else -> arch
        }}
        return binaries["$os-$normalized"]
    }}
    val INSTALL_SCRIPT: String by lazy {{
        listOf(
{literals}
        ).joinToString("")
    }}
}}
"#,
        version = metadata["RELAY_VERSION"],
        port = metadata["SCREEN_PORT"],
        entries = entries.join(",\n")
    );
    write_changed(&root().join("composeApp/build/generated/screenService/kotlin/dev/termish/screen/ScreenServiceAssets.kt"), &output)
}
fn pack_agent() -> Result<()> {
    let service = root().join("agentBridge");
    let manifest = read_manifest(&service)?;
    let resources = root().join("composeApp/src/commonMain/composeResources/files/termish-agent");
    fs::create_dir_all(&resources)?;
    for entry in fs::read_dir(&resources)? {
        let path = entry?.path();
        if !manifest
            .binaries
            .values()
            .any(|b| Some(b.file.as_str()) == path.file_name().and_then(|s| s.to_str()))
        {
            fs::remove_file(path)?;
        }
    }
    let mut entries = Vec::new();
    for (target, b) in manifest.binaries {
        write_payload(
            &resources.join(&b.file),
            &fs::read(service.join("build/binaries").join(&b.file))?,
        )?;
        entries.push(format!(
            "        {} to Binary({}, {}, {})",
            kotlin_literal(&target),
            kotlin_literal(&b.file),
            kotlin_literal(&b.sha256),
            b.size
        ));
    }
    let output = format!(
        r#"// Generated by tools/service-build.
package dev.termish.agent

internal object AgentBridgeAssets {{
    data class Binary(val filename: String, val sha256: String, val size: Int)
    private val binaries = mapOf<String, Binary>(
{}
    )
    fun binaryFor(os: String?, arch: String?): Binary? {{
        val normalized = when (arch) {{
            "arm64", "aarch64" -> if (os == "Darwin") "arm64" else "aarch64"
            "amd64", "x86_64" -> "x86_64"
            else -> arch
        }}
        return binaries["$os-$normalized"]
    }}
}}
"#,
        entries.join(",\n")
    );
    write_changed(
        &root().join(
            "composeApp/build/generated/agentBridge/kotlin/dev/termish/agent/AgentBridgeAssets.kt",
        ),
        &output,
    )
}
fn main() -> Result<()> {
    let args: Vec<String> = env::args().skip(1).collect();
    match args.first().map(String::as_str) {
        Some("build-screen") => build("screenService", args.iter().any(|arg| arg == "--all")),
        Some("build-agent") => build("agentBridge", args.iter().any(|arg| arg == "--all")),
        Some("pack-agent") => pack_agent(),
        Some("pack-screen") => pack(),
        _ => Err("usage: service-build {build-screen [--all]|pack-screen}".into()),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn escapes_kotlin_interpolation_and_unicode() {
        assert_eq!(
            kotlin_literal("$HOME\n中文\"\\"),
            "\"\\$HOME\\n中文\\\"\\\\\""
        );
    }
    #[test]
    fn invalid_manifest_never_traverses_paths() {
        let binary = Binary {
            file: "../secret".into(),
            sha256: String::new(),
            size: 0,
        };
        assert!(validate_binary(Path::new("/nonexistent"), "Darwin-arm64", &binary).is_err());
    }
}
