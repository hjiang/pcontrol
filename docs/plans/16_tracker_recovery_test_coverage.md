# 16 — Automated coverage for the TrackerService recovery orchestration

Issue #79. The usage-backfill recovery orchestration in
`android/app/src/main/kotlin/com/pcontrol/app/TrackerService.kt`
(`launchBackfill`, `promoteDebt`, `runRecovery`, plus the queue/debt/journal
helpers) is correctness-critical — three PR #69 review rounds found real
defects exactly there (queued window swallowed when a later debt was
promoted, `backfillInFlight` released outside `backfillMutex`, stall path
floored gap recovery above the committed cursor) — but only the already-pure
seams were tested. The last fix (316d726) was a call-site correction that
could not be pinned with a test at the seams that existed.

This plan extracts more pure decision seams into `:core` (the
`BackfillPromotion` precedent) so each orchestration decision is unit-tested,
and rewires TrackerService to route its decisions through them
behavior-preserving — same locks, same write order, same `apply()`/`commit()`
choices, same logs.

## Invariant → seam → tests

| # | Invariant | Seam (`:core`) | Tests |
| - | --------- | -------------- | ----- |
| A | Recovery floor is the committed cursor, never the in-memory query anchor (316d726) — the DECISION is pinned; the call-site wiring stays review-only (see below) | `BackfillRecovery.recoveryFloorMs` (anchor passed as the rejected candidate) | `BackfillRecoveryTest` — anchor ahead/behind/null, zero cursor |
| B | Claim ordering: a queue head is claimed only when it belongs with the active row; disjoint heads wait (claim time in `runRecovery`) | `BackfillRecovery.claim` | `BackfillRecoveryTest` — unhandled pass, fresh row, zeroed row absent, contiguous/overlapping/covered/disjoint heads, row-owes, retire |
| C | Queue awareness: owed frontiers never jump past an unrecovered queued window; disjoint queued windows do not clamp a request away (e8019d6); a queued window that does not COVER the frontier never skips the uncovered prefix (PR #81 round 2) | `BackfillRecovery.owedWindow`, `BackfillQueue.clamp` | `BackfillRecoveryTest` + `BackfillQueueTest` — overlapping raise, disjoint ignored, subsumed → null, max over chained coverage, partial-overlap prefix preserved |
| E | Completed-marker retention: retirement waits for the live cursor and a non-extending debt | `BackfillRecovery.debtClearDecision` (+ `hasDurableWork` guard-liveness input) | `BackfillRecoveryTest` — cursor trails, extending debt, covered/clear cases |
| — | Journal durability arithmetic: `"start:end|…"` codec; removal preserves non-matching (even malformed) entries; append-before-queue clamping | `BackfillJournal` (entryOf/containsEntry/appendEntry/decode/removeEntries), `BackfillQueue` | `BackfillQueueTest` |

TrackerService call sites rewire the inline expressions to the seams;
`runRecovery`'s claim `when` and the retirement gate keep their bodies
(durable writes and their ordering) and now switch on the seam's decision.
The backfill-state machine keeps `BackfillPromotion` for `promoteDebt` and
the retirement extend branch.

## Stages

### Stage 1 — seams + tests (TDD: red first)

New `android/core/src/main/kotlin/com/pcontrol/core/BackfillQueue.kt`
(`BackfillQueue.clamp`, `BackfillJournal`) and
`android/core/src/main/kotlin/com/pcontrol/core/BackfillRecovery.kt`
(`recoveryFloorMs`, `owedWindow`, `claim`, `debtClearDecision`,
`hasDurableWork`), with `BackfillQueueTest` and `BackfillRecoveryTest`
(56 tests total) written first and confirmed failing against `TODO`
stubs (the red run recorded 212 completed / 51 failed; three edge tests
and two PR #81 partial-overlap regressions were added during review), then
green.

The `runRecovery` claim arm takes the row values from the seam's decision
payload (`InstallFreshRow.window` / `ExtendActiveRow.endMs`); the peeked
queue head stays the identity for the journal removal and the dequeue.

**Status**: done.

### Stage 2 — TrackerService rewiring (behavior-preserving)

Frontier snapshot via `recoveryFloorMs` (anchor = rejected candidate); the
worker-side plan clamp via `BackfillQueue.clamp`; the debt-merge owed
computation via `owedWindow`; the three durable-work checks via
`hasDurableWork`; `journalAppend`/`journalRemove`/`journalLoad` via the
codec; `enqueueClamped` via `clamp`; the `runRecovery` claim arm via
`claim`; the retirement gate via `debtClearDecision`. Verified equivalent
case-by-case (fully-claimed → null, dedupe-hit branches verbatim, malformed
journal entries preserved by removal, `apply()` vs `commit()` untouched).

**Status**: done.

### Stage 3 — validation

`gradle :core:test` (217 tests, 0 failures; includes the PR #81
partial-overlap regressions),
`gradle :app:testDebugUnitTest` (179 tests, 0 failures; 396 total),
`gradle :app:lintDebug` clean (re-run after the fix-up pass that sourced
the claim arm's row values from the seam payload).

**Status**: done.

## Remains manual-only

- **Guard race windows (invariant D)**: the `backfillInFlight` CAS vs.
  detector publication and the mutex-serialized release are timing behavior
  of real coroutines/monitors — a pure seam cannot pin them (a Robolectric
  service harness would need DB injection and UsageStats fakes; deliberately
  not attempted here).
- **The floor wiring**: `recoveryFloorMs` pins the DECISION (cursor wins);
  that the call site passes the committed cursor — not the anchor — remains
  a one-line call-site invariant (now named, commented, and reviewable).
- **Real Room/prefs execution order**: the install → journal-commit →
  dequeue ordering and per-chunk `withTransaction` atomicity run against
  real Room; unit tests cover the decisions, not the database.
- **Claim-time union of a preserved prefix (PR #81 review, follow-up)**:
  `clamp`/`owedWindow` now advance a frontier only past a queued window
  that COVERS it, so a queued window starting inside a span no longer
  drops the uncovered prefix at the decision layer (regression-tested).
  The remaining gap is at CLAIM time: `Claim.ExtendActiveRow` preserves
  the active row's `progressMs`, so a preserved below-progress prefix is
  still swallowed when an older queued head is claimed first. Closing it
  needs queue coalescing plus a claim-time union/progress rule (lowering
  `progressMs` risks double-counting an already-recovered range) — a
  separate, semantics-changing change.
- Device verification of plan 15 (freeze-thaw, force-stop, update restart)
  remains the end-to-end proof.
