# Stream Test Run Reuse — Implementation Guide (v1.1.0)

This is the full narrative implementation guide for the plug-in documented at a
quick-reference level in the [repository root README](../README.md). Where the README already
covers something in full (parameter reference, usage examples, deployment steps,
troubleshooting table), this guide links to it instead of repeating it, and focuses on the
business rationale and the end-to-end mechanics behind each decision.

## 1. Executive summary

`StreamTestRunReusePlugin` is a server-side Codebeamer Wiki plug-in that performs controlled
Test Run result reuse between two explicitly selected streams. The source and target streams
can be in a parent–child relationship, a child–parent relationship, or a sibling
relationship — the direction is determined entirely by the supplied `sourceStreamId` and
`targetStreamId`, never assumed from stream hierarchy.

The plug-in resolves the source Test Case tracker and source Test Run tracker from explicit
parameters, and dynamically resolves the corresponding target Test Case tracker and the
applicable target Test Run tracker through Codebeamer's Stream API. Source and target Test
Case variants are matched through `commonItemId`. Only executable child Test Case Run items
are eligible for reuse; structural parent Test Runs are excluded by requiring a non-null
parent reference.

Dry run is the default and performs no write operations. `latest` mode selects one
highest-ID eligible execution run per matched Test Case; `all` mode selects every eligible
executable child run in ascending ID order. The optional `targetTestCaseIds` parameter
restricts processing to one or more physical Test Case IDs from the resolved target tracker.

Test Run Evidence preserves the original execution provenance; `COPY_OF` identifies the
immediate source run used for the copy. Origin-aware idempotency prevents repeated reuse of
the same execution into the same target Test Case. Execution runs in a bounded background
executor, with one independent transaction per copied run, and repeated Wiki rendering
returns the existing operation status instead of submitting a duplicate job.

## 2. Business purpose and scope

The customization addresses the need to reuse executed Test Run results across stream
variants without manually recreating execution evidence for every target Test Case. A
typical product-line structure contains an initial stream and multiple branch streams. The
same logical Test Case exists as a different physical item in each stream. The plug-in
identifies those variants through `commonItemId` and creates target Test Run copies linked to
the target-stream Test Case.

The same business service can reuse results from Initial Stream to Stream A, Stream A back to
Initial Stream, or Stream A to Stream B. No direct ancestry is required, as long as the
tracker variants and Test Case variants can be resolved unambiguously.

### 2.1 In scope

- Explicit source and target stream IDs.
- Explicit source Test Case tracker and source Test Run tracker IDs.
- Dynamic target Test Case tracker resolution through the Stream API.
- Shared or stream-variant target Test Run tracker resolution.
- All matched Test Cases, or selected target Test Cases.
- `latest` or `all` eligible child execution runs.
- Dry-run analysis, explicit execution, status reporting, and item-level results.
- Evidence inheritance, immediate-source fallback, `COPY_OF` association, and duplicate
  prevention.

### 2.2 Out of scope

- Same-stream Test Run duplication.
- Automatic execution immediately after stream creation.
- Automatic computed-field recalculation.
- Automatic deletion or resynchronization of previous copies.
- One atomic transaction for the complete bulk operation (each copy is independent — see
  [§9 Background processing and transactions](#9-background-processing-and-transactions)).
- Durable operation recovery after a JVM restart in the current in-memory model.
- Automatic creation of missing target Test Case variants.

## 3. Business scenarios and supported stream relationships

### 3.1 Full-scope reuse

When `targetTestCaseIds` is omitted or blank, the plug-in scans all active source Test Cases
in the explicit source Test Case tracker and matches them to target variants. Missing and
ambiguous variants are reported but do not prevent other matched pairs from being planned.

### 3.2 Selective target-driven reuse

When `targetTestCaseIds` is supplied, the plug-in treats the target items as the requested
business scope. Each physical target Test Case must exist, be accessible, be active, and
belong to the resolved target Test Case tracker. The source counterpart is then resolved by
`commonItemId`. This matches the target-driven behavior that the legacy Groovy workflow
script used, applied here to a bulk, cross-stream operation.

### 3.3 Supported relationships

| Scenario | Source | Target | Supported | Matching rule |
|---|---|---|---|---|
| Initial to child | Initial Stream | Stream A | Yes | Target variant resolved via Stream API tracker lineage; Test Case matched by `commonItemId` |
| Child to initial | Stream A | Initial Stream | Yes | Reverse direction, using explicit source and target parameters |
| Sibling reuse | Stream A | Stream B | Yes | No direct ancestry required |
| Same stream | Stream A | Stream A | **No** | Rejected during parameter validation (`sourceStreamId == targetStreamId`) |

## 4. End-to-end processing flow

1. Parse required stream and tracker IDs.
2. Reject same-stream execution.
3. Parse `reuseMode`, `dryRun`, `diagnostic`, `operationKey`, and optional
   `targetTestCaseIds`.
4. Resolve source and target streams.
5. Validate explicit source Test Case and Test Run trackers.
6. Resolve the target Test Case tracker variant.
7. Resolve the shared or stream-specific target Test Run tracker.
8. Load and validate selected target Test Cases, or load all source Test Cases in full-scope
   mode.
9. Match source and target Test Cases by `commonItemId`.
10. Index Test Run tracker items and retain only executable child runs where `parent` is not
    null.
11. Apply `latest` or `all` selection.
12. Resolve the target Test Run Evidence field.
13. Compute effective evidence: inherit source evidence, or fall back to the immediate
    source.
14. Apply origin-aware idempotency.
15. Render the dry-run plan, or reserve `operationKey` and submit background execution.
16. For each planned copy: create and relink the Test Run, persist and verify evidence, and
    create `COPY_OF`.
17. Update operation status and render aggregate and item-level results.

## 5. Stream and tracker resolution

### 5.1 Source metadata

The source stream, source Test Case tracker, and source Test Run tracker are explicit. This
removes ambiguity when multiple source Test Run trackers exist on the same source stream.

### 5.2 Target Test Case tracker

The target Test Case tracker is resolved from the source Test Case tracker and the target
stream using Codebeamer's v3 Stream tracker-variant endpoint:

```
GET /api/v3/streams/<TARGET_STREAM_ID>/trackers/<SOURCE_TRACKER_ID>/variant
```

Exactly one target tracker is required; the call is made same-origin, forwarding the
invoking user's own session cookie (see [§10.1 Security](#101-security)).

### 5.3 Target Test Run tracker

The target Test Run tracker is resolved the same way, as a target-stream variant. If
resolution does not produce a separate tracker (or the lookup fails), the implementation
falls back to the source Test Run tracker, treating it as **shared** between source and
target. The report's "target-run resolution" field shows `STREAM_VARIANT`,
`SHARED_TRACKER`, or `SHARED_TRACKER_FALLBACK` accordingly (see
[`StreamTrackerResolver`](../src/main/java/com/intland/codebeamer/wiki/plugins/streamreuse/StreamTrackerResolver.java)).

> **Configuration requirement:** the target Test Run tracker must contain a multiple-value
> Choice field named exactly **Test Run Evidence**. When the Test Run tracker is shared, the
> same physical tracker acts as both source and target.

## 6. Test Case selection and variant matching

### 6.1 All matched Test Cases

When `targetTestCaseIds` is absent, the matcher loads all active source items and all active
target items. Target items are indexed by `commonItemId`. Each source item proceeds only
when exactly one target item shares its `commonItemId`.

### 6.2 Selected target Test Cases

When `targetTestCaseIds` is populated, the matcher loads each requested target item
directly. The requested target item must belong to the resolved target tracker. Source items
are indexed by `commonItemId`, and exactly one corresponding source variant is required.

### 6.3 CSV normalization rules

- Whitespace is trimmed.
- Only positive integers are accepted.
- Input order is preserved.
- Duplicate IDs are removed (and counted).
- Empty CSV tokens are rejected.
- Malformed tokens block the request **before** any stream or tracker resolution happens.

### 6.4 Full-scope vs. selected-target behavior

| Condition | Full-scope behavior | Selected-target behavior |
|---|---|---|
| Target item is missing | Reported as a missing variant during matching | Request is blocked |
| Target item belongs to another tracker | Not applicable to requested IDs | Request is blocked |
| No source counterpart | Reported and skipped | Reported in validation details; no pair created |
| Multiple source counterparts | Reported as ambiguous | Reported as ambiguous; no pair created |
| Duplicate requested ID | Not applicable | Removed; duplicate count reported |

## 7. Test Run discovery and selection

A manually created Test Run produces both a structural parent Test Run and a child Test Case
Run. Reusing both would double-count the same execution, so only Test Run items whose
parent reference is **not null** are considered (full rule table in the
[README's "Run eligibility" section](../README.md#run-eligibility)).

## 8. Test Run Evidence, provenance, and idempotency

See the README's [Test Run Evidence vs. COPY_OF](../README.md#test-run-evidence-vs-copy_of)
section for the field prerequisite and the evidence-strategy table.

The plug-in does **not** use the immediate source Test Run ID alone as the duplicate key — a
reused source can already point to an earlier original execution several hops back. The
effective origin is therefore the *normalized Test Run Evidence set*. If source evidence is
empty, the immediate source run becomes the effective origin.

```
Original execution OR-50
    |
    +-- Evidence of intermediate copy SR-100 = OR-50
    |
    `-- Target copy TR-200
          Evidence = OR-50
          COPY_OF = SR-100
```

A target copy is skipped when an existing target Test Run references the same target Test
Case and contains the same effective origin evidence. This protects page refresh, operation
retry, chained stream reuse, and repeated execution under a new operation key.

`operationKey` identifies a *deliberate execution request* — it is required only when
`dryRun=false`. Repeated Wiki rendering with the same key returns the existing in-memory
report and must not queue a second job.

> **Durability boundary:** operation keys and status reports are stored in memory. A
> Codebeamer restart clears the registry. Evidence-based idempotency remains the durable
> duplicate-protection mechanism regardless of registry state.

## 9. Background processing and transactions

The Wiki request itself only performs validation and planning — it never blocks on bulk
writes. Mutation work is submitted to a small, intentionally bounded managed executor (core
pool size 1, max pool size 2, queue capacity 20, thread prefix `stream-reuse-`) to limit
concurrent database pressure.

Each copied run executes through `StreamReuseCopyService` with `REQUIRES_NEW` and
`rollbackFor=Exception`. A failure in one copy rolls back that copy but does **not** roll
back successful copies already committed for the same bulk operation. Per copy:

1. Copy the source Test Run DTO.
2. Set the target Test Run tracker.
3. Replace the Test Case reference with the matched target Test Case.
4. Apply the planned Test Run Evidence values.
5. Create the copied Test Run.
6. Reload the created item.
7. Verify that evidence persisted.
8. Create `COPY_OF` to the immediate source.
9. Return the copied Test Run ID.

## 10. Security, performance, and reliability

### 10.1 Security

- The active Codebeamer user and their permissions govern stream metadata and item access —
  the plug-in does not bypass Codebeamer item permissions.
- No embedded service credentials are used anywhere in this implementation (contrast with
  the [legacy Groovy script](../legacy/README.md), which read a PAT/Basic-auth pair from
  environment variables).
- Same-origin Stream API calls forward the current session only to the local Codebeamer
  origin — never to a third-party host.
- Error messages are HTML-escaped before Wiki rendering.
- Selected target items are validated against the resolved target tracker before anything
  else happens.

### 10.2 Performance

- Selected-target mode avoids scanning every target Test Case.
- Full-scope mode currently scans the complete source and target trackers (see
  [Known limitations](../README.md#known-limitations)).
- Test Run indexing currently scans the complete source Test Run tracker.
- Executor concurrency is intentionally bounded; each copy commits independently, limiting
  transaction size.
- Large trackers should be profiled and may require query-based retrieval in a future
  release.

### 10.3 Reliability

- Dry-run default prevents accidental writes.
- Strict CSV validation prevents silent partial scope.
- Structural parent runs are excluded from eligibility.
- Evidence is reloaded and verified after creation, not merely assumed from the write call.
- `operationKey` prevents duplicate background submission during repeated rendering.
- Evidence idempotency protects durable data against retries and restarts, independent of
  the in-memory operation registry.

### 10.4 Operating guidance

- Keep execution-mode markup (`dryRun='false'`) under controlled change ownership — rendering
  the Wiki page *is* the execution trigger.
- Use descriptive, unique operation keys (e.g. embedding source/target stream IDs and a
  date).
- Return the page to `dryRun='true'` after a successful execution.
- Capture the completed report as deployment or test evidence.
- Monitor Codebeamer logs for `STREAM_REUSE_*` entries.
- Use a new operation key for each deliberate new business request — never reuse one across
  unrelated executions.

## 11. Maintenance, upgrade, and rollback

### 11.1 Maintenance

- Keep the Java sources, VM template, Spring XML snippet, and this guide together in source
  control (this repository) so they can never drift apart.
- Record the Codebeamer release, JDK, build tool, build timestamp, and jar checksum for every
  deployment.
- Compile against the exact target Codebeamer libraries after every product upgrade (see
  [`libs/README.md`](../libs/README.md)).
- Regression-test the Stream API paths, internal manager method signatures, tracker layout
  APIs, and Wiki rendering methods after any Codebeamer upgrade — these are the integration
  points most likely to change between releases.
- Retain test data for parent→child, child→parent, sibling, all-scope, selected-scope,
  evidence-inheritance, and duplicate-prevention scenarios.

### 11.2 Rollback

See the README's [Rollback](../README.md#rollback) section. The key rule:

> Restore the jar, VM template, and Spring registration as a **known matching version set**.
> Do not mix a new model class with an old Velocity template, or vice versa.

## 12. Known limitations and recommended future increments

The current known limitations are tracked in the
[README's "Known limitations" section](../README.md#known-limitations) so they stay next to
the troubleshooting guidance. Recommended future increments, carried over from the original
design notes:

- Database-backed operation registry with a unique `operationKey` constraint, for
  cluster-safe, restart-durable status.
- Cluster-aware job ownership for the background executor.
- A dedicated preview/execute controller with a CSRF-protected POST action, instead of using
  Wiki rendering as the execution trigger.
- Supported query-based Test Case and Test Run retrieval, to avoid full tracker scans at
  scale.
- Completion-time-based "latest run" selection, instead of highest Test Run ID.
- Persistent audit history and a downloadable execution report.
- Coalesced tracker-level computed-field recalculation, if item-level recalculation remains
  unavailable.

## Appendix A — Component inventory

See the [Architecture table in the README](../README.md#architecture) for the full
component-to-responsibility mapping. Source files, for reference:

```
src/main/java/com/intland/codebeamer/wiki/plugins/
├── StreamTestRunReusePlugin.java              discovery bridge
└── streamreuse/
    ├── StreamTestRunReusePlugin.java          Wiki entry point
    ├── StreamReuseParameters.java             parameter parsing & validation
    ├── StreamTrackerResolver.java             stream/tracker resolution
    ├── StreamMetadataClient.java              Stream API client interface
    ├── CodebeamerV3StreamMetadataClient.java  Stream API client implementation
    ├── TestCaseMatcher.java                   commonItemId matching
    ├── TestRunSelector.java                   run indexing & latest/all selection
    ├── EvidencePropagationService.java        Test Run Evidence computation
    ├── IdempotencyService.java                duplicate-copy detection
    ├── StreamReusePlanner.java                combines the above into a Plan
    ├── OperationRegistry.java                 in-memory operation status
    ├── StreamReuseFacade.java                 orchestrates prepare/execute
    ├── StreamReuseJob.java                    background job runner
    ├── StreamReuseCopyService.java            per-copy transaction
    ├── StreamReuseExecutorConfiguration.java  bounded executor bean
    ├── StreamReuseStatusController.java       REST status endpoint
    ├── StreamReuseModels.java                 Report / ItemResult / Status models
    └── ReuseMode.java                         LATEST / ALL enum
```

## Appendix B — Quick reference

```
[{StreamTestRunReusePlugin
    sourceStreamId='<SOURCE_STREAM_ID>'
    targetStreamId='<TARGET_STREAM_ID>'
    sourceTestCaseTrackerId='<SOURCE_TEST_CASE_TRACKER_ID>'
    sourceTestRunTrackerId='<SOURCE_TEST_RUN_TRACKER_ID>'
    targetTestCaseIds='<OPTIONAL_TARGET_IDS>'
    reuseMode='latest'
    dryRun='true'
    diagnostic='true'
}]
```

**Safe default:** omit `dryRun` or set it `true` until the preview has been reviewed. Change
`dryRun` to `false` and add a new `operationKey` only after dry-run approval.
