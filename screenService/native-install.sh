# Rust payload arrives over authenticated SFTP, never inside a shell argument.
APP_DIR="$HOME/Library/Application Support/termish"
NATIVE="$APP_DIR/screen-service"
TERMISH_BACKEND=rust
[ -n "${TERMISH_NATIVE_STAGE:-}" ] || { echo "==> Native payload required" >&2; exit 1; }
if [ -n "${TERMISH_NATIVE_STAGE:-}" ]; then
  case "$TERMISH_NATIVE_STAGE" in
    "$APP_DIR"/screen-service.upload-*) ;;
    *) echo '==> Invalid service staging path' >&2; exit 1 ;;
  esac
  case "${TERMISH_NATIVE_SHA256:-}" in
    ''|*[!0-9a-f]*) echo '==> Invalid service checksum' >&2; exit 1 ;;
  esac
  [ "${#TERMISH_NATIVE_SHA256}" = 64 ] || exit 1
  if command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA=$(shasum -a 256 "$TERMISH_NATIVE_STAGE" | awk '{print $1}')
  elif command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA=$(sha256sum "$TERMISH_NATIVE_STAGE" | awk '{print $1}')
  else
    echo '==> SHA-256 verifier unavailable' >&2; exit 1
  fi
  if [ "$ACTUAL_SHA" != "$TERMISH_NATIVE_SHA256" ]; then
    echo '==> Service payload checksum mismatch' >&2; exit 1
  fi
  chmod 700 "$TERMISH_NATIVE_STAGE"
  TERMISH_NATIVE_EXEC="$TERMISH_NATIVE_STAGE"
  if [ "$OS" = "Darwin" ]; then
    APP_BUNDLE="$APP_DIR/Termish Helper.app"
    TERMISH_APP_STAGE="$TERMISH_NATIVE_STAGE.app"
    trap 'rm -rf "$TERMISH_APP_STAGE"' 0
    mkdir -m 700 "$TERMISH_APP_STAGE"
    tar -xzf "$TERMISH_NATIVE_STAGE" -C "$TERMISH_APP_STAGE"
    TERMISH_NATIVE_EXEC="$TERMISH_APP_STAGE/Contents/MacOS/Termish Helper"
    /usr/bin/codesign --verify --strict "$TERMISH_APP_STAGE"
    NATIVE="$APP_BUNDLE/Contents/MacOS/Termish Helper"
  fi
  if [ "$("$TERMISH_NATIVE_EXEC" --version)" != "@RELAY_VERSION@" ]; then
    echo '==> Service payload version or architecture mismatch' >&2; exit 1
  fi
  TERMISH_BACKEND=rust
  echo '==> Rust service payload verified'
fi
