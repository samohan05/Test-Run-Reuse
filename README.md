# Stream Test Run Reuse — Codebeamer Customization (v3.0.0)

A server-side **PTC Codebeamer** customization that performs controlled, bulk **Test Run
result reuse across streams**. Point it at a source stream and a target stream and it finds
the matching Test Case variants (by `commonItemId`), finds each one's eligible prior Test
Runs, and creates linked copies in the target stream — so a Test Case that already passed on
the Initial Stream doesn't have to be re-executed by hand on every branch stream.

As of v3.0.0 there are **two front-ends over the same reuse engine**:

- A **Dashboard Widget** (`web/streamTestRunReuseWidget.html` + `extension.json`) — a
  project-scoped, point-and-click UI with dropdowns, stream hierarchy, a searchable Test Case
  picker, and linked preview/execution results. This is the recommended way to use the
  solution going forward — see [Dashboard Widget](#dashboard-widget).
- The original **Wiki plug-in**, invoked with Wiki markup — still fully supported, unchanged,
  and useful for scripted/automated invocation:

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

Both front-ends validate every input server-side and delegate to the exact same
`StreamReuseFacade` → matching → evidence → idempotency → background-execution pipeline — see
[Architecture](#architecture).

> **Looking for the older, same-stream Groovy workflow-script version?** It has been
> superseded by this solution and is kept for reference under [`legacy/`](legacy/).

---

## Table of contents

- [What changed in v3.0.0](#what-changed-in-v300)
- [What changed in v1.2.0](#what-changed-in-v120)
- [Why this exists](#why-this-exists)
- [What it does](#what-it-does)
- [Architecture](#architecture)
- [Wiki invocation](#wiki-invocation)
- [Parameter reference](#parameter-reference)
- [Usage examples](#usage-examples)
- [Output model](#output-model)
- [Dashboard Widget](#dashboard-widget)
- [Deployment](#deployment)
- [Building from source](#building-from-source)
- [Validation checklist](#validation-checklist)
- [Troubleshooting](#troubleshooting)
- [Known limitations](#known-limitations)
- [Repository layout](#repository-layout)

---

## What changed in v3.0.0

This release adds a **Dashboard Widget** front-end alongside the existing Wiki plug-in —
nothing was removed or changed in the engine. Concretely:

- A new `com.intland.codebeamer.wiki.plugins.streamreuse.dashboard` package (7 classes):
  [`StreamReuseDashboardController`](src/main/java/com/intland/codebeamer/wiki/plugins/streamreuse/dashboard/StreamReuseDashboardController.java)
  exposes `POST /cb/api/streamReuse/philips/preview`, `POST .../execute`, and
  `GET .../status/{operationId}`, plus
  [`DashboardApiMappingProbeController`](src/main/java/com/intland/codebeamer/wiki/plugins/streamreuse/dashboard/DashboardApiMappingProbeController.java)'s
  `GET .../ping` health probe. Every one of these delegates straight into the *existing*
  `StreamReuseFacade` — none of the engine's matching/selection/evidence/idempotency logic is
  duplicated.
  [`DashboardRequestValidator`](src/main/java/com/intland/codebeamer/wiki/plugins/streamreuse/dashboard/DashboardRequestValidator.java)
  independently re-validates every browser-supplied ID (streams, trackers, project
  membership, deleted/Folder items) before anything is planned.
  [`DashboardReportResponse`](src/main/java/com/intland/codebeamer/wiki/plugins/streamreuse/dashboard/DashboardReportResponse.java)
  converts the `Report` object graph into a JSON-safe `Map` (Codebeamer's default Jackson
  setup doesn't serialize `java.time.Instant`, so timestamps come out as ISO-8601 strings).
- A `web/` folder with `extension.json` (the external-widget descriptor) and
  `streamTestRunReuseWidget.html` (a single-file, dependency-free widget UI). These are static
  assets, not part of the Maven build — they deploy to a different location than the jar; see
  [Dashboard Widget](#dashboard-widget) and [Deployment](#deployment).
- `web/cb-widget-api.js` and `web/menuitem_submenuindicator.png` are also included, as
  reference copies of the Codebeamer-shipped widget bridge script and stock icon this widget
  depends on — **neither file is authored by this project**; they're the same files already
  serving other widgets on a Codebeamer 3.2.0.2 install. Source your own site's copies rather
  than assuming these exact bytes match your install.
- No Wiki-plugin parameters changed, no engine class changed (verified byte-for-byte against
  v1.2.0), and no new Spring context entry is needed — the existing
  `base-package="com.intland.codebeamer.wiki.plugins.streamreuse"` component scan already
  covers the `dashboard` sub-package.
- `pom.xml` bumped to `3.0.0`; the consolidated jar now contains 26 classes (18 from before,
  plus the 7 dashboard classes and one controller inner class).

See [`docs/dashboard-widget-implementation-guide.md`](docs/dashboard-widget-implementation-guide.md)
for the full narrative write-up.

## What changed in v1.2.0

`CodebeamerV3StreamMetadataClient` (which resolved stream/tracker metadata by making a
self-referential HTTP call back into Codebeamer's own `/api/v3/streams/...` endpoints,
forwarding the caller's session cookie) has been replaced by
[`CodebeamerInternalStreamMetadataClient`](src/main/java/com/intland/codebeamer/wiki/plugins/streamreuse/CodebeamerInternalStreamMetadataClient.java),
which calls Codebeamer's own Spring-managed `StreamRestController` bean **in-process** instead.

This removes, entirely:
- the self-referential HTTP call and its latency,
- base-URL discovery (scheme/host/port reconstruction from the incoming request),
- a dependency on the connector port Codebeamer happens to be listening on,
- cookie forwarding, and
- the reverse-proxy / hairpin-NAT failure mode where a server can't reliably call back into
  its own public hostname.

It is otherwise behavior-compatible: `StreamTrackerResolver` now wires in the new client via
`@Qualifier("codebeamerInternalStreamMetadataClient")` (the new client is also `@Primary`),
but the `StreamMetadataClient` interface, the resolution logic, and the report's
`STREAM_VARIANT` / `SHARED_TRACKER` / `SHARED_TRACKER_FALLBACK` outcomes are unchanged. No
Wiki parameters changed. If you're upgrading from v1.1.0, see
[Deployment](#deployment) — the only new thing to deploy is the jar itself; the Spring
context entry and `.vm` template are unchanged.

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
  |     `-- CodebeamerInternalStreamMetadataClient   (in-process call to StreamRestController)
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
| `CodebeamerInternalStreamMetadataClient` / `StreamMetadataClient` | Resolves stream names and tracker-variant metadata by calling Codebeamer's own `StreamRestController` bean directly (no HTTP round-trip) | No |
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

### A second front-end: the Dashboard Widget

Everything above this line is shared. The Dashboard Widget is simply a second entry point in
front of the same `StreamReuseFacade`:

```
web/streamTestRunReuseWidget.html  (browser, iframe)
  |  POST /cb/api/streamReuse/philips/preview | /execute
  |  GET  /cb/api/streamReuse/philips/status/{id} | /ping
  v
StreamReuseDashboardController      HTTP contract, delegates
  +-- DashboardRequestValidator     independently re-validates every browser-supplied ID
  +-- DashboardCurrentUserResolver  resolves the acting UserDto defensively (no hard API dependency)
  +-- DashboardParameterMapper      maps dashboard JSON -> StreamReuseParameters.parse(...)
  +-- DashboardReportResponse       converts the Report graph to JSON-safe types (Instant -> ISO-8601)
  `-- StreamReuseFacade             <-- same engine as the Wiki plug-in, from here down
```

| Component | Responsibility | Writes to the database? |
|---|---|---|
| `StreamReuseDashboardController` | HTTP entry point: `/preview`, `/execute`, `/status/{id}` | No |
| `DashboardApiMappingProbeController` | `GET /ping` — confirms the controller is registered and reports a backend version | No |
| `DashboardReuseRequest` | JSON request DTO accepted by `/preview` and `/execute` | No |
| `DashboardRequestValidator` | Re-validates streams/trackers/project membership/selected items/Folder exclusion server-side | No |
| `DashboardCurrentUserResolver` | Resolves the authenticated `UserDto` without a hard dependency on one release's auth helper signature | No |
| `DashboardParameterMapper` | Maps `DashboardReuseRequest` to the same `StreamReuseParameters` the Wiki plug-in uses | No |
| `DashboardReportResponse` | Bean-introspects the `Report` graph into a JSON-safe `Map` | No |

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
resolved dynamically from `targetStreamId` by calling Codebeamer's own
`StreamRestController` bean in-process (the same logic behind the Stream variant API,
`.../streams/{targetStreamId}/trackers/{sourceTrackerId}/variant`, invoked directly rather
than over HTTP — see [What changed in v1.2.0](#what-changed-in-v120)). If that resolution for
the Test Run tracker doesn't produce a distinct tracker, the plug-in falls back to treating
the Test Run tracker as **shared** between source and target (this is normal/expected when
both streams record Test Runs in the same physical tracker).

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

## Dashboard Widget

The widget is a **project-scoped** Codebeamer dashboard tile. It replaces hand-written Wiki
markup with discovery-driven dropdowns, while staying server-authoritative: every ID the
browser sends is revalidated before anything is planned or executed.

### Registration

`web/extension.json` registers one `dashboardWidgets` entry with a single configured
attribute, **Project** — tracker, stream, mode, and Test Case selections are all *runtime*
choices made inside the widget, not editor-time attributes:

```json
{
  "dashboardWidgets": [{
    "enabled": "true",
    "externalWidgetConfig": {
      "id": "philips-stream-test-run-reuse-widget",
      "name": "Stream Test Run Reuse",
      "category": "test",
      "viewUrl": "http://localhost:8080/cb/custom/streamTestRunReuseWidget.html?v=1.7.4",
      "iconUrl": "http://localhost:8080/cb/custom/menuitem_submenuindicator.png",
      "attributes": {
        "project": { "typeName": "project", "fieldLabel": "Project", "required": true, "value": [] }
      }
    }
  }]
}
```

Replace the `localhost:8080` host in `viewUrl`/`iconUrl` with your own site's URL, and bump
the `?v=` query string every time you redeploy the HTML (browsers and Codebeamer's own widget
cache otherwise keep serving the old file).

### Runtime user experience

1. Add the widget to a dashboard and select a **Project**.
2. The widget calls Codebeamer's own `/cb/api/v3/...` discovery APIs — typed tracker search
   (Test Case trackers are type `102`, Test Run trackers are type `9` and filtered to
   `sharedInWorkingSet=true`), the project's streams, and `/streams/initial` — and renders a
   parent-first stream hierarchy built from each stream's `sourceStreamId`.
3. Selecting a Test Case tracker "family" resolves its physical variant in every visible
   stream (one `GET .../variant` call per stream); streams where resolution fails are omitted
   from the source/target choices, and the selected source stream is excluded from the target
   list.
4. Choose `latest` or `all` run selection, and either **all matched** or a specific set of
   **selected** target Test Cases via a searchable, filterable, dual-pane picker. Items whose
   category is `Folder` (category ID `1`, or named "Folder") are excluded client-side — and
   independently re-rejected server-side by `DashboardRequestValidator` if a crafted request
   supplies one anyway.
5. **Preview** calls `POST /preview` (`dryRun=true` under the hood) and renders the same
   summary/item-level table described in [Output model](#output-model), with Source Test
   Case, Target Test Case, Source Run, effective evidence IDs, and the project all linked to
   their Codebeamer pages (`target="_blank" rel="noopener noreferrer"`).
6. **Execute** is disabled until a current preview reports `plannedCopies > 0`. It generates a
   unique `operationKey`, calls `POST /execute`, then polls `GET /status/{operationId}` every
   ~1.2s until a terminal status, updating the same results table in place (the Outcome
   column shows `Created Test Run #<id>` linked, the skip/failure reason, or an em dash).

### HTTP API

| Method & path | Purpose | Notes |
|---|---|---|
| `GET /cb/api/streamReuse/philips/ping` | Health/version probe | Confirms the controller registered under `/streamReuse/philips` rather than accidentally doubling `/api` |
| `POST /cb/api/streamReuse/philips/preview` | Read-only plan | Maps to `dryRun=true`; same validation as execute |
| `POST /cb/api/streamReuse/philips/execute` | Queue background execution | Requires a unique `operationKey` (the widget generates one) |
| `GET /cb/api/streamReuse/philips/status/{operationId}` | Poll an operation | `404` if the ID is unknown |

Request body (`preview` and `execute` share the same shape):

```json
{
  "projectId": 67,
  "logicalTestCaseTrackerId": 147230,
  "sourceStreamId": 133465,
  "sourceTestCaseTrackerId": 147230,
  "targetStreamId": 148202,
  "targetTestCaseTrackerId": 148418,
  "testRunTrackerId": 147233,
  "targetTestCaseIds": [46105],
  "reuseMode": "all",
  "diagnostic": true
}
```

`sourceStreamId`/`targetStreamId` must be actual Stream IDs from the Streams API, not
Working-Set IDs — `DashboardRequestValidator` calls the same stream/tracker resolution the
Wiki plug-in uses and rejects anything that doesn't match.

### Security notes specific to the widget

- Same-origin browser requests use the current Codebeamer session; no credentials are
  embedded in the widget.
- The server revalidates every browser-supplied ID — see `DashboardRequestValidator` above.
- Navigation links use `target="_blank" rel="noopener noreferrer"`; error text is escaped
  before insertion into the widget's HTML.
- CSRF protection must remain enabled for the mutation endpoints (`/execute`); use
  Codebeamer's approved same-origin request mechanism rather than disabling it.
- `DashboardReportResponse`'s JSON conversion is local to these three endpoints — it does
  **not** register a global Jackson `JavaTimeModule` or otherwise change how the rest of
  Codebeamer serializes responses.

See [`docs/dashboard-widget-implementation-guide.md`](docs/dashboard-widget-implementation-guide.md)
for the full guide (discovery APIs in detail, acceptance tests, troubleshooting, rollback).

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
| The compiled customization jar (this module's classes merged into, or alongside, your site's customization jar — see [Building from source](#building-from-source) for a ready-to-use standalone build) | `<CB_WEBAPP>/WEB-INF/lib/` |
| [`stream-test-run-reuse-plugin.vm`](src/main/resources/templates/wiki-plugin/stream-test-run-reuse-plugin.vm) | `<CB_WEBAPP>/config/templates/wiki-plugin/` |
| [`web/streamTestRunReuseWidget.html`](web/streamTestRunReuseWidget.html) | `<CB_WEBAPP>/custom/` |
| `web/cb-widget-api.js`, `web/menuitem_submenuindicator.png` (reference copies only — see [What changed in v3.0.0](#what-changed-in-v300)) | `<CB_WEBAPP>/custom/`, alongside the HTML above |
| [`web/extension.json`](web/extension.json) | your site's configured `externalWidgetExtensions` location (on the environment this was built against, that was `<CB_WEBAPP>/assets/extension.json` — confirm yours) |
| The `<context:component-scan>` entry below | merge into your **single** customer Spring context, typically `<CB_WEBAPP>/WEB-INF/classes/my-applicationContext.xml` |

An entry in `my-applicationContext.xml` is required for Spring to discover this solution's
`@Component`/`@Configuration`/`@RestController` beans — without it, the application still
starts, but every Wiki invocation fails with "The Codebeamer application context is
unavailable" (or a `NoSuchBeanDefinitionException` for `StreamReuseFacade`), and the dashboard
`/ping` endpoint 404s. **No separate entry is needed for the `dashboard` sub-package** — the
existing `base-package="com.intland.codebeamer.wiki.plugins.streamreuse"` scan already covers
it; do not add a second, narrower scan. Two reference versions of the Spring XML are provided:

- [`docs/deployment/spring-component-scan-snippet.xml`](docs/deployment/spring-component-scan-snippet.xml) —
  the minimal change, containing only what this plug-in needs. Use this as the template for
  merging into your own context.
- [`docs/deployment/my-applicationContext.xml.example`](docs/deployment/my-applicationContext.xml.example) —
  a real, deployed `my-applicationContext.xml` from a live site running this plug-in,
  included as a concrete worked example of what the finished file looks like. It also
  contains two beans for *other*, unrelated Philips customizations on that site — don't copy
  those unless you're deploying those customizations too; only the
  `<context:component-scan base-package="com.intland.codebeamer.wiki.plugins.streamreuse"/>`
  element is relevant here.

> **Do not leave two customization jars that both define classes under**
> **`com.intland.codebeamer.wiki.plugins.streamreuse` on the classpath** — remove or archive
> the previous jar before deploying a new one.

### Deployment procedure

1. Back up the current customization jar, VM template, widget HTML/descriptor, and Spring
   context (capture timestamps/sizes/checksums if you want a clean rollback path).
2. Stop Codebeamer/Tomcat.
3. Deploy the new jar to `WEB-INF/lib/`.
4. Deploy `stream-test-run-reuse-plugin.vm` to `config/templates/wiki-plugin/`.
5. Deploy `streamTestRunReuseWidget.html` (and `cb-widget-api.js`/the icon, if not already
   present) to `custom/`.
6. Publish the matching `extension.json` through your site's `externalWidgetExtensions`
   mechanism.
7. Merge the component-scan snippet into your existing customer Spring context (one
   `<beans>` root only) — skip this step if it's already there from a prior release.
8. Clear Tomcat's work/cache directories if your environment requires it.
9. Start Codebeamer and watch the startup log for Spring/bean errors.
10. Confirm the fully-qualified `INSERT` syntax renders on a Wiki page, and the short-name
    syntax renders through the discovery bridge.
11. Open `/cb/api/streamReuse/philips/ping` and confirm it responds (verifies the dashboard
    controller registered correctly).
12. Clear `externalWidgetExtensionConfigCache`, hard-refresh, and add/re-add the widget to a
    dashboard if stale attributes remain.
13. Run a dry run / Preview smoke test (either front-end) before ever executing for real.

### Rollback

1. Stop Codebeamer/Tomcat.
2. Remove the new customization jar.
3. Restore the previous jar, VM template, widget HTML/descriptor, and Spring context as a
   matching set — don't mix a new model class with an old Velocity template or widget build,
   or vice versa.
4. Start Codebeamer and re-run the smoke tests above, including `/ping` if the dashboard was
   deployed.

## Building from source

```bash
mvn clean package
```

See [`libs/README.md`](libs/README.md) for how to populate the system-scoped Codebeamer/Spring
jars this module compiles against — they are not published to Maven Central and are not part
of this repository. This `pom.xml` has been verified to produce a working, self-contained
`target/stream-test-run-reuse-plugin-3.0.0.jar` (26 classes: the 18 from the Wiki plug-in and
engine, plus the 7-class `dashboard` package and one controller inner class, plus the Velocity
template) once those jars are in place. This jar is Java-only — the widget's HTML/JSON assets
under `web/` are a separate deployment artifact; see [Deployment](#deployment).

```bash
# Verify the expected classes are present in the built jar:
jar tf target/stream-test-run-reuse-plugin-3.0.0.jar | findstr /I "streamreuse dashboard StreamTestRunReusePlugin"

# Capture a checksum for your deployment record:
certutil -hashfile target/stream-test-run-reuse-plugin-3.0.0.jar SHA256
```

This jar is self-contained and additive: it only defines classes under
`com.intland.codebeamer.wiki.plugins(.streamreuse)`, so you can either drop it into
`WEB-INF/lib/` as its own jar alongside your site's other customization jars, or merge its
classes into an existing customization jar if your site prefers a single jar. Either way,
remember the "do not leave two jars defining the same classes" rule above.

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
- [ ] `GET /cb/api/streamReuse/philips/ping` returns `200` with the expected backend version.
- [ ] The widget loads, resolves the configured Project, and renders typed trackers, the
      stream hierarchy, and tracker-variant resolution.
- [ ] Folder-category items cannot be selected in the picker, and are rejected server-side
      even if a crafted request supplies one.
- [ ] Preview returns `200` with `createdCopies == 0`; Execute stays disabled until a current
      preview reports `plannedCopies > 0`.
- [ ] A created row's Outcome links to the new Test Run; Project/Test Case/Run links open in
      a new tab.

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
| Raw 404 in the widget iframe | `viewUrl` or the deployed static file path doesn't match | Open the exact HTML URL directly; confirm `extension.json` and the HTML version match |
| Widget shows "Project not configured" | `getConfig()` payload wasn't unwrapped, or the widget ID in `extension.json` doesn't match the HTML's `WidgetApi` constructor ID | Inspect the config payload in dev tools; confirm the IDs match exactly |
| No Preview endpoint / "No mapping for POST" | `StreamReuseDashboardController` isn't registered, or its mapping duplicates `/api` | Hit `/ping`; confirm `@RequestMapping("/streamReuse/philips")`, not `/api/streamReuse/philips` |
| "Stream not found" from the dashboard | A Working-Set ID was submitted instead of an actual Stream ID | Submit IDs from `/cb/api/v3/streams`, not Working-Set IDs |
| HTTP 500 on a Preview/Execute response | `Instant` serialization failure | Confirm the deployed jar includes the JSON-safe `DashboardReportResponse` |
| Widget selectors stay empty | Tracker-variant resolution failed, or no Test Case tracker family was selected | Check the `/variant` network calls and the selected logical tracker in dev tools |
| `/ping` reports an old version | An old jar is still active, or two jars define the same classes | Find and remove the duplicate jar, then fully restart |

**Recommended log searches:** `STREAM_TEST_RUN_REUSE_PLUGIN_FAILED`,
`STREAM_REUSE_RUN_INDEX`, `STREAM_REUSE_EVIDENCE_PLAN`,
`STREAM_REUSE_SOURCE_EVIDENCE_NOT_CONFIGURED`, `BeanCreationException`,
`NoSuchMethodError`, `NoClassDefFoundError`, and (dashboard-specific)
`STREAM_REUSE_DASHBOARD_PREVIEW`, `STREAM_REUSE_DASHBOARD_EXECUTE`,
`STREAM_REUSE_DASHBOARD_FAILED`.

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
- **Dashboard-specific:** the Streams API call is paged at 100 per request; the target Test
  Case picker requests up to 500 items; tracker-variant discovery makes one request per
  visible stream (so a project with many streams means many `/variant` calls when a Test Case
  tracker family is selected). None of these are currently paged further.

See [`docs/implementation-guide.md`](docs/implementation-guide.md) (Wiki plug-in) and
[`docs/dashboard-widget-implementation-guide.md`](docs/dashboard-widget-implementation-guide.md)
(Dashboard Widget) for the full narrative write-ups this README is distilled from.

## Repository layout

```
.
├── README.md                     This file
├── pom.xml                       Standalone Maven module (Java only; builds the jar)
├── libs/                         Vendor jars go here (gitignored) — see libs/README.md
├── src/main/java/com/intland/codebeamer/wiki/plugins/
│   ├── StreamTestRunReusePlugin.java         Discovery bridge
│   └── streamreuse/                          Engine + Wiki plug-in (17 classes)
│       └── dashboard/                        Dashboard Widget backend (7 classes)
├── src/main/resources/templates/wiki-plugin/
│   └── stream-test-run-reuse-plugin.vm       Velocity report template (Wiki plug-in only)
├── web/                           Dashboard Widget static assets (NOT part of the jar)
│   ├── extension.json                        External widget registration descriptor
│   ├── streamTestRunReuseWidget.html         The widget UI
│   ├── cb-widget-api.js                      Codebeamer-shipped bridge script (reference copy)
│   └── menuitem_submenuindicator.png         Stock Codebeamer icon (reference copy)
├── docs/
│   ├── implementation-guide.md                      Wiki plug-in narrative guide
│   ├── dashboard-widget-implementation-guide.md     Dashboard Widget narrative guide
│   └── deployment/
│       ├── spring-component-scan-snippet.xml  Minimal Spring XML to merge into your context
│       └── my-applicationContext.xml.example  A real deployed context, as a worked example
└── legacy/                        Superseded Groovy workflow-script version (see legacy/README.md)
```
