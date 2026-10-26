# Legacy: Groovy workflow-script version (superseded)

This folder preserves the **original** Test Run Reuse implementation for historical
reference. It is superseded by the Codebeamer Wiki plug-in documented in the
[repository root README](../README.md) and is no longer the recommended solution.

## How this differed from the current plug-in

| | Legacy (this folder) | Current (`src/`) |
|---|---|---|
| Mechanism | Groovy workflow/event script, pasted into a tracker's workflow transition | Server-side Codebeamer Wiki plug-in, invoked from a Wiki page |
| Trigger | Fires automatically on a workflow transition | Explicit, on-demand Wiki page render (dry run or execute) |
| Scope | One Test Case at a time, same stream | Bulk, cross-stream (parent/child/sibling), all matched Test Cases or an explicit selected list |
| Source of the "previous run" | Single most recent run of the same Test Case | Matched via `commonItemId` across stream variants, with explicit latest/all selection and structural-parent-run exclusion |
| Safety | No dry-run mode; runs for real as soon as the transition fires | Defaults to a read-only dry run; execution requires an explicit `operationKey` |
| Auth | Environment-variable credentials (PAT / Basic) on the Codebeamer server process | No stored credentials - forwards the invoking user's own session to the local Stream API |

The original documentation (`README.md`, `Test_Run_Reuse.groovy`, `examples/`) is kept as-is
in this folder for anyone who still has the Groovy script deployed and needs to understand
or roll it back.
