# CLAUDE.md — fork-local notes (sam-fakhreddine/floci)

This file is committed **only on this fork**. Upstream (`floci-io/floci`) ignores
`CLAUDE.md` and expects a local symlink to `AGENTS.md` instead — do not send this
file, `.claude/`, or the related `.gitignore` carve-outs upstream.

**`AGENTS.md` remains the canonical instruction file.** Read it first: it defines
the architecture, protocol rules, storage rules, testing policy, code style, and
commit conventions (Conventional Commits, PR title validated by CI, **no AI
`Co-Authored-By` trailers**). Nothing below overrides it.

## Remote session environment

Claude Code web containers are prepared by `.claude/hooks/session-start.sh`
(registered in `.claude/settings.json`):

- **Temurin JDK 25** at `/opt/temurin-25` — the pom's enforcer requires JDK 25;
  containers ship JDK 21. `JAVA_HOME`/`PATH` are exported via the session env file.
- **dockerd** started (with stale-pid cleanup) — Lambda, API Gateway authorizers,
  WebSockets, CloudFormation, DocDB, Neptune, ECR, ELBv2 and RDS/ElastiCache
  integration tests all need the Docker socket and fail without it.
- **`LANG`/`LC_ALL=C.UTF-8`** — S3 tests write objects with non-ASCII keys; an
  ASCII locale makes `S3ServiceTest.copyObjectWithNonASCIIKey` fail on
  `InvalidPathException`.

If a shell is missing these (hook didn't run), see
`.claude/references/environment.md` for the manual equivalents.

## Command crib

```bash
./mvnw test                                   # full suite (needs Docker + UTF-8 locale)
./mvnw test -Dtest=SsmIntegrationTest#putParameter
./mvnw quarkus:dev                            # hot reload on :4566
./mvnw clean package -DskipTests              # build target/quarkus-app/quarkus-run.jar
make docs-sync                                # regenerate action tables after handler changes
make docs-check                               # CI docs gate (run after committing docs/)
```

## Reference notes (read before deep work)

Digested findings from prior sessions live in `.claude/references/`:

- `architecture.md` — repo map: protocol dispatch, service registry, storage
  system, the add-a-service/add-an-action checklists, pagination and testing
  patterns, docs tooling.
- `aws-config-service.md` — AWS Config deep dive: the 33 implemented actions,
  this fork's compliance-loop design (evaluation storage, aggregation semantics,
  ResultToken deviation), and the feasibility map for what's still missing
  (resource recording, SelectResourceConfig, aggregators, CFN types).
- `environment.md` — remote-container runbook: hook internals, manual setup,
  compatibility-suite invocation, known environmental failure signatures.
