#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
CARGO=$(command -v cargo || true)
[ -n "$CARGO" ] || CARGO="$HOME/.cargo/bin/cargo"
exec "$CARGO" run --quiet --locked --manifest-path "$ROOT/tools/service-build/Cargo.toml" -- "$@"
