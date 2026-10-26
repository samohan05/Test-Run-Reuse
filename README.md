# Stream Test Run Reuse — Codebeamer Wiki Plug-in (v1.1.0)

A server-side **PTC Codebeamer** Wiki plug-in that performs controlled, bulk **Test Run
result reuse across streams**. Point it at a source stream and a target stream and it finds
the matching Test Case variants (by `commonItemId`), finds each one's eligible prior Test
Runs, and creates linked copies in the target stream — so a Test Case that already passed on
the Initial Stream doesn't have to be re-executed by hand on every branch stream.

```
[{StreamTestRunReusePlugin
    sourceStreamId='133465'
    targetStreamId='148202'
    sourceTestCaseTrackerId='147230'
    sourceTestRunTrackerId='147233'
    reuseMode='latest'
    dryRun='true'
}]
```

> **Looking for the older, same-stream Groovy workflow-script version?** It has been
> superseded by this plug-in and is kept for reference under [`legacy/`](legacy/).

---

## Table of contents

- [Why this exists](#why-this-exists)
- [What it does](#what-it-does)
- [Architecture](#architecture)
- [Wiki invocation](#wiki-invocation)
- [Parameter reference](#parameter-reference)
- [Usage examples](#usage-examples)
- [Output model](#output-model)
- [Deployment](#deployment)
- [Building from source](#building-from-source)
- [Validation checklist](#validation-checklist)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [Repository layout](#repository-layout)

---

## Why this exists

A typical product-line structure has an **Initial Stream** and one or more **branch
streams** (`Stream A`, `Stream B`, ...). The same logical Test Case exists as a different
physical item in each stream (Codebeamer links these variants through `commonItemId`). When
a Test Case has already been executed and passed on one stream, teams don't want to
re-execute the identical test by hand on every other stream variant just to produce a Test
Run record there too — they want to **reuse the existing result**, with full traceability
back to the original execution.

This plug-in automates that: it matches Test Case variants across two explicitly chosen
streams, finds the executable Test Run(s) to reuse, and creates copies in the target stream
that carry forward the original evidence.

## What it does

- **Any direction, no ancestry required.** `sourceStreamId` / `targetStreamId` can be
  Initial → Stream A, Stream A → Initial, or Stream A → Stream B (siblings). The direction is
  whatever you pass in; the plug-in doesn't assume a parent/child relationship.
- **Full-scope or selective.** Omit `targetTestCaseIds` to scan every Test Case in the source
  tracker and reuse into every matched target variant, or pass one or more physical target
  Test Case IDs to restrict the operation to exactly those items.
- **`latest` or `all` run selection.** Reuse just the most recent eligible execution per Test
  Case, or every eligible execution (in ascending ID order).
- **Dry run by default.** Rendering the plug-in with `dryRun` omitted or `true` produces a
  full analysis/report and **writes nothing**. You only get real Test Run creation when you
  explicitly set `dryRun='false'` and supply an `operationKey`.
- **Evidence-aware idempotency.** Re-rendering the same page, retrying an operation, or
  chaining reuse across three streams will not create duplicate copies — the plug-in tracks
  the *effective origin* of each execution (not just the immediate source run ID) and skips
  anything already reused into the same target Test Case.
- **Background execution, per-copy transactions.** Real execution runs on a small bounded
  thread pool; each copied Test Run commits independently, so one failure doesn't roll back
  the others in the same bulk operation.

### Example scenario this solves

```
Initial Stream
|-- Test Case 46056 (commonItemId 46056)
|-- Test Run child execution 46094   <- already executed, PASSED
|
+-- Stream A
|   `-- Test Case 46088 (commonItemId 46056)   <- no Test Run yet
|
`-- Stream B
    `-- Test Case 46120 (commonItemId 46056)   <- no Test Run yet
```

Rendering the plug-in with `sourceStreamId=<Initial Stream>`, `targetStreamId=<Stream A>`
(and again for `Stream B`) creates a Test Run against Test Case 46088 (and 46120) that
carries forward run 46094's result and links it as evidence — without re-running the test.

## Architecture

```
Codebeamer Wiki page
  |
  +-- JSPWiki short-name or fully qualified plug-in invocation
  |     `-- StreamTestRunReusePlugin                     (Wiki entry point, Velocity rendering)
  |
  +-- StreamReuseParameters        parses & validates the invocation contract
  |
  +-- StreamTrackerResolver        resolves/validates source+target streams & trackers
  |     `-- CodebeamerV3StreamMetadataClient   (same-origin calls to /api/v3/streams/...)
  |
  +-- TestCaseMatcher              builds source<->target Test Case pairs by commonItemId
  |
  +-- TestRunSelector              indexes executable child runs, applies latest/all
  |
  +-- EvidencePropagationService   computes the Test Run Evidence to carry forward
  +-- IdempotencyService           detects already-reused target copies
  +-- StreamReusePlanner           combines the above into a read-only execution Plan
  |
  +-- dryRun=true  -> StreamReuseFacade renders the Plan as a Velocity report, stops here
  |
  `-- dryRun=false -> StreamReuseFacade
        +-- OperationRegistry           reserves operationKey, returns existing report on retry
        +-- streamReuseExecutor         bounded ThreadPoolTaskExecutor (core 1 / max 2 / queue 20)
        +-- StreamReuseJob              iterates the Plan, aggregates outcomes
        `-- StreamReuseCopyService      creates ONE copied Test Run per REQUIRES_NEW transaction
```

| Component | Responsibility | Writes to the database? |
|---|---|---|
| `StreamTestRunReusePlugin` | Wiki entry point: resolves the current user, loads the Spring context, renders `stream-test-run-reuse-plugin.vm` | No |
| `StreamReuseParameters` | Parses and validates every Wiki parameter | No |
| `CodebeamerV3StreamMetadataClient` / `StreamMetadataClient` | Queries the Stream API for stream names and tracker-variant resolution | No |
| `StreamTrackerResolver` | Resolves source/target streams and the target Test Case / Test Run trackers | No |
| `TestCaseMatcher` | Matches source and target Test Case variants by `commonItemId` (all-item or selected-target mode) | No |
| `TestRunSelector` | Indexes Test Run tracker items into executable child runs per Test Case; applies `latest`/`all` | No |
| `EvidencePropagationService` | Computes the "Test Run Evidence" value set to copy forward | No |
| `IdempotencyService` | Detects an existing target copy with the same effective origin evidence | No |
| `StreamReusePlanner` | Combines matching + selection + evidence + idempotency into a `Plan` and populates the report | No |
| `OperationRegistry` | In-memory map of `operationId` / `operationKey` → report, so repeated Wiki renders don't resubmit the job | In-memory only |
| `StreamReuseExecutorConfiguration` | Declares the bounded `streamReuseExecutor` bean | — |
| `StreamReuseJob` | Executes a `Plan`'s work items, aggregates created/failed/evidence counters | Delegates per item |
| `StreamReuseCopyService` | Creates one copied Test Run, verifies evidence persisted, creates the `COPY_OF` association | **Yes** — one `REQUIRES_NEW` transaction per copy |
| `StreamReuseStatusController` | `GET /custom/stream-test-run-reuse/status/{operationId}` — poll a running/completed operation | No |
| `stream-test-run-reuse-plugin.vm` | Velocity template that renders the `Report` | No |

### Test Run Evidence vs. `COPY_OF`

These serve different audit purposes:

- **Test Run Evidence** (a multi-value Choice field) identifies the *original execution*
  evidence. If the source run already has evidence (because it is itself a previous reuse
  copy), that evidence is inherited as-is. If the source run has no evidence, the source run
  itself becomes the evidence.
- **`COPY_OF`** is an association that always points at the *immediate* source Test Run used
  for this particular copy — i.e. the direct parent in the copy chain, regardless of how many
  hops the Test Run Evidence chain represents.

| Source state | Target evidence | `COPY_OF` target | Strategy |
|---|---|---|---|
| Source evidence empty/absent | Immediate source run | Immediate source run | `EVIDENCE_FROM_IMMEDIATE_SOURCE` |
| One valid inherited value | That inherited value | Immediate source run | `EVIDENCE_INHERITED_FROM_SOURCE` |
| Multiple valid inherited values | All unique inherited values | Immediate source run | `EVIDENCE_INHERITED_MULTIPLE` |
| Source evidence present but unresolvable | Immediate source run | Immediate source run | `EVIDENCE_FALLBACK_TO_IMMEDIATE_SOURCE` |

**Idempotency key** = target Test Case ID + the effective (resolved) evidence Test Run IDs. A
target copy is skipped (`SKIP_ALREADY_REUSED`) when an existing target Test Run already
carries the same target Test Case and the same effective evidence — this is what makes
re-rendering, retrying, and chained multi-stream reuse safe.

### Run eligibility

Codebeamer creates **two** items per manually-run test: a structural parent Test Run and a
child Test Case Run that actually carries the result. Counting both would double-count the
same execution, so only child runs are eligible:

```java
if (run.getParent() == null) {
    // Structural parent Test Run: exclude from reuse eligibility.
    continue;
}
```

| Rule | Behavior |
|---|---|
| Deleted run | Excluded |
| Structural parent run (`parent == null`) | Excluded |
| Executable child run (`parent != null`) | Eligible, if linked to a matched source Test Case |
| Malformed Test Case reference | Skipped and logged (`STREAM_REUSE_SKIPPED_MALFORMED_TEST_CASE_REFERENCE`) |
| `reuseMode=latest` | Highest eligible Test Run **ID** per Test Case (not completion timestamp — see [Known limitations](#known-limitations)) |
| `reuseMode=all` | Every eligible run, ascending ID order |

## Wiki invocation

**Short-name** (requires the discovery bridge class, included in this repo, to be on the
classpath):

```
[{StreamTestRunReusePlugin
    sourceStreamId='133465'
    targetStreamId='148202'
    sourceTestCaseTrackerId='147230'
    sourceTestRunTrackerId='147233'
    targetTestCaseIds='46088,46089'
    reuseMode='latest'
    dryRun='true'
    diagnostic='true'
}]
```

**Fully qualified** (bypasses the bridge — useful while diagnosing a packaging/deployment
issue):

```
[{INSERT com.intland.codebeamer.wiki.plugins.streamreuse.StreamTestRunReusePlugin WHERE
sourceStreamId='133465',
targetStreamId='148202',
sourceTestCaseTrackerId='147230',
sourceTestRunTrackerId='147233',
targetTestCaseIds='46088,46089',
reuseMode='latest',
dryRun='true',
diagnostic='true'
}]
```

Codebeamer's short Wiki syntax only resolves plug-ins declared directly under
`com.intland.codebeamer.wiki.plugins`. The real implementation lives in the
`...wiki.plugins.streamreuse` sub-package, so
[`StreamTestRunReusePlugin`](src/main/java/com/intland/codebeamer/wiki/plugins/StreamTestRunReusePlugin.java)
at the parent-package level is a one-line **discovery bridge** that simply extends the real
implementation class. Both classes must be on the deployed classpath.

## Parameter reference

| Parameter | Required | Default | Meaning |
|---|---|---|---|
| `sourceStreamId` | Yes | — | Source stream containing the source Test Case variants. Must be a positive integer, and must differ from `targetStreamId`. |
| `targetStreamId` | Yes | — | Target stream that will receive copied Test Runs. |
| `sourceTestCaseTrackerId` | Yes | — | Physical source Test Case tracker ID. |
| `sourceTestRunTrackerId` | Yes | — | Source Test Run tracker ID. |
| `targetTestCaseIds` | No | All matched | Single target Test Case ID, or a comma-separated list. Restricts the operation to these physical target items. Whitespace is trimmed, duplicates are removed (and counted), order is preserved. Any empty token or non-positive-integer token blocks the entire request before anything is resolved. |
| `reuseMode` | No | `latest` | `latest` or `all` (case-insensitive). Anything else is rejected. |
| `dryRun` | No | `true` | `true`/`false`, `yes`/`no`, or `1`/`0`. Read-only preview when true; submits a background execution when false. |
| `diagnostic` | No | `false` | Reserved for expanded diagnostic presentation in the report. |
| `operationKey` | **Only when `dryRun='false'`** | — | Unique key identifying this execution request. Letters, digits, `.`, `_`, `:`, `-`; max 120 characters. Re-rendering the page with the **same** key returns the existing report instead of submitting a second job. |

The target Test Case tracker and the target Test Run tracker are **not** passed in — they are
resolved dynamically from `targetStreamId` via Codebeamer's Stream variant API
(`GET /api/v3/streams/{targetStreamId}/trackers/{sourceTrackerId}/variant`). If that
resolution for the Test Run tracker doesn't produce a distinct tracker, the plug-in falls
back to treating the Test Run tracker as **shared** between source and target (this is
normal/expected when both streams record Test Runs in the same physical tracker).

## Usage examples

All examples assume Test Run Evidence is already configured on the target Test Run tracker
(see [Deployment](#deployment)).

### 1. Preview everything that would be reused (safe to run any time)

```
[{StreamTestRunReusePlugin
    sourceStreamId='133465'
    targetStreamId='148202'
    sourceTestCaseTrackerId='147230'
    sourceTestRunTrackerId='147233'
}]
```
`targetTestCaseIds` is omitted, so every active Test Case in tracker `147230` is scanned and
matched by `commonItemId` against tracker `148202`'s variants. `dryRun` defaults to `true`,
so this only renders a report: scope, resolved trackers, how many Test Cases matched/were
missing/were ambiguous, how many runs are eligible, and the planned action for each pair. No
Test Run is created.

### 2. Preview reuse for one specific target Test Case

```
[{StreamTestRunReusePlugin
    sourceStreamId='133465'
    targetStreamId='148202'
    sourceTestCaseTrackerId='147230'
    sourceTestRunTrackerId='147233'
    targetTestCaseIds='46088'
    reuseMode='latest'
    dryRun='true'
}]
```
Only target Test Case `46088` is considered. The plug-in resolves its source counterpart by
`commonItemId` and reports the single highest-ID eligible run that would be reused. If
`46088` doesn't belong to the resolved target tracker, is deleted, or can't be found, the
request is **blocked** (rejected) rather than silently skipped.

### 3. Preview a selected group of target Test Cases, with the expanded report

```
[{StreamTestRunReusePlugin
    sourceStreamId='133465'
    targetStreamId='148202'
    sourceTestCaseTrackerId='147230'
    sourceTestRunTrackerId='147233'
    targetTestCaseIds='46088,46089'
    reuseMode='latest'
    dryRun='true'
    diagnostic='true'
}]
```
Same as above, but for two target Test Cases at once. Duplicate IDs in `targetTestCaseIds`
are removed (and the duplicate count is shown); malformed tokens (e.g. `46088,ABC`) block the
whole request with a validation error before anything else runs.

### 4. Execute: create the copies for a selected group

```
[{StreamTestRunReusePlugin
    sourceStreamId='133465'
    targetStreamId='148202'
    sourceTestCaseTrackerId='147230'
    sourceTestRunTrackerId='147233'
    targetTestCaseIds='46088,46089'
    reuseMode='latest'
    dryRun='false'
    operationKey='stream-133465-to-148202-selected-latest-20261006-001'
}]
```
This is example 3 with `dryRun='false'` and a unique `operationKey` added. The first render
reserves the key, status becomes `QUEUED`, and the bulk copy is submitted to the background
executor. **Render the page again** to see the operation progress to `RUNNING` and then
`COMPLETED` (or `COMPLETED_WITH_ERRORS` if any single copy failed — the others still
succeed). Re-rendering with the *same* `operationKey` never submits a second job; it just
returns the current report. You can also poll
`GET /custom/stream-test-run-reuse/status/<operationId>` (the `operationId` shown in the
report) instead of re-rendering the Wiki page.

> **Recommended operating sequence:** run a dry run first, review the plan, fix any data
> issues, then flip to `dryRun='false'` with a fresh `operationKey`. Once the operation shows
> `COMPLETED`, return the Wiki markup to `dryRun='true'` so the page doesn't accidentally
> resubmit a new bulk operation later.

### 5. Execute: reuse *all* eligible runs (not just the latest) for one target

```
[{StreamTestRunReusePlugin
    sourceStreamId='133465'
    targetStreamId='148202'
    sourceTestCaseTrackerId='147230'
    sourceTestRunTrackerId='147233'
    targetTestCaseIds='46088'
    reuseMode='all'
    dryRun='false'
    operationKey='stream-133465-to-148202-tc46088-all-20261006-001'
}]
```
Every eligible child run for the matched source Test Case is copied (ascending ID order),
not just the latest one.

### 6. Sibling-stream reuse (no parent/child relationship)

```
[{StreamTestRunReusePlugin
    sourceStreamId='<STREAM_A_ID>'
    targetStreamId='<STREAM_B_ID>'
    sourceTestCaseTrackerId='<STREAM_A_TEST_CASE_TRACKER>'
    sourceTestRunTrackerId='<SHARED_OR_SOURCE_RUN_TRACKER>'
    targetTestCaseIds='<STREAM_B_TEST_CASE_IDS>'
    reuseMode='latest'
    dryRun='true'
}]
```
`sourceStreamId`/`targetStreamId` can name any two streams — direct ancestry is never
required, only that the Test Case/Test Run tracker variants resolve unambiguously.

### 7. Invalid input is rejected before anything is touched

```
targetTestCaseIds='46088,ABC,46089'
```
`ABC` is not a positive integer, so the whole request is rejected with a clear validation
error — nothing is matched, planned, or executed. Same for a stale/deleted ID, an ID from the
wrong tracker, `sourceStreamId == targetStreamId`, an invalid `reuseMode`, or
`dryRun='false'` without an `operationKey`.

## Output model

| Section | Shows |
|---|---|
| Status banner | Status, reuse mode, dry-run flag, operation ID, operation key |
| Selection scope | `ALL_MATCHED_TEST_CASES` or `SELECTED_TARGET_TEST_CASES`, requested IDs, resolved/invalid/duplicate counts, validation messages |
| Source and target | Stream, Test Case tracker, Test Run tracker (and whether the target run tracker is shared or a stream variant) |
| Summary | Scanned, matched, missing, ambiguous, without-runs, eligible, selected, planned, already-reused, created, failed |
| Item-level results | Source Test Case, target Test Case, source run, evidence strategy + IDs, action, copied run ID (or failure reason) |

### Status values

| Status | Meaning |
|---|---|
| `PREVIEW_READY` | Read-only plan created (dry run). |
| `PREVIEW_BLOCKED` | Validation prevented execution. |
| `QUEUED` | Operation reserved and submitted to the executor. |
| `RUNNING` | Background processing in progress. |
| `COMPLETED` | All planned copies completed successfully. |
| `COMPLETED_WITH_ERRORS` | At least one copy failed; others may have succeeded. |
| `FAILED` | Operation could not proceed or be submitted. |

### Item actions

| Action | Meaning |
|---|---|
| `CREATE` | Dry-run plan: a new copy would be created. |
| `CREATED` | Execution: the target Test Run was created. |
| `SKIP_ALREADY_REUSED` | The effective origin already exists for this target Test Case. |
| `FAILED` | The copy transaction failed; `reason` holds the exception message. |

## Deployment

### Prerequisites

- Codebeamer **3.2.0.2**, Jakarta/Tomcat 10 baseline.
- The target Test Run tracker has a user-defined, **multiple-value Choice** field named
  exactly **`Test Run Evidence`**, accepting Test Run items as values.
- The invoking Codebeamer user has read access to the source items and write access to the
  target Test Case tracker and target Test Run tracker.

### Artifacts and where they go

| Artifact | Deploys to |
|---|---|
| The compiled customization jar (this module's classes merged into, or alongside, your site's customization jar) | `<CB_WEBAPP>/WEB-INF/lib/` |
| [`stream-test-run-reuse-plugin.vm`](src/main/resources/templates/wiki-plugin/stream-test-run-reuse-plugin.vm) | `<CB_WEBAPP>/config/templates/wiki-plugin/` |
| [`docs/deployment/spring-component-scan-snippet.xml`](docs/deployment/spring-component-scan-snippet.xml) | merge into your **single** customer Spring context, typically `<CB_WEBAPP>/WEB-INF/classes/my-applicationContext.xml` |

> **Do not leave two customization jars that both define classes under**
> **`com.intland.codebeamer.wiki.plugins.streamreuse` on the classpath** — remove or archive
> the previous jar before deploying a new one.

### Deployment procedure

1. Back up the current customization jar, VM template, and Spring context (capture
   timestamps/sizes/checksums if you want a clean rollback path).
2. Stop Codebeamer/Tomcat.
3. Deploy the new jar to `WEB-INF/lib/`.
4. Deploy `stream-test-run-reuse-plugin.vm` to `config/templates/wiki-plugin/`.
5. Merge the component-scan snippet into your existing customer Spring context (one
   `<beans>` root only).
6. Clear Tomcat's work/cache directories if your environment requires it.
7. Start Codebeamer and watch the startup log for Spring/bean errors.
8. Confirm the fully-qualified `INSERT` syntax renders on a Wiki page.
9. Confirm the short-name syntax renders through the discovery bridge.
10. Run a `dryRun='true'` smoke test before ever setting `dryRun='false'`.

### Rollback

1. Stop Codebeamer/Tomcat.
2. Remove the new customization jar.
3. Restore the previous jar, VM template, and Spring context as a matching set — don't mix a
   new model class with an old Velocity template, or vice versa.
4. Start Codebeamer and re-run the smoke tests above.

## Building from source

```bash
mvn clean package
```

See [`libs/README.md`](libs/README.md) for how to populate the system-scoped Codebeamer/Spring
jars this module compiles against — they are not published to Maven Central and are not part
of this repository.

```bash
# Verify the expected classes are present in the built jar:
jar tf target/stream-test-run-reuse-plugin-1.1.0.jar | findstr /I "streamreuse StreamTestRunReusePlugin"

# Capture a checksum for your deployment record:
certutil -hashfile target/stream-test-run-reuse-plugin-1.1.0.jar SHA256
```

## Validation checklist

- [ ] Application starts with the component scan enabled, no `BeanCreationException`.
- [ ] Fully qualified plug-in invocation renders.
- [ ] Short-name bridge invocation renders.
- [ ] Dry run performs no writes.
- [ ] Resolved source/target stream names, Test Case tracker, and Test Run tracker are correct.
- [ ] Parent→child, child→parent, and sibling→sibling all match Test Cases correctly.
- [ ] Structural parent runs are excluded; only runs with `parent != null` are eligible.
- [ ] `latest` selects exactly one (highest-ID) run per Test Case; `all` selects every eligible run in ascending order.
- [ ] Selecting one target ID, and a comma-separated group, both work; duplicates are deduplicated and reported; a malformed token blocks the request; a wrong-tracker or missing target ID blocks the request.
- [ ] Repeated dry run after a successful copy shows `SKIP_ALREADY_REUSED`; a *new* `operationKey` does not bypass evidence-based idempotency.
- [ ] First execution render queues exactly one job; a repeated render with the same `operationKey` returns the existing status rather than queuing a second job.
- [ ] A failing copy does not stop or roll back the other copies in the same operation.

## Troubleshooting

| Symptom | Likely cause | Resolution |
|---|---|---|
| `Could not find plugin StreamTestRunReusePlugin` | Only the `streamreuse` implementation class is deployed, not the discovery bridge | Deploy the bridge class, or use the fully qualified `INSERT` syntax |
| Application does not start | Invalid Spring XML, duplicate beans/jars, or a missing dependency | Use exactly one `<beans>` root, remove duplicate jars, check the first startup exception |
| `Field 'Test Run Evidence' was not found on target Test Run tracker ...` | The target tracker lacks the field | Create the exact Choice field (multi-value) and grant permissions |
| Eligible run counts look doubled | Structural parent and child runs are both being counted | Confirm you're on this version — the child-run filter (`parent != null`) must be in effect |
| `operationKey already used` / similar error on repeat render | An older registry implementation throws instead of returning the existing report | Confirm `OperationRegistry.registerOrGet` behavior is deployed |
| Selected target Test Case rejected | ID is missing, deleted, inaccessible, or belongs to the wrong tracker | Use physical IDs from the *resolved* target Test Case tracker shown in a full-scope dry run |
| "No source Test Case was found ... for target Test Case ..." | No `commonItemId` variant exists in the source tracker | Create/branch the source variant, or remove the item from the selected scope |
| Planned copies is zero | No eligible runs, or the origin is already reused | Check the item-level action and evidence IDs in the report |
| Created run is missing evidence | Field type mismatch or no write permission on the field | Validate the Choice field configuration and the invoking user's permissions |
| Status is gone after a restart | `OperationRegistry` is in-memory only (see [Known limitations](#known-limitations)) | Re-run a dry run; rely on evidence-based idempotency, which is durable |

**Recommended log searches:** `STREAM_TEST_RUN_REUSE_PLUGIN_FAILED`,
`STREAM_REUSE_RUN_INDEX`, `STREAM_REUSE_EVIDENCE_PLAN`,
`STREAM_REUSE_SOURCE_EVIDENCE_NOT_CONFIGURED`, `BeanCreationException`,
`NoSuchMethodError`, `NoClassDefFoundError`.

## Known limitations

- **`OperationRegistry` is in-memory only** — not cluster-safe, and a Codebeamer restart
  clears all operation status (evidence-based idempotency remains the durable
  duplicate-protection mechanism regardless).
- **Full-scope matching currently scans the complete source and target Test Case trackers**,
  and run indexing scans the complete source Test Run tracker — this may not scale to very
  large trackers without further query-based retrieval.
- **`latest` means "highest Test Run item ID"**, not "most recently completed" — these
  normally agree, but aren't guaranteed to under unusual ID/completion-time sequences.
- **Test Case linkage parsing assumes table 0, column 0** of the Test Run item. A source run
  linked to multiple Test Cases in that table only relinks row 0 on copy; this has no defined
  policy for multi-Test-Case rows.
- **No explicit computed-field recalculation** is performed after a copy.
- **`<CB_TOMCAT>/conf/stream-test-run-reuse.properties` on a deployed server is not actually
  read by this code.** The executor's pool size/queue capacity and the default
  mode/dry-run/preview-size values are hardcoded in
  [`StreamReuseExecutorConfiguration`](src/main/java/com/intland/codebeamer/wiki/plugins/streamreuse/StreamReuseExecutorConfiguration.java)
  and [`StreamReuseParameters`](src/main/java/com/intland/codebeamer/wiki/plugins/streamreuse/StreamReuseParameters.java)
  rather than loaded from that file. If your site has such a properties file, treat it as
  stale/documentation-only unless you wire it up (e.g. via Spring's
  `@PropertySource`/`Environment`) yourself — don't assume editing it changes runtime
  behavior.
- **The current execution trigger is Wiki rendering**, not a dedicated POST/administrative
  endpoint — be deliberate about who can edit execution-mode Wiki markup.

See [`docs/implementation-guide.md`](docs/implementation-guide.md) for the full narrative
write-up this README is distilled from, including business scenarios, the end-to-end
processing flow, and recommended future hardening.

## Repository layout

```
.
├── README.md                     This file
├── pom.xml                       Standalone Maven module for this plug-in
├── libs/                         Vendor jars go here (gitignored) — see libs/README.md
├── src/main/java/com/intland/codebeamer/wiki/plugins/
│   ├── StreamTestRunReusePlugin.java         Discovery bridge
│   └── streamreuse/                          Real implementation (17 classes)
├── src/main/resources/templates/wiki-plugin/
│   └── stream-test-run-reuse-plugin.vm       Velocity report template
├── docs/
│   ├── implementation-guide.md               Full narrative implementation guide
│   └── deployment/
│       └── spring-component-scan-snippet.xml Spring XML to merge into your context
└── legacy/                        Superseded Groovy workflow-script version (see legacy/README.md)
```
