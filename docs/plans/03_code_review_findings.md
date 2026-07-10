# pcontrol — Code review findings (handoff for fixing agent)

Review of the completed implementation against
`docs/plans/01_implementation_plan.md` (referred to below as "the plan";
section numbers like §6 refer to it). Reviewed at commit `94a4064`.

**Instructions for the fixing agent:** work the findings in order (F1 first —
nothing in `:app` can even be tested until it compiles). For every fix, follow
TDD: write the failing test listed under the finding first, then the minimal
fix, then refactor. Commit per finding with a message like
`fix(F2): total-limit BLOCK must win over per-app WARN`. Run the verification
commands at the bottom before declaring done. Do NOT change behavior that is
not listed here.

## What was verified as correct (do not touch)

- Server Stages 1–3 pass completely: `go test ./...`, `go vet ./...` clean.
  Schema matches plan §5; `/api/v1/sync` matches §7 (UUID dedupe, `policy:
  null` on version match, 1 MiB cap → 413, 401 middleware); policy mutations
  bump `policy_version` transactionally (tested); session cookies are
  `HttpOnly`/`Secure` (TLS-only, so LAN HTTP logins work)/`SameSite=Lax`; bcrypt admin auth; `hash-password`
  subcommand; HTMX templates via `go:embed`; `deploy/` files present.
- `:core` tests pass; `PolicyEngineTest` covers all eight required Stage 6
  cases plus Stage 5 regressions.
- `BrowserDomainCache` session semantics (keep-on-null, clear-on-leave) match
  §9. `flake.nix` and README match the plan.

---

## F1 — BLOCKER: `:app` module does not compile

`gradle :app:testDebugUnitTest` fails in
`android/app/src/main/kotlin/com/pcontrol/app/SecretPrefs.kt`:

1. Two `companion object` blocks (lines 18 and 59) — illegal in Kotlin.
   Merge the constants `KEY_SERVER_URL` / `KEY_DEVICE_TOKEN` from the second
   block into the first, delete the second block.
2. It uses the `MasterKey.Builder` / context-first
   `EncryptedSharedPreferences.create(context, name, masterKey, …)` API from
   `androidx.security:security-crypto` **1.1.0+**, but
   `android/app/build.gradle.kts` pins **1.0.0** (which only has the
   `MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)` helper and the
   `create(name, masterKeyAlias, context, keyScheme, valueScheme)` signature).
   **Fix by upgrading the dependency to `1.1.0-alpha06`** (keeps the code's
   API, which is the non-deprecated one). Do not rewrite to the 1.0.0 API.

Required verification: `gradle :app:testDebugUnitTest` compiles and all
existing `:app` tests (Enforcer, SyncClient, BrowserRegistry,
BrowserDomainCache) pass. Report their results — they have never been run.

## F2 — HIGH: verdict ordering violates §6 ("first BLOCK wins")

`android/core/src/main/kotlin/com/pcontrol/core/PolicyEngine.kt`:

- `evaluateApp` (~line 67): when a per-app limit is in its WARN band, it
  returns `WARN` immediately — the total-limit rule (rule 4) is never
  evaluated. Hole: total limit exhausted + app at 90–99% of its own per-app
  limit → WARN every tick, app stays usable forever.
- `evaluateWeb` (~line 122): same bug — per-site WARN returns before the
  total-limit/restricted-mode BLOCK is evaluated.

Fix: restructure both functions so ALL rules are evaluated first and any
BLOCK verdict from a later rule beats a WARN from an earlier rule. Concretely:
compute the per-limit verdict and the total-limit verdict, then return the
first BLOCK in rule order; if none, the first WARN in rule order; else ALLOW.
Rule-2-beats-restricted-mode (the existing
`browser per-app limit exhausted still BLOCK_APP …` test) must keep passing.

Required new tests in `PolicyEngineTest.kt` (write first, watch them fail):

- `per-app WARN band but total limit hit returns BLOCK_APP`
- `per-site WARN band but total limit hit and domain not excluded returns BLOCK_WEB`
- `per-site WARN band, total limit hit, domain excluded returns WARN`
  (restricted mode allows the domain, but the site's own warn still fires)

## F3 — HIGH: never-block list must resolve default launcher/dialer at runtime

`PolicyEngine.kt` line ~20 hardcodes four launcher packages. §6 rule 1 says
"the **default** launcher/home app" and "the **default** dialer". If the
device's launcher is not in the hardcoded set, hitting the total limit blocks
the launcher; `BlockedActivity` escapes to home, which is blocked again →
livelock, phone unusable.

Fix (keep the engine pure):

1. Change `PolicyEngine` to take the never-block set as a parameter
   (`neverBlock: Set<String>`) instead of the hardcoded `NEVER_BLOCK_PACKAGES`
   constant. Keep `com.android.systemui`, `com.android.settings`, and
   `com.pcontrol.app` as a base set the caller always includes.
2. In `:app`, add a `NeverBlockResolver` that queries `PackageManager` for the
   default HOME activity (`Intent.ACTION_MAIN` + `Intent.CATEGORY_HOME`,
   `resolveActivity` with `MATCH_DEFAULT_ONLY`) and the default dialer
   (`TelecomManager.getDefaultDialerPackage()`), merges them with the base
   set, and hands the result to the engine each tick from `TrackerService`.
   Cache the resolution for 60s; resolution failure falls back to base set +
   the previously hardcoded launchers (fail-safe: better to under-block the
   launcher than to brick the phone).
3. Unit tests: engine tests pass the set explicitly (update existing
   never-block test); a Robolectric test for `NeverBlockResolver` asserting
   the resolved HOME package lands in the set.

## F4 — MEDIUM: sync race silently drops usage recorded during the HTTP call

`android/app/.../db/AppDatabase.kt` (~line 33): `markSynced` runs
`UPDATE usage_counter SET syncedSeconds = seconds …` AFTER the network call,
but the event sent contained the delta from the pre-call snapshot. A tick
recorded mid-request is marked synced without ever being sent. Plan §9
requires: "on success set `syncedSeconds = seconds` **for the snapshot values
sent**".

Fix: change the DAO to
`UPDATE usage_counter SET syncedSeconds = :sentSeconds WHERE …` and have
`TrackerService.onSync` pass each counter's snapshot `seconds` value.
Test (Robolectric, in-memory Room): insert counter with seconds=50/synced=0,
simulate a tick to 60 after building the batch, `markSynced(..., 50)` →
`getUnsynced()` still returns the row with a delta of 10.

## F5 — MEDIUM: 2-strikes BLOCK_WEB fallback is not implemented

`android/app/.../Enforcer.kt` (~line 92): the overlay fallback fires only when
the BACK *dispatch* fails. §6: fall back to `BlockedActivity` "if the domain
is still in the URL bar after 2 back-presses". As written, a page BACK cannot
leave (single-tab session) gets a BACK every 10s forever with no overlay.

Fix: add a small pure strike counter (e.g. `WebBlockStrikes`: key =
`subject|day`, incremented each `BLOCK_WEB` handling where the current cached
domain is still the blocked one, reset when the domain changes or verdict is
not BLOCK_WEB). On strike ≥ 3 (i.e., still present after 2 back-presses),
launch `BlockedActivity` instead of another BACK. Unit-test the counter class
directly: increment/reset/threshold.

## F6 — MEDIUM: WARN dedupe must be persisted in Room, keyed by day

`Enforcer.kt` (~line 31): `warnedKeys` is an in-memory set. Plan Stage 5 task
2 requires persisting the warned-set in Room keyed by day (process restart
currently re-warns; the set also grows without bound since keys embed the
day). Also `resetWarnedSet()`'s doc comment claims TrackerService calls it on
day rollover — nothing does.

Fix: new Room entity `WarnedSubject(day, subject)` with a DAO
(`exists(day, subject)`, `insert`, `deleteOtherDays(day)`); `Enforcer` takes
the DAO (or a lambda) instead of the static set; TrackerService calls
`deleteOtherDays(today)` once per tick loop day-change. Delete
`resetWarnedSet()` and its stale comment. Robolectric test: warn once →
second warn same day no-op → different day warns again.

## F7 — MINOR: suffix matching must apply to domains only

`PolicyEngine.kt` `matchesSubject` (~line 196) is used for app package
matching too, so a limit on subject `android.chrome` would match package
`com.android.chrome`. Plan §6 defines suffix matching for domains only.
Fix: exact equality for `kind == "app"` limits/exclusions (including
`countedTotal`'s app-exclusion filter); suffix match only for `kind == "web"`.
Tests: app limit on `android.chrome` does NOT match `com.android.chrome`;
existing domain-suffix tests keep passing.

## F8 — MINOR: use human app labels

`TrackerService.kt` (~line 158) uses the package name as `label`. Plan §5/§8
expects human labels ("YouTube") on the dashboard. Fix: resolve via
`packageManager.getApplicationLabel(getApplicationInfo(pkg, 0))`, falling back
to the package name; cache in-memory. No new test required (thin Android
glue), but keep the fallback path.

## F9 — MINOR: API returns 500 for invalid event kind and leaks error text

`server/internal/api/sync.go`: an event with `kind` outside `app|web` reaches
the DB CHECK constraint → 500, and handler errors include internal text
(`insert events: %v`). Fix: validate `kind ∈ {app, web}` (and non-empty
`event_id`, `day`) in the parse loop → 400 `"bad request"`; log the internal
error server-side, return generic `"internal error"` on 500s. Extend
`sync_test.go`: bad kind → 400.

## F10 — NOTE (no action unless asked)

- `IMPLEMENTATION_PLAN.md` was archived to `docs/plans/` instead of deleted —
  acceptable deviation, leave as is.
- `TouchLastSeen` error is ignored in `sync.go` — acceptable.
- The on-device manual checklists (plan Stages 4–6) have never been verified
  by a reviewer and cannot be verified in CI. After F1–F6 land, the owner
  must run the Stage 5 and Stage 6 manual checklists on the real phone.

---

## Final verification (all must pass before declaring done)

```sh
# from repo root, inside `nix develop`
cd server && go test ./... && go vet ./...
cd ../android && gradle :core:test :app:testDebugUnitTest
```

Plus: every finding F1–F9 has its listed test(s) added and green, and each fix
is a separate commit referencing the finding number.
