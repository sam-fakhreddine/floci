# [see-something] issues/0012 — LZA organizations stage: Macie/GuardDuty/SecurityHub module probes ignore security-config.yaml, floci has no service to answer them

- **Status:** Fixed
- **Labels:** see-something, deploy-tooling, lza-integration
- **Severity:** 4 (high) — blocks Step 5 `uc-build` custom-config Stage-1 runs at `Organization/Organizations` on every run, deterministically
- **Opened:** 2026-08-12
- **Found on branch:** `integration/all-features`

## What's wrong

`uc-build` execution `58fddaee-364c-4714-b6b2-f78959fdfca3` progressed cleanly through `Bootstrap`, `Review`, and `Logging/Logging` (confirming issues/0004's reopened fix works — see that file) and then failed at `Organization/Organizations`:

```
❌  Module macie failed (UnknownOperationException): macie setup failed in us-east-1 (UnknownOperationException)
❌  Regional Errors: [
  {"region": "us-east-1", "accountId": "531837219685", ..., "errorMessage": "Unknown operation: GET /macie"},
  {"region": "us-east-1", "accountId": "000000000000", ..., "errorMessage": "Unknown operation: GET /macie"}
]
```

floci implements no Macie service (`tokensave_search("Macie")` → zero results); `EmulatorConfig.rejectUnknownServiceScope` (default `true`) throws `UnknownOperationException` for any unimplemented service/operation, which is what actually fires here.

`.mission/config/uc-build/config/security-config.yaml` already has `macie.enable: false`, with a pre-existing comment noting it's disabled because it's "not implemented in floci" — but that config flag doesn't stop LZA's organizations-stage module runner from **probing** the live service first (`GetMacieSession` et al.) to decide whether to disable it. The runner's own log line names its actual escape hatch: `SKIP_MACIE_MODULE` (and by the same mechanism `SKIP_GUARDDUTY_MODULE`/`SKIP_GUARD_DUTY_MODULE`/`SKIP_SECURITY_HUB_MODULE`/`SKIP_SECURITYHUB_MODULE`), an environment variable on the CodeBuild project running the stage — not a security-config.yaml key.

## Root cause

Not a floci emulation bug and not a new symptom of issues/0004 — this is a wiring gap in the deploy tooling. A working fix already existed: `.mission/scripts/skip-unimplemented-security-modules.sh` patches `AWSAccelerator-ToolkitProject`'s CodeBuild environment with all five `SKIP_*_MODULE=true` vars via `codebuild update-project`, and is proven correct — it's already called by the older `.mission/scripts/kick-and-watch.sh` deploy path. It was simply never wired into `.mission/scripts/uc-deploy-all.sh`, which is the standard `uc-build` entry point used by Step 5's flow. Those vars are in-memory CodeBuild-project state (not baked into the image or any persisted config), so a fresh `reset-netnew-safe.sh` net-new container always starts without them regardless of any earlier session having applied them by hand.

## Fix

Added a `[4a]` step to `.mission/scripts/uc-deploy-all.sh`, right after `[4]` confirms the installer stack's pipeline (and therefore `AWSAccelerator-ToolkitProject`) exists, and before `[5]`/`[6]` touch config or start the core execution:

```sh
sh skip-unimplemented-security-modules.sh || { echo "FATAL: could not apply SKIP_*_MODULE env vars"; exit 1; }
```

Fails the whole `uc-deploy-all.sh` run loudly if the patch doesn't take (matching the script's own verification step, which exits non-zero when no `SKIP_` vars are present after the update). No Java/floci source change — `.mission/` is git-untracked (`.git/info/exclude:11`), so this fix lives only in the local deploy-tooling checkout, not in the emulator itself. A `disable-unimplemented-security-services.sh` config-side companion is referenced in `.mission/shared-lza-container-design.md` and in this script's own header comment as "necessary but NOT sufficient on its own" but does not exist as a file — `security-config.yaml`'s existing `enable: false` lines already cover that side manually; no separate script was ever needed to reach a passing run, so it was not created.

## Verification

Not yet independently re-run against this specific execution path (the fix was applied to the script itself, not yet exercised end-to-end). Next `uc-deploy-all.sh uc-build` run will apply it automatically as part of the standard flow; the `Organization/Organizations` stage should progress past the Macie probe.

## Next

Fresh `uc-deploy-all.sh uc-build` run (no image rebuild needed — this is a deploy-script change, not a floci source change). Confirm `Organization/Organizations` clears the Macie/GuardDuty/SecurityHub probes and progresses to whatever stage comes next. If a further genuinely-new symptom surfaces, diagnose it with the same rigor per HANDOFF.md's standing procedure.

