# Remote-container environment runbook

## What the session-start hook does

`.claude/hooks/session-start.sh` (registered in `.claude/settings.json`, runs only
when `CLAUDE_CODE_REMOTE=true`, synchronous, idempotent):

1. Installs **Temurin JDK 25** to `/opt/temurin-25` if absent (Adoptium
   latest-GA tarball). The pom's maven-enforcer rule rejects the container's
   default JDK 21 with "Floci requires JDK 25".
2. Starts **dockerd** if the socket is down, first deleting stale
   `/var/run/docker.pid` / containerd pid files when no dockerd process exists —
   a suspended/resumed container leaves stale pid files pointing at recycled
   PIDs, and dockerd refuses to start until they're removed
   ("process with PID N is still running"). Waits up to 30s; warns instead of
   failing so non-Docker work can proceed. Daemon log: `/tmp/dockerd.log`.
3. Appends to `$CLAUDE_ENV_FILE`: `JAVA_HOME=/opt/temurin-25`, `PATH`,
   `LANG=C.UTF-8`, `LC_ALL=C.UTF-8`.

Manual equivalents when the hook hasn't run in a shell:

```bash
export JAVA_HOME=/opt/temurin-25 PATH="$JAVA_HOME/bin:$PATH" LANG=C.UTF-8 LC_ALL=C.UTF-8
rm -f /var/run/docker.pid; nohup dockerd >/tmp/dockerd.log 2>&1 &
```

## Known failure signatures (environmental, not code)

| Symptom | Cause |
|---|---|
| `Floci requires JDK 25. Set JAVA_HOME to JDK 25.` | JAVA_HOME not pointing at /opt/temurin-25 |
| Errors mentioning `unix:///var/run/docker.sock`; Lambda `Function.TimedOut`; API Gateway authorizer / WebSocket / DocDB / Neptune / ECR / ELBv2 / CloudFormation suites failing in bulk | dockerd not running |
| `ensure docker is not running or delete /var/run/docker.pid` in /tmp/dockerd.log | stale pid file after container resume |
| `S3ServiceTest.copyObjectWithNonASCIIKey` → `InvalidPathException: Malformed input` | non-UTF-8 locale (`sun.jnu.encoding`) |

With Docker up and a UTF-8 locale the full `./mvnw test` suite passes
(~8.6k tests); without Docker, ~100 failures/errors concentrate in the
container-backed suites listed above.

## Compatibility (boto3) suite runbook

```bash
./mvnw clean package -DskipTests
java -jar target/quarkus-app/quarkus-run.jar &        # emulator on :4566
python3 -m venv /tmp/venv && /tmp/venv/bin/pip install -r compatibility-tests/sdk-test-python/requirements.txt
cd compatibility-tests/sdk-test-python && /tmp/venv/bin/python -m pytest tests/test_config.py -v
```

Notes: system `pip install` fails on the Debian-owned `cryptography` package —
always use a venv. `FLOCI_ENDPOINT` overrides the default
`http://localhost:4566`. The suite needs no AWS credentials (dummy `test` keys).

## Misc

- Outbound HTTPS goes through the workspace proxy; Java is pre-wired via
  `JAVA_TOOL_OPTIONS` (truststore + proxy flags) — don't unset it, and expect it
  echoed as a "Picked up JAVA_TOOL_OPTIONS" line on every JVM start.
- Maven wrapper `./mvnw` downloads Maven on first use; the local repo lives in
  `/root/.m2` and is cached with container state.
- Docs gate: `make docs-check` only passes after `docs/` edits are **committed**
  (it runs `git diff --exit-code -- docs/`).
