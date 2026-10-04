use serde_json::{json, Value};
use std::{
    env, fs,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncWrite, AsyncWriteExt};
pub type Result<T> = std::result::Result<T, Box<dyn std::error::Error + Send + Sync>>;
pub const MAX_LINE: usize = 8 * 1024 * 1024;
pub fn now() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis() as u64
}
pub fn id() -> String {
    uuid::Uuid::new_v4().simple().to_string()[..12].to_owned()
}
pub fn text<'a>(v: &'a Value, k: &str) -> &'a str {
    v.get(k).and_then(Value::as_str).unwrap_or("")
}
pub fn home() -> PathBuf {
    PathBuf::from(env::var("HOME").unwrap_or_else(|_| "/".into()))
}
pub fn expand(s: &str) -> PathBuf {
    s.strip_prefix("~/")
        .map(|s| home().join(s))
        .unwrap_or_else(|| PathBuf::from(s))
}
pub fn bridge_home() -> PathBuf {
    env::var("TERMISH_AGENT_HOME")
        .map(|s| expand(&s))
        .unwrap_or_else(|_| home().join(".local/share/termish-agent"))
}
pub fn private_dir(p: &Path) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    fs::create_dir_all(p)?;
    fs::set_permissions(p, fs::Permissions::from_mode(0o700))?;
    Ok(())
}
pub fn atomic_json(p: &Path, value: &Value) -> Result<()> {
    use std::io::Write;
    use std::os::unix::fs::OpenOptionsExt;
    let temp = p.with_extension(format!("{}.tmp", id()));
    let mut file = fs::OpenOptions::new()
        .create_new(true)
        .write(true)
        .mode(0o600)
        .open(&temp)?;
    let result = (|| -> Result<()> {
        file.write_all(&serde_json::to_vec(value)?)?;
        file.sync_all()?;
        fs::rename(&temp, p)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temp);
    }
    result
}
pub async fn line<R: AsyncBufRead + Unpin>(r: &mut R, max: usize) -> Result<Option<Value>> {
    let mut bytes = Vec::new();
    loop {
        let available = r.fill_buf().await?;
        if available.is_empty() {
            if bytes.is_empty() {
                return Ok(None);
            }
            break;
        }
        let end = available.iter().position(|b| *b == b'\n');
        let size = end.map(|i| i + 1).unwrap_or(available.len());
        if bytes.len() + size > max {
            return Err("request too large".into());
        }
        bytes.extend_from_slice(&available[..size]);
        r.consume(size);
        if end.is_some() {
            break;
        }
    }
    let value: Value = serde_json::from_slice(&bytes).map_err(|_| "invalid JSON")?;
    if !value.is_object() {
        return Err("request must be an object".into());
    }
    Ok(Some(value))
}
pub async fn send<W: AsyncWrite + Unpin>(w: &mut W, value: &Value) -> Result<()> {
    let mut raw = serde_json::to_vec(value)?;
    raw.push(b'\n');
    if raw.len() > MAX_LINE {
        return Err("response too large".into());
    }
    w.write_all(&raw).await?;
    w.flush().await?;
    Ok(())
}
pub fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.into()
    } else {
        format!("{}\n… [truncated]", s.chars().take(max).collect::<String>())
    }
}
pub fn blocks(v: &Value, kind: &str, field: &str) -> String {
    if let Some(s) = v.as_str() {
        return s.into();
    }
    v.as_array()
        .map(|a| {
            a.iter()
                .filter(|b| text(b, "type") == kind)
                .map(|b| text(b, field))
                .collect::<String>()
        })
        .unwrap_or_default()
}
pub fn secrets(v: &Value) -> Vec<String> {
    let mut found = Vec::new();
    match v {
        Value::Object(map) => {
            for (k, v) in map {
                let key = k.to_ascii_lowercase().replace(['_', '-'], "");
                if [
                    "apikey",
                    "accesstoken",
                    "authtoken",
                    "authorization",
                    "password",
                    "secret",
                ]
                .contains(&key.as_str())
                {
                    if let Some(s) = v.as_str() {
                        found.push(s.into());
                    }
                } else {
                    found.extend(secrets(v));
                }
            }
        }
        Value::Array(a) => {
            for v in a {
                found.extend(secrets(v));
            }
        }
        _ => (),
    }
    found
}
pub fn redact(v: &mut Value, secrets: &[String]) {
    use std::sync::LazyLock;
    static BEARER: LazyLock<regex::Regex> =
        LazyLock::new(|| regex::Regex::new(r#"(?i)(bearer\s+)([^\s,;"']+)"#).unwrap());
    static ASSIGN: LazyLock<regex::Regex> = LazyLock::new(|| {
        regex::Regex::new(r#"(?i)(["']?(?:api[_-]?key|access[_-]?token|auth[_-]?token|authorization|password|secret)["']?\s*[:=]\s*["']?)([^"'\s,;}]+)"#).unwrap()
    });
    match v {
        Value::String(s) => {
            for secret in secrets {
                if secret.len() >= 4 {
                    *s = s.replace(secret, "[REDACTED]");
                }
            }
            *s = BEARER.replace_all(s, "${1}[REDACTED]").into_owned();
            *s = ASSIGN.replace_all(s, "${1}[REDACTED]").into_owned();
        }
        Value::Object(m) => {
            for v in m.values_mut() {
                redact(v, secrets);
            }
        }
        Value::Array(a) => {
            for v in a {
                redact(v, secrets);
            }
        }
        _ => (),
    }
}
pub fn error(id: Value, message: &str, code: &str) -> Value {
    json!({"id":id,"error":{"code":code,"message":message}})
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn redact_nested_credentials() {
        let mut v =
            json!({"error":"Bearer abcdef API_KEY=secretvalue exactkey","nested":["exactkey"]});
        redact(&mut v, &["exactkey".into()]);
        let s = v.to_string();
        assert!(!s.contains("abcdef"));
        assert!(!s.contains("secretvalue"));
        assert!(!s.contains("exactkey"));
        assert!(s.contains("[REDACTED]"));
    }
    #[tokio::test]
    async fn rejects_non_object_and_invalid_utf8() {
        for input in [b"[]\n".as_slice(), b"\xff\n".as_slice()] {
            let mut reader = tokio::io::BufReader::new(input);
            assert!(line(&mut reader, MAX_LINE).await.is_err());
        }
    }
}
