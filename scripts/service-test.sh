#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CARGO=$(command -v cargo || true)
[ -n "$CARGO" ] || CARGO="$HOME/.cargo/bin/cargo"
case "${1:-}" in screenService|agentBridge) SERVICE="$1" ;; *) exit 2 ;; esac
"$CARGO" test --locked --manifest-path "$ROOT/$SERVICE/rust/Cargo.toml"
if [ "$SERVICE" = screenService ]; then
  if [ "$(uname)" = Darwin ]; then
    "$CARGO" build --locked --manifest-path "$ROOT/$SERVICE/rust/Cargo.toml"
    TERMISH_CAPTURE_TEST_HELPER="$ROOT/$SERVICE/rust/target/debug/termish-screen-service"
    export TERMISH_CAPTURE_TEST_HELPER
  fi
  python3 -m unittest discover -s "$ROOT/screenService/macos/tests" -v
fi
