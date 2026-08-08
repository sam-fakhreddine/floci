# Floci architecture map

Floci is a Java 25 / Quarkus local AWS emulator (open-source LocalStack Community
alternative). Everything listens on port 4566 and speaks real AWS wire protocols —
the AWS SDK/CLI/Terraform work unmodified. ~72 service packages live under
`src/main/java/io/github/hectorvent/floci/services/<svc>/`.

Layered design (per `AGENTS.md`): **Handler/Controller** (parse wire protocol,
build AWS-shaped responses) → **Service** (`@ApplicationScoped`, business logic,
throws `AwsException(type, message, status)`) → **model/** (Java records).

## Protocol dispatch

| Protocol | Entry point | Routing key |
|---|---|---|
| JSON 1.1 | `core/common/AwsJson11Controller.java` | `X-Amz-Target: <Prefix>.<Action>` |
| Query (XML) | `core/common/AwsQueryController.java` | form-encoded `Action` |
| REST JSON / REST XML | per-service JAX-RS controllers | URL paths |

- `core/common/ResolvedServiceCatalog.java` is the single registry: one
  `descriptor(...)` call per service in the constructor (service id, enablement
  flag, storage mode/flush, protocol, target prefixes). AWS Config is at ~line 314
  (id `config`, prefix `StarlingDoveService.`).
- `ServiceCatalog.matchTarget()` strips the prefix to get the action string; the
  controller then switches on service id to the service's `*JsonHandler.handle(...)`.
- `core/common/RegionResolver` extracts region + account from the SigV4
  `Authorization` credential scope; controllers resolve once and pass `region` down.
- IAM: JSON 1.1 / Query services need **no** registry entry in
  `IamActionRegistry` — `<scope>:<Action>` is derived automatically. Enforcement
  is off by default (`floci.iam.enforcement-enabled=false`).

## Storage

- Always via `StorageFactory.create(namespace, fileName, typeReference)` wrapped
  in `core/storage/StorageBackedMap`. Never instantiate backends directly.
- `AccountAwareStorageBackend` prefixes keys with the caller's account id
  automatically; cross-account access uses explicit `*ForAccount` overloads
  (see Organizations).
- **Flush gotcha**: `StorageBackedMap` persists only on a *top-level* `put`
  (`remove` persists via `storage.delete`). Mutating a nested map requires
  re-putting the outer entry — see `persistRegion(...)` in
  `services/configservice/AwsConfigService.java`. After load, re-wrap inner maps
  as `ConcurrentHashMap` (Jackson gives plain maps) — `normalizeRegionMaps(...)`.
- Modes: `memory` / `persistent` / `hybrid` / `wal`; test profile uses `memory`.
  Per-service flush interval in `application.yml` under
  `floci.storage.services.<id>.flush-interval-ms` (main: 5000, test: 60000).

## Adding a service (or extending one)

Cleanest end-to-end template: commit `0f1b3e1` (Organizations, JSON 1.1). Files:
handler + service + `model/` records; `ResolvedServiceCatalog` descriptor;
`AwsJson11Controller` field/ctor/assignment/dispatch case (4 edits);
`EmulatorConfig` service + storage interfaces; main + test `application.yml`;
integration + unit tests; docs (`docs/services/<id>.md`, `docs/services/index.md`
op count, `mkdocs.yml`, README tables); `tools/docs/services.yaml` registration.

Extending an existing service touches only: handler switch + method, service
method, model records, tests, `docs/services/<id>.md`, index op count.

Model record conventions: `@RegisterForReflection` (native image),
`@JsonInclude(NON_NULL)`, `@JsonIgnoreProperties(ignoreUnknown = true)`, explicit
`@JsonProperty` per component. Match real AWS casing per shape — most JSON 1.1
shapes are PascalCase, but e.g. Config's recorder/delivery-channel family is
camelCase because AWS itself is.

## Pagination

No shared utility; copy an existing pattern:
- `services/iot/IotService.java` `paginate(items, maxResults, nextToken)` +
  `Page<T>` record — offset-integer `NextToken`.
- `services/organizations/OrganizationsJsonHandler.java` — same idea inline.
- `services/configservice/AwsConfigService.java` `Paged<T>` — adds default/max
  page size + op-specific over-limit error code.
Sort before paginating: `ConcurrentHashMap` iteration order is unstable and
offset tokens need a stable order. Bad token → `InvalidNextTokenException` 400.

## Testing

- `*IntegrationTest.java`: `@QuarkusTest` + RestAssured raw HTTP POST `/` with
  `X-Amz-Target` + `application/x-amz-json-1.1`. **Must** call
  `RestAssuredJsonUtils.configureAwsContentTypes()` in `@BeforeAll` (RestAssured
  parser registration is broken under Quarkus). Stateful sequences use
  `@TestMethodOrder(OrderAnnotation.class)`. Error asserts:
  `.body("__type", equalTo("SomeException"))`. All test classes share one Quarkus
  instance and one store — use unique resource names and clean up.
- `*ServiceTest` / `*PersistenceTest`: plain JUnit, direct construction. Restart
  simulation: inner `SharedStorageFactory extends StorageFactory` returning
  `InMemoryStorage` keyed by filename; build two service instances over it
  (see `AwsConfigServicePersistenceTest`).
- `compatibility-tests/sdk-test-python/`: boto3 suite against a *running*
  emulator; per-service client fixture in `conftest.py`, `tests/test_<svc>.py`.
  AGENTS.md: management APIs should be SDK-validated, not only raw HTTP.

## Docs tooling (CI gate)

`docs/services/*.md` "Supported Actions" tables are generated from handler
source by `tools/docs/regen_action_docs.py`, driven by `tools/docs/services.yaml`.
Services under `deferred_handlers` (AWS Config is one) have **hand-maintained**
tables the generator never touches. `make docs-sync` regenerates; `make docs-check`
(CI) runs regen `--strict` **plus `git diff --exit-code -- docs/`** — so it only
passes after docs edits are committed. New switch handlers must be registered in
`services.yaml` or listed under `deferred_handlers`, or `--strict` fails.

## CloudFormation resource types

Never extend `CloudFormationResourceProvisioner` (legacy monolith). Add
per-service `<Service>CfnProvisioner` under `services/cloudformation/provisioners/`
(`@ApplicationScoped`, CDI auto-discovery). Set **both** `setPhysicalId` (backs
`Ref`) and `getAttributes()` entries (back `Fn::GetAtt` — a silent miss resolves
to the literal `"LogicalId.Attr"`). `provision` also serves updates. Unmapped
types stub as `CREATE_COMPLETE`, so tests must assert exact `Fn::GetAtt` keys.
