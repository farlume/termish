#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CARGO=$(command -v cargo || true)
[ -n "$CARGO" ] || CARGO="$HOME/.cargo/bin/cargo"
case "${1:-}" in screenService|agentBridge) SERVICE="$1" ;; *) exit 2 ;; esac
exec "$CARGO" test --locked --manifest-path "$ROOT/$SERVICE/rust/Cargo.toml"
