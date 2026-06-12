# Test Run Reuse — Codebeamer Workflow Script

A statically-typed (`@TypeChecked`) Groovy workflow script for **PTC Codebeamer** that
automates **test-run reuse**. When it fires for a Test Case, it creates a brand-new
Test Run and pre-populates it from the **most recent previous run** of the same test
case — copying the *Test Step Results* table, carrying the previous result forward, and
linking the previous run as *evidence*.

The intent is to avoid re-executing a test that has already been run: instead of running
the steps again, you reuse the last known result and keep a traceable link back to the
run it came from.

> The script talks to Codebeamer over the **REST API v3** (plus one legacy `/cb/api`
> endpoint). It runs server-side as a workflow action/event handler.

---

## What it does

The script is triggered by a Codebeamer workflow event whose *secondary source* is a
**Test Case** item. On each run it performs the following steps:

| Step | Action | Endpoint |
|------|--------|----------|
| 1 | **Create** a new Test Run for the current Test Case | `POST /cb/api/v3/trackers/{trackerId}/testruns` |
| 2 | Resolve the Test Case's **`commonItemId`** | `GET /cb/api/v3/items/{testCaseId}` |
| 3 | Fetch the **latest previous Test Run** for that common item | `POST /cb/api/testCases/{commonItemId}/testRuns?page=1&pageSize=1` |
| 3.5 | **Copy the "Test Step Results" table** (field `2000000`) from the previous run into the new run | `GET /cb/api/v3/items/{lastRunId}/fields` → `PUT /cb/api/v3/items/{newRunId}/fields?quietMode=false` |
| 4 | **Set the result + "Test Run Evidence"** (field `1000`) on the new run's row, linking it to the previous run | `PUT /cb/api/v3/testruns/{newRunId}` |

On success it sets a workflow `message` attribute such as:

```
SUCCESS: Created Test Run #<id>, copied result 'PASSED' from commonItemId=<id>
```

### How it works

- The script is invoked at the bottom via `execute(applicationContext, event)` — the two
  bindings Codebeamer injects into workflow/event scripts.
- The Test Case it acts on is read from `event.getSecondarySource()`.
- All HTTP work goes through a single helper, `TestRunHelper.doRequest(...)`, which handles
  authentication and returns `{ status, text }`.
- **Idempotency guard:** the script sets the request attribute `cb.testrun.created` once it
  succeeds and returns early if it sees that flag, so it will not run twice within the same
  request.
- Any non-2xx response throws a `VetoException` with the failing status and response body,
  which surfaces the error in the Codebeamer UI.

---

## Prerequisites

- **PTC Codebeamer** with the REST API v3 enabled.
- A **Test Run tracker** to create runs in (default tracker id `107933`, named *MR Test Run*
  in the reference instance).
- The following **custom fields** must exist on the Test Run / Test Case Run items
  (the field IDs are instance-specific — change them to match yours; see
  [Configuration](#configuration)):
  - **`2000000` — "Test Step Results"** (a `TableFieldValue`). This is the standard
    Codebeamer test-step-results table.
  - **`1000` — "Test Run Evidence"** (a `ChoiceFieldValue` holding a
    `TrackerItemReference`). Used to link the new run back to the run it was copied from.
- An account (or token) with permission to read test cases/runs and create/update runs.

---

## Configuration

All deployment-specific values are read from **environment variables** of the Codebeamer
server's JVM process, with safe defaults baked in. **No credentials are stored in the
script.**

| Variable | Purpose | Default |
|----------|---------|---------|
| `CB_BASE_URL` | Base URL of your Codebeamer instance | `http://localhost:8080` |
| `CB_TEST_RUN_TRACKER_ID` | Tracker ID that new Test Runs are created in | `107933` |
| `CB_PAT` | Personal Access Token (preferred auth) | _(empty)_ |
| `CB_USER` | Username for HTTP Basic auth (fallback) | _(empty)_ |
| `CB_PASS` | Password for HTTP Basic auth (fallback) | _(empty)_ |

Two values remain as constants in the script because they map to custom fields rather than
to the deployment environment. Edit them in `Test_Run_Reuse.groovy` if your field IDs
differ:

- `2000000` — the "Test Step Results" table field (Step 3.5).
- `1000` — the "Test Run Evidence" reference field (Step 4).

### Authentication

`TestRunHelper.doRequest` picks the first method that is configured, in this order:

1. **Personal Access Token** (recommended) — set `CB_PAT`. Sent as `Authorization: Bearer <token>`.
2. **HTTP Basic** — set **both** `CB_USER` and `CB_PASS`. Sent as `Authorization: Basic <base64>`.
3. **Session cookie** — only if you flip `USE_SESSION_COOKIE` to `true` in the script; reuses
   the caller's `JSESSIONID`.

> **Set these on the Codebeamer server process**, since `System.getenv(...)` reads the
> server JVM's environment (e.g. via your service wrapper / `setenv` script / container env).
> A PAT is preferred over Basic auth so a password is never placed in the environment.

---

## Installation

1. Open Codebeamer and go to the tracker whose workflow should trigger the reuse
   (the tracker that holds your Test Cases).
2. Edit the **workflow** and add (or open) the **transition / state action** that should run
   this automation.
3. Add a **script action** and paste the entire contents of
   [`Test_Run_Reuse.groovy`](Test_Run_Reuse.groovy).
4. Make sure the action is wired so that the **Test Case** item is delivered as the event's
   *secondary source*.
5. Set the [environment variables](#configuration) on the Codebeamer server and restart the
   service so the new environment is picked up.
6. Adjust the tracker ID and the two custom-field IDs if your instance differs.

---

## Examples

The [`examples/`](examples) folder contains real request/response payloads captured from a
Codebeamer instance, to illustrate the exact JSON shapes the script reads and writes:

| File | Corresponds to |
|------|----------------|
| `get_source_run_item.json` | `GET /cb/api/v3/items/{id}` for the **source** (previous) run |
| `get_target_run_item.json` | `GET /cb/api/v3/items/{id}` for the **target** (newly created) run, showing the linked *Test Run Evidence* |
| `get_item_fields_response.json` | `GET /cb/api/v3/items/{id}/fields` — note the *Test Step Results* table lives under `readOnlyTableFields` (field `2000000`) |
| `put_test_step_results.json` | Body for `PUT /cb/api/v3/items/{newRunId}/fields` (Step 3.5) |
| `put_item_fields.json` | Reference body for a fuller item-fields update |

> These samples contain example item IDs from a test instance — they are for reference only
> and are not used at runtime.

---

## Troubleshooting

The script fails fast with a `VetoException` whose message includes the HTTP status and the
response body. Common cases:

- **`Failed to create Test Run. status=401/403`** — authentication or permissions. Verify
  `CB_PAT` (or `CB_USER`/`CB_PASS`) and that the user may create runs in the tracker.
- **`commonItemId missing for testCase=...`** — the item returned by Step 2 had no
  `commonItemId`; confirm the secondary source really is a Test Case.
- **`No previous runs found for commonItemId=...`** — there is no prior run to reuse. The
  test case must have been run at least once before.
- **`STEP 3.5: fieldId 2000000 not found in readOnlyTableFields`** — the *Test Step Results*
  field ID differs in your instance; update the `2000000` constant.
- **`Step 4 failed (row update via parent). status=...`** — check the *Test Run Evidence*
  field ID (`1000`) and that it accepts a `TrackerItemReference`.

---

## Security note

Never commit credentials. This script reads all secrets from environment variables and ships
with empty defaults for `CB_PAT` / `CB_USER` / `CB_PASS`. Prefer a **Personal Access Token**
over a username/password.
