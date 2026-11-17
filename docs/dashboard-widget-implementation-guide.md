# Stream Test Run Reuse — Dashboard Widget Implementation Guide (v4.0.0 / widget build 1.7.11)

This is the full narrative implementation guide for the Dashboard Widget front-end, which
shipped in v3.0.0 alongside the existing Wiki plug-in. Where the
[repository root README](../README.md) already covers something at reference level (HTTP API
table, request/response shapes, deployment steps, troubleshooting table), this guide links to
it instead of repeating it, and focuses on the business rationale and end-to-end mechanics.
For the shared reuse engine itself (matching, run selection, evidence, idempotency,
background execution), see [`docs/implementation-guide.md`](implementation-guide.md) — none
of that changed for this release.

## 1. Executive summary

The Stream Test Run Reuse Dashboard Widget provides a guided Codebeamer dashboard experience
for previewing and executing Test Run result reuse between stream variants. The widget
replaces manually maintained Wiki invocation syntax with a project-scoped user interface that
discovers Test Case trackers, shared Test Run trackers, streams, tracker variants, and
selectable target Test Cases.

The solution remains server-authoritative. Browser selections are submitted to custom preview
and execute endpoints, validated against Codebeamer streams and trackers, and delegated to the
existing reuse engine (`StreamReuseFacade` and everything beneath it). The engine itself is
unchanged by this release: Test Case variant matching, executable child-run filtering,
latest/all run selection, Test Run Evidence propagation, origin-aware idempotency, background
execution, per-copy transactions, and operation-status reporting are all exactly what the
Wiki plug-in already used.

External Widget editor stores only the selected Codebeamer project. Runtime selectors cover
Test Case tracker, shared Test Run tracker, source stream, target stream, reuse mode, target
Test Case scope, and target Test Cases. Preview performs no writes and returns summary and
item-level planning results. Execute submits a unique operation, then the widget polls status
until completion. Source Test Cases, target Test Cases, source runs, evidence items, created
Test Runs, and the project are all navigable through links.

## 2. Business purpose and scope

Product-line and stream-based test management creates multiple physical Test Case variants
representing the same logical Test Case. Execution evidence generated in one stream may need
to be reused in another stream without re-executing or manually recreating the Test Run
history. The widget makes that operation reviewable, selective, permission-aware, and
repeat-safe — without anyone needing to know the Wiki invocation syntax or the underlying
stream/tracker IDs by heart.

### 2.1 In scope

- Initial-to-child, child-to-initial, parent-to-child, child-to-parent, and
  sibling-to-sibling reuse.
- Project-scoped tracker and stream discovery.
- Typed Test Case and Test Run tracker selection.
- Source/target physical tracker resolution through the Streams API.
- All matched target Test Cases, or explicit multi-selection.
- Latest eligible child run, or all eligible child runs.
- Preview, execute, asynchronous status, item-level results, and hyperlinks.
- Folder information-item exclusion from target Test Case selection.

### 2.2 Out of scope

- Same-stream duplication.
- Automatic creation of missing target Test Case variants.
- Deletion or resynchronization of previously created copies.
- One transaction for the entire bulk operation.
- Durable operation recovery after a JVM restart in the current in-memory registry.
- Automatic execution triggered solely by stream creation.

## 3. Architectural boundary

The widget is the user interface and request orchestrator. The Java customization is the
authority for validation, planning, evidence, idempotency, and mutation.
**Browser-provided IDs must never be trusted without server-side revalidation** — this is why
`DashboardRequestValidator` independently re-resolves and re-checks every stream, tracker, and
Test Case ID the widget submits, rather than trusting what the browser already "knows" from
its own discovery calls.

## 4. Project, tracker, and stream discovery

### 4.1 Project configuration

`WidgetApi.getConfig()` returns a payload whose user configuration is available through its
`config` value. The widget normalizes object and JSON-string representations, extracts the
Project, and calls `GET /cb/api/v3/projects/{projectId}` to retrieve the real project name for
display and linking.

### 4.2 Typed tracker search

```
POST /cb/api/v3/projects/{projectId}/trackers/search
{
  "deleted": false,
  "hidden": false,
  "types": [
    {"id": 102, "name": "Testcase", "type": "TrackerTypeReference"},
    {"id": 9, "name": "Testrun", "type": "TrackerTypeReference"}
  ]
}
```

The response supplies authoritative tracker-type metadata — the widget does not infer tracker
categories from English names. Test Run choices are further restricted to
`sharedInWorkingSet=true`.

### 4.3 Project streams

```
GET /cb/api/v3/streams?projectId={projectId}&page=0&pageSize=100
GET /cb/api/v3/streams/initial
```

The Streams API returns actual Stream IDs, names, descriptions, colors, hidden state,
`sourceStreamId`, and `initialStream`. **Working-Set IDs must not be used as Stream IDs** — if
`/streams/initial` disagrees with what's already present, the widget treats the Initial Stream
endpoint as authoritative.

## 5. Stream hierarchy and tracker variant resolution

The widget builds an ID index and child lists from `sourceStreamId`, sorts siblings, and
performs a parent-first depth-first traversal. Each child is displayed immediately below its
parent. Streams with `sourceStreamId=null` remain root streams; the widget does not invent
parentage.

```
GET /cb/api/v3/streams/{streamId}/trackers/{logicalTrackerId}/variant
```

After the logical Test Case tracker is selected, the widget resolves the physical tracker
variant in every visible stream — one request per stream. Streams where resolution fails are
omitted from source and target choices. The selected source stream is omitted from the target
list.

## 6. Target Test Case selection

```
GET /cb/api/v3/items/query?page={pickerPage}&pageSize=200
    &queryString=project.id IN ({projectId}) AND tracker.id IN ({targetTrackerId})
```

The full-screen picker loads physical Test Case items from the selected target tracker through
Codebeamer's item-query API, constrained by both project and tracker, and server-paged at 200
items per page (as of build 1.7.11 — see [Appendix F](#appendix-f--widget-ui-version-history-build-1711-progress-normalization-fix));
selections made on earlier pages persist across page navigation.

### 6.1 Search and filter behavior

Search spans physical ID, `commonItemId`, name, description, status, priority, creator, and
modifier. Users can filter by status, select/clear all visible items, mark multiple
available/selected rows, move marked rows between the Available and Selected panes, and apply
the selection to persist the physical target Test Case IDs.

### 6.2 Folder exclusion

Items are excluded when their categories contain category ID `1` or a category named
"Folder" (case-insensitively). Other categories (e.g. Functional, Integration) remain
selectable. **The server repeats this validation** and must reject a Folder item even if a
crafted request supplies its ID directly — see `DashboardRequestValidator.isFolder(...)` in
the [root README's architecture section](../README.md#a-second-front-end-the-dashboard-widget).

## 7. Preview and execute processing

Preview submits the complete scope to `POST /cb/api/streamReuse/philips/preview`. The
dashboard controller validates the browser-supplied identifiers, resolves the current user,
maps the request to `StreamReuseParameters` with `dryRun=true`, and delegates to
`StreamReuseFacade`. No copy is created. The results table is titled "Reuse preview and
execution details" because the same table is updated after execution, in place.

Execute uses the same scope plus a unique `operationKey` and maps the request to
`dryRun=false`. The facade reserves or retrieves the operation, queues the existing background
job, and returns an operation report. The widget then polls the status endpoint until a
terminal state. The full request/response shapes and endpoint table are in the
[README's Dashboard Widget section](../README.md#dashboard-widget).

## 8. JSON response serialization

The reuse domain report includes `java.time.Instant` timestamps. Codebeamer's active Jackson
mapper does not serialize Java Time types by default in this environment.
`DashboardReportResponse` converts the report bean graph to JSON-safe maps and lists,
formatting `TemporalAccessor` values with `toString()`, which yields ISO-8601 timestamps, e.g.:

```json
{
  "startedAt": "2026-11-14T07:36:33.063Z",
  "completedAt": "2026-11-14T07:36:35.814Z"
}
```

> **Isolation:** this conversion does not modify Codebeamer's global `ObjectMapper` or
> register `JavaTimeModule` globally. It applies only to the `/preview`, `/execute`, and
> `/status` responses.

## 9. Performance, concurrency, and reliability

| Area | Current design | Operational implication |
|---|---|---|
| Stream discovery | Up to 100 streams in one request | Increase paging only after testing |
| Variant discovery | One request per stream after tracker-family selection | Acceptable for moderate stream counts; consider aggregation for large projects |
| Item selector | Up to 500 target Test Cases | Add paging/server-side search for larger trackers |
| Run indexing | Scans the shared Test Run tracker (unchanged engine behavior) | Profile large trackers |
| Executor | Core 1, max 2, queue 20 (unchanged) | Intentionally limits database pressure |
| Transactions | `REQUIRES_NEW` per copied run (unchanged) | One failure does not roll back prior successful copies |
| Operation registry | In memory (unchanged) | Restart loses UI status, not evidence-based duplicate protection |

## 10. Build and packaging

```bash
mvn clean package
jar tf target/stream-test-run-reuse-plugin-3.0.0.jar | findstr /I "streamreuse dashboard"
certutil -hashfile target/stream-test-run-reuse-plugin-3.0.0.jar SHA256
```

Required JAR entries (a subset — see the [README's Repository layout](../README.md#repository-layout)
for the full class list):

```
com/intland/codebeamer/wiki/plugins/streamreuse/dashboard/StreamReuseDashboardController.class
com/intland/codebeamer/wiki/plugins/streamreuse/dashboard/DashboardApiMappingProbeController.class
com/intland/codebeamer/wiki/plugins/streamreuse/dashboard/DashboardReportResponse.class
com/intland/codebeamer/wiki/plugins/streamreuse/StreamReuseFacade.class
com/intland/codebeamer/wiki/plugins/streamreuse/CodebeamerInternalStreamMetadataClient.class
```

> **Version-set rule:** deploy the jar, HTML, and `extension.json` as a matching release set.
> Do not mix a new HTML build with an older descriptor, or a new controller with an older
> response converter.

## 11. Deployment and configuration

See the [root README's Deployment section](../README.md#deployment) for the full artifact
table and step-by-step procedure, and
[`docs/deployment/spring-component-scan-snippet.xml`](deployment/spring-component-scan-snippet.xml) /
[`docs/deployment/my-applicationContext.xml.example`](deployment/my-applicationContext.xml.example)
for the Spring wiring (the same scan that already covers the Wiki plug-in covers this
`dashboard` sub-package too — no second entry is needed).

### 11.1 Widget cache administration

- Increment the `viewUrl` query version (`?v=...`) after every HTML update.
- Clear `externalWidgetExtensionConfigCache` after any `extension.json` change.
- Remove and re-add the widget on a dashboard if stale attributes persist.
- Verify the iframe `src` and network requests in browser developer tools when debugging.

## 12. Validation and acceptance tests

| Test group | Required cases |
|---|---|
| Registration | `/ping` returns 200; widget loads; Project configuration resolves |
| Discovery | Typed trackers, initial stream, hidden-stream filtering, hierarchy, variant resolution |
| Selection | Folder exclusion, search, status filter, multi-select, selected/all scope |
| Preview | Initial→child, child→initial, sibling, parent→child, latest, all, selected, full scope |
| Evidence | Immediate source, inherited (one), inherited (multiple), fallback |
| Idempotency | Already-reused detection; a new `operationKey` does not bypass it |
| Execution | Queued, running, completed, completed-with-errors, failed |
| Navigation | Project, Test Case, source run, evidence, and created-run links all open correctly |
| Restart | Operation status loss is documented; Preview still detects existing evidence |

## 13. Troubleshooting

See the [root README's Troubleshooting table](../README.md#troubleshooting) — the
dashboard-specific rows are included there alongside the Wiki plug-in's.

## 14. Monitoring and operations

- Capture operation ID, operation key, source/target streams, selected scope,
  planned/created/skipped/failed counts, start/completion timestamps, and jar/widget versions
  for every approved execution.
- Monitor executor queue saturation and database pressure during large `all`-mode operations.
- Keep Preview as the mandatory operating step before every Execute.
- Use a deliberate, unique operation key for every approved execution request.
- Retain the completed report output as implementation or validation evidence.
- After a restart, use Preview to reconstruct durable business state through evidence
  idempotency — operation status itself is not durable (see
  [Known limitations](../README.md#known-limitations)).

## 15. Upgrade, maintenance, and rollback

See the [root README's Deployment → Rollback section](../README.md#rollback). In addition,
for this front-end specifically:

- Keep the HTML, `extension.json`, and the jar's dashboard classes together in source control
  as a matching set (this repository does that for you going forward).
- Record the browser versions used for dashboard validation, alongside the usual Codebeamer
  release/JDK/build-tool/jar-checksum record.
- Regression-test the discovery APIs, controller mappings, response serialization, tracker
  variant resolution, and item-query behavior after every Codebeamer upgrade.

## Appendix A — Component inventory

| Artifact | Purpose | Typical location |
|---|---|---|
| `extension.json` | External widget registration and Project attribute | Your site's `externalWidgetExtensions` location |
| `streamTestRunReuseWidget.html` | Complete same-origin widget UI | `<CB_WEBAPP>/custom/` |
| `cb-widget-api.js` | Iframe-to-Codebeamer host communication (Codebeamer-shipped, not authored here) | Beside the widget HTML |
| `menuitem_submenuindicator.png` | Widget icon used by the current descriptor (stock Codebeamer asset) | `<CB_WEBAPP>/custom/` |
| `StreamReuseDashboardController` / `DashboardApiMappingProbeController` | HTTP contract | Customization jar |
| `DashboardReuseRequest` / `DashboardParameterMapper` / `DashboardRequestValidator` / `DashboardCurrentUserResolver` / `DashboardReportResponse` | Request mapping, validation, user resolution, response conversion | Customization jar |
| Reused engine package (`CodebeamerInternalStreamMetadataClient`, `StreamTrackerResolver`, `TestCaseMatcher`, `TestRunSelector`, `EvidencePropagationService`, `IdempotencyService`, `StreamReusePlanner`, `StreamReuseFacade`, `OperationRegistry`, `StreamReuseJob`, `StreamReuseCopyService`, `StreamReuseExecutorConfiguration`, `StreamReuseModels`) | Everything described in [`docs/implementation-guide.md`](implementation-guide.md) | Customization jar |

## Appendix B — Endpoint reference

| Endpoint | Method | Used for |
|---|---|---|
| `/cb/api/v3/projects/{id}` | GET | Project name and link label |
| `/cb/api/v3/projects/{id}/trackers/search` | POST | Typed Test Case and Test Run trackers |
| `/cb/api/v3/streams?projectId=...` | GET | Project streams |
| `/cb/api/v3/streams/initial` | GET | Initial Stream ID |
| `/cb/api/v3/streams/{stream}/trackers/{tracker}/variant` | GET | Physical tracker variant |
| `/cb/api/v3/items/query` | GET | Target Test Case items |
| `/cb/api/streamReuse/philips/ping` | GET | Backend health/version |
| `/cb/api/streamReuse/philips/preview` | POST | Read-only plan |
| `/cb/api/streamReuse/philips/execute` | POST | Background execution |
| `/cb/api/streamReuse/philips/status/{operationId}` | GET | Operation polling (full report) |
| `/cb/api/streamReuse/philips/status/{operationId}/summary` | GET | Operation polling (lean progress snapshot; widget tries this first, falls back to the full report on `404`) |

## Appendix C — Status and action reference

Shared with the Wiki plug-in — see the
[README's Output model section](../README.md#output-model).

## Appendix D — Deployment quick reference

1. Build the customization jar.
2. Verify required classes and SHA-256.
3. Stop Codebeamer.
4. Replace the jar and remove duplicates.
5. Deploy the matching HTML and `extension.json`.
6. Start Codebeamer.
7. Verify `/cb/api/streamReuse/philips/ping`.
8. Clear the external widget cache.
9. Confirm the widget build/version shown in its UI.
10. Run Preview.
11. Approve and Execute with a unique operation key.
12. Capture the completed report as evidence.

## Appendix E — Widget UI version history (build 1.7.4, "linked results")

The HTML build deployed in this release ("V1.7.4-LinkedResults") added, on top of the initial
widget UI:

- Loads the configured project through `GET /cb/api/v3/projects/{projectId}` and displays the
  real project name and ID, linked to `/cb/project/{projectId}` in a new tab.
- Renames "Preview details" to "Reuse preview and execution details" (the same table is reused
  for both preview and post-execution display).
- Source Test Case, Target Test Case, Source Run, effective evidence IDs, and created Test Run
  IDs all link to `/cb/issue/{id}` in a new tab.
- Renames the final results column to "Outcome / Created Item": created rows show
  `Created Test Run #<id>` (linked), skip/failure rows show their reason, and empty outcomes
  show an em dash.
- Preserves everything from before: Streams API hierarchy, Folder exclusion, the large picker,
  preview, execution, and status polling.

## Appendix F — Widget UI version history (build 1.7.11, "progress normalization fix")

The HTML build deployed in v4.0.0 ("V1.7.11-progress-normalization-fix") added, on top of
build 1.7.4:

- The target Test Case picker is server-paged (200 items/page) with Previous/Next controls and
  "Select all on page" / "Clear all selections", instead of loading up to 500 items in one
  request; the picker modal now runs full-screen.
- Clicking **Execute** shows a confirmation dialog (planned copy count, source/target stream
  names, reuse mode, and an extra warning above 500 planned copies) before calling `/execute`.
- A live progress overlay during execution: a progress bar; created/already-reused/failed/
  remaining counts; elapsed time and an estimated-time-remaining calculation; the Test Run and
  target Test Case currently being processed; and the operation ID.
- Status polling tries the new lean `GET .../status/{operationId}/summary` endpoint first and
  falls back to the full `GET .../status/{operationId}` report on `404`. The poll interval is
  now adaptive — roughly every 3s for the first 30 seconds, every 5s up to 5 minutes, every 10s
  beyond that, and every 15s whenever the browser tab is hidden — rather than a fixed ~1.2s.
- The results table gained a filter (All / Created / Already reused / Failed / Planned) and its
  own pagination (100 rows/page); **Execute** is only enabled while the current filtered view
  still has plannable (`CREATE`) rows.
- Preserves everything from build 1.7.4: the project link, the renamed results table and
  "Outcome / Created Item" column, and linked IDs throughout.
