#!/bin/bash
set -euo pipefail

# Prepares a Claude Code on the web container so the full Floci suite can run:
#   - Temurin JDK 25 (the pom's enforcer rule requires it; containers ship JDK 21)
#   - a running Docker daemon (Lambda, RDS, ElastiCache, DocDB, Neptune, ... tests)
#   - a UTF-8 locale (S3 tests write objects with non-ASCII keys to disk)
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

JDK_DIR=/opt/temurin-25
JDK_URL="https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"

if [ ! -x "$JDK_DIR/bin/java" ]; then
  echo "Installing Temurin JDK 25 to $JDK_DIR..."
  mkdir -p "$JDK_DIR"
  curl -fsSL --retry 3 "$JDK_URL" | tar -xz --strip-components=1 -C "$JDK_DIR"
fi

if ! docker info >/dev/null 2>&1; then
  if command -v dockerd >/dev/null 2>&1; then
    # A suspended/resumed container leaves stale pid files behind; dockerd
    # refuses to start while they name a (now unrelated) live PID.
    if ! pgrep -x dockerd >/dev/null 2>&1; then
      rm -f /var/run/docker.pid /run/docker/containerd/containerd.pid
    fi
    echo "Starting dockerd..."
    nohup dockerd >/tmp/dockerd.log 2>&1 &
    for _ in $(seq 1 30); do
      docker info >/dev/null 2>&1 && break
      sleep 1
    done
  fi
  if ! docker info >/dev/null 2>&1; then
    echo "warning: dockerd did not start; container-backed integration tests will fail (see /tmp/dockerd.log)" >&2
  fi
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ] && ! grep -qs "JAVA_HOME=$JDK_DIR" "$CLAUDE_ENV_FILE"; then
  {
    echo "export JAVA_HOME=$JDK_DIR"
    echo 'export PATH="$JAVA_HOME/bin:$PATH"'
    echo "export LANG=C.UTF-8"
    echo "export LC_ALL=C.UTF-8"
  } >>"$CLAUDE_ENV_FILE"
fi

echo "session-start: JDK 25 ready, docker $(docker info >/dev/null 2>&1 && echo up || echo unavailable), locale C.UTF-8"
