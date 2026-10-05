package dev.termish.agent

/** Retire only this account's old companion after the native bridge starts. */
internal val legacyBridgeCleanupScript =
    """
    (
      LEGACY="${'$'}HOME/.local/share/termish-agent/current/termish-agent.pyz"
      legacy_bridge_owner() {
        case "${'$'}1" in ''|*[!0-9]*) return 1 ;; esac
        [ "${'$'}1" -gt 1 ] || return 1
        [ "${'$'}(ps -p "${'$'}1" -o uid= | tr -d '[:space:]')" = "${'$'}(id -u)" ] || return 1
        case "${'$'}(ps -p "${'$'}1" -o command=)" in *" ${'$'}LEGACY serve") ;; *) return 1 ;; esac
        COMM=${'$'}(ps -p "${'$'}1" -o comm=)
        case "${'$'}{COMM##*/}" in Python|python|python[0-9]*|pypy*) return 0 ;; *) return 1 ;; esac
      }
      PIDS=${'$'}(ps -axo pid=,uid=,command= | while read -r PID OWNER_UID COMMAND; do
        [ "${'$'}OWNER_UID" = "${'$'}(id -u)" ] || continue
        case "${'$'}COMMAND" in (*" ${'$'}LEGACY serve") printf '%s\n' "${'$'}PID" ;; esac
      done)
      for PID in ${'$'}PIDS; do
        if legacy_bridge_owner "${'$'}PID"; then kill -TERM "${'$'}PID" || exit 1; fi
      done
      N=0
      while [ "${'$'}N" -lt 50 ]; do
        RUNNING=0
        for PID in ${'$'}PIDS; do
          if legacy_bridge_owner "${'$'}PID"; then RUNNING=1; fi
        done
        [ "${'$'}RUNNING" = 1 ] || break
        sleep 0.1
        N=${'$'}((N + 1))
      done
      for PID in ${'$'}PIDS; do
        if legacy_bridge_owner "${'$'}PID"; then exit 1; fi
      done
      rm -f "${'$'}LEGACY"
    )
    """.trimIndent()
