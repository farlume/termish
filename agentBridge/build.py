#!/usr/bin/env python3
"""Build the dependency-free Agent Bridge zipapp bundled by Termish."""

from __future__ import annotations

import pathlib
import shutil
import tempfile
import zipapp


ROOT = pathlib.Path(__file__).resolve().parent
SOURCE = ROOT / "termish_agent"
OUTPUT = (
    ROOT.parent
    / "composeApp"
    / "src"
    / "commonMain"
    / "composeResources"
    / "files"
    / "termish-agent.pyz"
)


def main() -> None:
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="termish-agent-build-") as tmp:
        stage = pathlib.Path(tmp)
        for source in SOURCE.glob("*.py"):
            shutil.copy2(source, stage / source.name)
        zipapp.create_archive(
            stage,
            target=OUTPUT,
            interpreter="/usr/bin/env python3",
            compressed=True,
        )
    print(OUTPUT)


if __name__ == "__main__":
    main()
