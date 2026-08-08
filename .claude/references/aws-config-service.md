# AWS Config service — state, design, and remaining feasibility map

Service id `config`, package `services/configservice/`, JSON 1.1, target prefix
`StarlingDoveService.`. Docs: `docs/services/config.md` (hand-maintained table —
listed under `deferred_handlers` in `tools/docs/services.yaml`; keep the op count
in `docs/services/index.md` in sync). Initial CRUD landed upstream in PR #934;
this fork added the fidelity pass + compliance evaluation loop (33 actions total).

## Implementation map

| Piece | Location |
|---|---|
| Handler (switch on 33 actions) | `services/configservice/ConfigServiceJsonHandler.java` |
| Service logic | `services/configservice/AwsConfigService.java` |
| Models (25 records) | `services/configservice/model/` |
| Registry entry | `core/common/ResolvedServiceCatalog.java` ~:314 |
| Dispatch case | `core/common/AwsJson11Controller.java` ~:249 (`case "config"`) |
| Config toggle | `FLOCI_SERVICES_CONFIGSERVICE_ENABLED` (default true; `EmulatorConfig.configservice()`) |
| Storage files (namespace `config`) | `config-rules.json`, `config-evaluations.json`, `config-recorders.json`, `config-delivery-channels.json`, `config-retention.json`, `config-conformance-packs.json`, `config-tags.json` |

Action groups: Config Rules (5) · Compliance & Evaluations (9) · Configuration
Recorder (6) · Delivery Channel (3) · Retention Configuration (3) · Conformance
Packs (4) · Tagging (3).

## Compliance-loop design (this fork)

- **Evaluation store**: `region → ruleName → resourceKey("Type|Id") → ConfigEvaluation`,
  last-write-wins per (rule, resource). Every inner mutation is followed by
  `persistRegion(evaluations, region)`; `normalizeEvaluationMaps()` re-wraps both
  nested levels on load. Deleting a rule cascades its evaluations.
- **ResultToken deviation** (documented in config.md): real AWS hands custom rules
  an opaque token inside the Lambda evaluation event. Floci has no evaluation
  event, so `PutEvaluations` expects the **rule name** as the token
  (`<rule-name>:<suffix>` accepted). `TestMode=true` validates without persisting.
  `PutExternalEvaluation` takes the rule name explicitly.
- **Rule-level aggregation** (`DescribeComplianceByConfigRule`): drop
  `NOT_APPLICABLE` → empty ⇒ `INSUFFICIENT_DATA`; any `NON_COMPLIANT` ⇒
  `NON_COMPLIANT` + `ComplianceContributorCount{CappedCount≤25, CapExceeded}`;
  else any `INSUFFICIENT_DATA` ⇒ `INSUFFICIENT_DATA`; else `COMPLIANT` (no
  contributor count — AWS only populates it for NON_COMPLIANT).
- **Resource-level** (`DescribeComplianceByResource`): group across rules; any
  non-compliant rule ⇒ `NON_COMPLIANT` (count = rules, cap 25); else
  INSUFFICIENT_DATA > COMPLIANT > NOT_APPLICABLE.
- **Summaries**: by-rule counts *rules* (caps 25, field names are
  `CompliantResourceCount`/`NonCompliantResourceCount` per AWS despite counting
  rules); by-resource-type counts resources (caps 100); omitted `ResourceTypes` ⇒
  one aggregate entry with no `ResourceType` field.
- **Timestamps**: `OrderingTimestamp` parsed as double (boto3 sends fractional
  epoch); server-generated times are epoch-second `Long`s (boto3 parses both into
  `datetime`). `FirstActivatedTime` / invocation times are transient (not
  persisted across restart) — mirrors the recorder run-state pattern.
- **Pagination** (`Paged<T>` + offset token): DescribeConfigRules and
  DescribeComplianceByConfigRule fixed 25; EvaluationStatus default/max 150;
  conformance packs default/max 20 with `InvalidLimitException` (only Config op
  documenting that code); GetComplianceDetailsByConfigRule and
  DescribeComplianceByResource default 10 / max 100; others fixed 10.
- **Error types** (all 400, asserted via `__type`): `NoSuchConfigRuleException`,
  `InvalidResultTokenException`, `InvalidParameterValueException`,
  `InvalidNextTokenException`, `InvalidLimitException`,
  `NoSuchConfigurationRecorderException`, `NoSuchDeliveryChannelException`,
  `LastDeliveryChannelDeleteFailedException` (channel delete while recorder
  running), `NoSuchRetentionConfigurationException`,
  `NoSuchConformancePackException`, `NoAvailableConfigurationRecorderException`.
- Behavior notes: `DescribeConfigRules` with an unknown name 400s (AWS-accurate);
  `PutConfigRule` round-trips the full rule shape and defaults
  `EvaluationModes=[{Mode:"DETECTIVE"}]`; `StartConfigRulesEvaluation` marks
  invocation but calls no Lambda.

## Tests

Java (`src/test/java/.../configservice/`): `ConfigRuleIntegrationTest`,
`ComplianceEvaluationIntegrationTest`, `ConfigPaginationIntegrationTest`,
`RetentionConfigurationIntegrationTest`, `ConfigurationRecorderIntegrationTest`,
`ConformancePackIntegrationTest`, `ConfigTaggingIntegrationTest`,
`AwsConfigServicePersistenceTest` (restart simulation via `SharedStorageFactory`).
boto3: `compatibility-tests/sdk-test-python/tests/test_config.py` (+
`config_client` fixture in `conftest.py`) — needs a running emulator on :4566.
Note: boto3 client-side-validates some params (e.g. `RetentionPeriodInDays≥30`),
so server-side validation errors are only testable via raw HTTP.

## What's still missing (feasibility map)

Cheap (pattern exists, contained in `configservice/`):
- `DeliverConfigSnapshot` stub, `BatchGetResourceConfig` returning empty,
  organization-rule CRUD stubs, `PutConfigurationAggregator` CRUD — all
  store-and-return shapes.

Medium:
- `AWS::Config::ConfigRule` / `AWS::Config::ConfigurationRecorder` /
  `AWS::Config::DeliveryChannel` CloudFormation provisioners — none exist; follow
  the per-service `*CfnProvisioner` pattern (see architecture.md).
- Remediation configuration CRUD (`PutRemediationConfiguration` etc.) — CRUD is
  cheap; actually *executing* remediation (SSM automation) is not.

Architecturally expensive (the reason "real AWS Config" is a project, not a PR):
- **Configuration items / resource recording** (`GetResourceConfigHistory`,
  `ListDiscoveredResources`): needs a cross-service change-capture stream that
  floci does not have — no service publishes resource mutations today
  (`ResourceUsageEnumerator` is a 48-line stub). Prior art for the delivery side:
  CloudTrail's background flusher writing gzipped records to S3
  (`CloudTrailServiceConfig.flushIntervalSeconds()`), which is the shape snapshot
  delivery to the delivery-channel bucket would take.
- **`SelectResourceConfig`**: additionally needs a SQL-subset query engine over
  configuration items.
- **Aggregators with real cross-account/cross-region data**: needs the recording
  layer first; multi-account plumbing exists (`AccountAwareStorageBackend`,
  Organizations), but there is nothing to aggregate until items are recorded.
