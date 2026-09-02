# Plan 14: Web UI polish — round 2 (readable, live, dark)

This plan is written to be executed by a coding agent (target model:
glm-5.3-flash). **Read the whole document before starting any stage.**
Every stage is independently committable and independently green. File
paths, function names, and string literals below are normative — when
something is ambiguous, prefer the simplest solution consistent with this
document and the existing code.

Baseline: this plan was written against commit `1570fdf` (branch
`agp-9.3-gradle-9.5`). Re-verify line numbers before editing; they may have
drifted.

---

## 0. Instructions for the executing agent

1. Work **one stage at a time, in the given order**. Do not start a stage
   until the previous stage's Success Criteria all pass. Stages 1–5 are the
   core scope; stages 6–8 are stretch goals — only attempt them after
   stages 1–5 are complete, green, and committed.
2. **TDD is mandatory for every Go change.** Write the failing test first,
   run it and confirm it fails, then implement until it passes. CSS-only
   changes have no unit tests; verify them with the manual smoke test in
   §9.
3. After every stage, update the stage's `**Status:**` line
   (`Not Started` → `Complete`) and **check off its checklist items** so the
   status and checklist agree (this was a review finding on PR #62 — do not
   repeat it).
4. Commit after each green stage: `web: imperative summary (Stage N)`.
5. Gate for every Go-touching stage — must pass before marking complete:

   ```sh
   cd server && go test ./... && go vet ./...
   ```

6. **Hard constraints (do NOT violate):**
   - No new dependencies of any kind: no npm, no build step, no CDN, no
     CSS framework, no chart library, no icon font.
   - All CSS lives in the single `<style>` block in
     `server/internal/web/templates/layout.gohtml` (existing pattern).
   - The only static asset is `static/htmx.min.js` (embedded). Do NOT edit
     or reformat `htmx.min.js`. Any new JavaScript is a small inline
     `<script>` at the bottom of `layout.gohtml`, plain ES5-compatible,
     no imports.
   - Templates are picked up by the glob `templates/*.gohtml` in
     `render.go`'s `init()`. A new template file is automatically
     available — no registration needed.
   - No trailing whitespace, including on blank lines.
   - Server handlers keep existing patterns: strict validation, plain
     `http.Error(...)`, best-effort side writes log-and-continue.
   - **Dashboard status must be visible text, never hover-only** (no
     requirement may be satisfied only by a `title` attribute).
   - Do not touch `server/internal/api/` (device sync API) or anything
     under `android/`. This plan is web-UI only.

7. Existing tests that will need updating as you go (key-string assertions
   against rendered HTML — read `server/internal/web/dashboard_test.go`
   before changing any template string): the "Today: X min" strings, the
   7-day history markup, and the device card markup all have assertions.
   When you change a rendered string, update its assertion in the SAME
   commit. Never delete a test to make a change pass.

---

## Stage 1 — Human-readable durations

**Goal:** replace raw "480 min" with "8h", "8h 15m", "45m" everywhere.

**Files:** `server/internal/web/dashboard.go`, `render.go`,
`templates/dashboard.gohtml`, `templates/device.gohtml`,
`dashboard_test.go`.

### 1a. The helper (TDD)

Add to `dashboard.go`, next to `formatAge` (~line 534):

```go
// formatDuration renders a minute count for humans:
// 0 → "0m", 45 → "45m", 60 → "1h", 125 → "2h 5m", 480 → "8h".
func formatDuration(min int) string {
	if min < 60 {
		return strconv.Itoa(min) + "m"
	}
	h := min / 60
	m := min % 60
	if m == 0 {
		return strconv.Itoa(h) + "h"
	}
	return strconv.Itoa(h) + "h " + strconv.Itoa(m) + "m"
}
```

(Add `"strconv"` to imports if absent.)

**Write the failing test first** — table-driven unit test in
`dashboard_test.go` (or a new `dashboard_duration_test.go`):

| in  | want    |
| --- | ------- |
| 0   | `0m`    |
| 45  | `45m`   |
| 59  | `59m`   |
| 60  | `1h`    |
| 61  | `1h 1m` |
| 125 | `2h 5m` |
| 480 | `8h`    |
| 1441 | `24h 1m` |

### 1b. Expose to templates

In `render.go`, `init()` (~line 62), extend the FuncMap:

```go
"dur": formatDuration,
```

### 1c. Use it in templates

- `dashboard.gohtml`: `Today: <strong>{{.TotalMinutes}} min</strong>` →
  `Today: <strong>{{dur .TotalMinutes}}</strong>`; the small line under
  the bar `{{.TotalMinutes}}m / {{.LimitMin}}m — {{.BarPercent}}%` →
  `{{dur .TotalMinutes}} / {{dur .LimitMin}} — {{.BarPercent}}%`; the
  `(no limit set)` branch stays.
- `device.gohtml`: `Usage for {{.Day}}: {{.TotalMinutes}} min` →
  `Usage for {{.Day}}: {{dur .TotalMinutes}}`; the bar caption line and
  `Limit: {{.LimitMin}}m (warn at {{.WarnPct}}%)` →
  `Limit: {{dur .LimitMin}} (warn at {{.WarnPct}}%)`; the 7-day history
  row label `{{.Minutes}} min` → `{{dur .Minutes}}`; the app/website
  tables' `<td>{{.Minutes}}</td>` → `<td>{{dur .Minutes}}</td>`.
- `dashboard.gohtml` top-app pills `{{$e.Label}} ({{$e.Minutes}} min)` →
  `{{$e.Label}} ({{dur $e.Minutes}})`.

### 1d. Update existing assertions

Search `dashboard_test.go` for assertions containing `" min"` / `"m / "`
and update them to the new rendered strings. Add at least two new
key-string assertions that pin the formatting: a dashboard render with a
480-minute total must contain `"8h"`; a device page render must contain
`"/ 10h"` when the limit is 600.

**Success criteria:** all tests green; `go vet` clean; no raw " min"
strings remain in `dashboard.gohtml` / `device.gohtml` except inside the
`(no limit set)` branch and battery/last-seen text.

**Status:** Not Started

---

## Stage 2 — Progress bar accessibility + warn tick

**Goal:** bars are real progress bars to assistive tech, and the warn
threshold is visible on the bar.

**Files:** `layout.gohtml`, `dashboard.gohtml`, `device.gohtml`,
`dashboard_test.go`.

1. **Cap history percents.** In `dashboard.go` deviceDetail (~line 450),
   the history loop sets `BarPercent: pct` without capping — change to
   `BarPercent: min(pct, 100)` (matches the two existing `min(pct, 100)`
   call sites). TDD: existing history tests + add one where one day's
   total exceeds `maxMinutes` is impossible (max is the max), so instead
   pin the cap with a direct unit test on the rendered history markup —
   simpler: skip the new test if not reachable, but keep the cap for
   safety. (This is a defensive edit; no behavior change.)

2. **ARIA.** Every `.bar-bg` div in `dashboard.gohtml` and `device.gohtml`
   gets:

   ```html
   role="progressbar" aria-valuemin="0" aria-valuemax="100" aria-valuenow="{{.BarPercent}}"
   aria-label="Daily usage {{.BarPercent}} percent of limit"
   ```

   (Label text may adapt per location but must be present.)

3. **Warn tick.** CSS in `layout.gohtml`:

   ```css
   .bar-bg { position: relative; }
   .bar-warn-tick {
     position: absolute; top: 0; bottom: 0; left: 0;
     width: 2px; background: var(--warning); opacity: .8;
   }
   ```

   In `device.gohtml` (the device-page bar only — the dashboard card
   doesn't currently carry `WarnPct`), inside the `.bar-bg` element add:

   ```html
   {{if and .HasLimit (lt .WarnPct 100)}}<span class="bar-warn-tick" style="left:{{.WarnPct}}%" aria-hidden="true"></span>{{end}}
   ```

   Test (dashboard_test.go): render a device page with limit 600 and warn
   80; assert the HTML contains `bar-warn-tick` and `left:80%`. Write the
   test first.

**Success criteria:** tests green; every `.bar-bg` in templates has
`role="progressbar"`; device page shows the tick at the configured warn
percent.

**Status:** Not Started

---

## Stage 3 — Dark mode

**Goal:** automatic + user-toggleable dark theme. CSS + one small inline
script only.

**Files:** `layout.gohtml` only (plus dashboard_test.go if you add a
class-presence assertion — optional here, CSS is manually verified).

1. **Token overrides.** Add after the existing `:root` block in
   `layout.gohtml`:

   ```css
   [data-theme="dark"] {
     --bg: #111827;
     --surface: #1f2937;
     --text: #e5e7eb;
     --text-muted: #9ca3af;
     --border: #374151;
     --primary: #7c93f5;
     --primary-hover: #94a7f7;
     --shadow: 0 1px 3px rgba(0,0,0,.5);
     --shadow-md: 0 4px 6px rgba(0,0,0,.5);
   }
   ```

2. **Replace hard-coded light-only colors with tokens** so they flip:

   | Selector | Current | Change to |
   | --- | --- | --- |
   | `.bar-bg` | `background: #eee` | `background: var(--bg)` (move into `:root` a new `--bar-bg: #eee` token; dark: `#374151`) |
   | `.status-online` | `#d1fae5`/`#065f46` | tokens `--status-ok-bg`/`--status-ok-fg` (dark: `#064e3b`/`#a7f3d0`) |
   | `.status-offline` | `#f3f4f6`/`#6b7280` | tokens (dark: `#374151`/`#9ca3af`) |
   | `.pill-danger` | `#fee2e2`/`#991b1b` | tokens (dark: `#7f1d1d`/`#fecaca`) |
   | `.pill-warning` | `#fef3c7`/`#92400e` | tokens (dark: `#78350f`/`#fde68a`) |
   | `.alert-error` | `#fee2e2`/`#991b1b`/`#fca5a5` | reuse the danger tokens |
   | `.top-apps` | `color: #555` | `color: var(--text-muted)` |
   | `.table-styled tr:hover td` | `rgba(67,97,238,.04)` | fine as-is (works on both) |

   The nav gradient and `#fff` nav text stay as-is (already dark).

3. **Toggle button + script.** In the nav (inside
   `{{if not .MinimalLayout}}`), after the `.nav-spacer` span add:

   ```html
   <button id="theme-toggle" class="btn-ghost" aria-label="Toggle dark mode"
           onclick="toggleTheme()">🌙</button>
   ```

   At the bottom of `<body>` in `layout.gohtml`, add one inline script:

   ```html
   <script>
   function applyTheme(t){document.documentElement.dataset.theme=t;
     var b=document.getElementById('theme-toggle');
     if(b){b.textContent = t==='dark' ? '☀️' : '🌙';}}
   function toggleTheme(){var t=document.documentElement.dataset.theme==='dark'?'light':'dark';
     try{localStorage.setItem('pcontrol-theme',t)}catch(e){}
     applyTheme(t);}
   (function(){
     var t=null;
     try{t=localStorage.getItem('pcontrol-theme')}catch(e){}
     if(!t){t=window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';}
     applyTheme(t);
   })();
   </script>
   ```

   Wrap localStorage access in try/catch (Safari private mode throws).

**Success criteria:** manual smoke test (§9): dashboard, device page,
limits page, login page all render correctly in both themes; toggle
persists across reload; no unthemed light patches remain (check badges,
pills, alerts, tables, inputs).

**Status:** Not Started

---

## Stage 4 — Clickable device cards

**Goal:** the whole dashboard card is the link, and hover-lift matches
affordance.

**Files:** `dashboard.gohtml`, `layout.gohtml`, `dashboard_test.go`.

1. In `dashboard.gohtml`, change each device card from:

   ```html
   <div class="card"> ... <h2 ...><a href="/devices/{{.ID}}">{{.Name}}</a></h2> ... </div>
   ```

   to:

   ```html
   <a class="card card-link" href="/devices/{{.ID}}">
     ... <h2 ...>{{.Name}}</h2> ...
   </a>
   ```

   (Remove the inner `<a>` — nested anchors are invalid HTML. Keep the
   battery span, status pill, bar, and top-app pills inside; they contain
   no links.)

2. CSS in `layout.gohtml`:

   ```css
   a.card-link { color: inherit; text-decoration: none; display: block; }
   a.card-link:hover, a.card-link:focus-visible { color: inherit; }
   a.card-link:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
   ```

3. Update `dashboard_test.go` assertions that matched the old `<h2><a`
   structure: the device name must now appear exactly once inside the
   card. Assert `<a class="card card-link"` and
   `href="/devices/` + id appear.

**Success criteria:** tests green; clicking anywhere on a card navigates;
keyboard focus outline visible on card; name not double-rendered.

**Status:** Not Started

---

## Stage 5 — Live dashboard (auto-refresh)

**Goal:** the dashboard device grid refreshes itself every 30 s via HTMX,
pull-only (browser polls server; no change to the sync architecture).

**Files:** new `templates/device_grid.gohtml`, `dashboard.gohtml`,
`dashboard.go`, `dashboard_test.go`, `router.go` (only if a route is
needed — it is not: same path, header-based content negotiation).

1. **Extract the partial.** Create `templates/device_grid.gohtml`
   containing exactly the current `{{if .Devices}} … {{else}} … {{end}}`
   block from `dashboard.gohtml` (the whole `card-grid` + empty-state
   branch). `dashboard.gohtml` becomes:

   ```html
   <h1>Devices</h1>
   ...count line + register button...
   <div id="device-grid" hx-get="/" hx-trigger="every 30s" hx-swap="innerHTML">
     {{template "device_grid.gohtml" .}}
   </div>
   ```

2. **Content negotiation in the handler.** In
   `dashboard.go` `dashboard()` handler, before rendering: if
   `r.Header.Get("HX-Request") == "true"`, render ONLY the partial:

   ```go
   if r.Header.Get("HX-Request") == "true" {
       w.Header().Set("Content-Type", "text/html; charset=utf-8")
       return parsedTemplates.ExecuteTemplate(w, "device_grid.gohtml", data)
   }
   ```

   (See how `renderPage` sets Content-Type in `render.go` and mirror it.
   Note `renderPage` cannot be reused for partials — it wraps in layout.)

3. **Tests (write first):**
   - Existing dashboard tests keep passing unchanged (no HX-Request →
     full page, still contains `<nav`).
   - New test: same request with header `HX-Request: true` → response
     does NOT contain `<nav` and DOES contain the device name and
     `status-pill`.

**Success criteria:** tests green; manual: two browser windows, one on
the dashboard; register a sync from the other (or wait) and watch the
grid update within ~30 s without reload; the `<h1>Devices</h1>` and count
line do NOT flicker (they're outside the swapped region).

**Status:** Not Started

---

## Stage 6 — Inline SVG 7-day history chart (stretch)

**Goal:** replace the device page's div-bar history list with a
server-rendered inline SVG bar chart with a limit line and per-bar
tooltips. No JS, no library.

**Files:** `dashboard.go`, `templatesData.go`, `device.gohtml`,
`dashboard_test.go`.

1. Extend `historyRow` in `templatesData.go` with precomputed geometry —
   do geometry in Go, not in the template:

   ```go
   type historyRow struct {
       Day        string
       Minutes    int
       BarPercent int
       X          int // svg rect x
       Width      int // svg rect width
       Height     int // svg rect height
       Y          int // svg rect y (= 100 - Height)
       LimitLineY int // 0 = no line
   }
   ```

   Chart box: `viewBox="0 0 280 100"`. 7 bars: `Width: 28`, gap 12,
   `X = i*40 + 12`. `Height = BarPercent` (already capped at 100 by
   Stage 2). Limit line: if `HasLimit`, `LimitLineY = 100 - limitPct`
   where `limitPct = limitMinutes*100/maxMinutes` (cap at 100); compute
   once, set on every row (or hoist to `deviceDetailData.LimitLineY` and
   render the line once — preferred).

2. `device.gohtml`: replace the `{{range .History}}` div list with:

   ```html
   <svg viewBox="0 0 280 100" role="img"
        aria-label="Daily usage over the last 7 days, maximum {{dur .HistoryMaxMinutes}}"
        style="width:100%;max-width:420px;height:120px">
     {{range .History}}
     <g>
       <title>{{.Day}}: {{dur .Minutes}}</title>
       <rect x="{{.X}}" y="{{.Y}}" width="{{.Width}}" height="{{.Height}}"
             rx="2" fill="var(--primary)"/>
     </g>
     {{end}}
     {{if .HasLimit}}<line x1="0" x2="280" y1="{{.LimitLineY}}" y2="{{.LimitLineY}}"
       stroke="var(--danger)" stroke-width="1.5" stroke-dasharray="4 3"/>{{end}}
   </svg>
   ```

   (Add `HistoryMaxMinutes int` and `LimitLineY int` to
   `deviceDetailData`; populate in the handler.)

3. Tests (write first): device page with 7 days of fixture data and a
   limit → response contains `<svg`, `role="img"`, one `<rect` per day
   (7), `<title>` containing a fixture day key, and `<line` when a limit
   exists; no `<line` when none.

**Success criteria:** tests green; chart renders in both themes (fill
uses the `var(--primary)` token); tooltips work on hover; the old div-bar
history markup is gone and its old assertions updated in the same commit.

**Status:** Not Started

---

## Stage 7 — HTMX settings forms + save feedback (stretch)

**Goal:** rename / recipients / timezone forms submit via HTMX and show a
toast; no full-page reload; validation errors render inline, not as an
error page.

**Files:** `device.gohtml`, `layout.gohtml`, `dashboard.go`,
`dashboard_test.go`.

1. **Toast region.** In `layout.gohtml` before `</main>` add
   `<div id="toast" aria-live="polite"></div>`; CSS:

   ```css
   #toast > .toast {
     position: fixed; right: 1rem; bottom: 1rem; z-index: 100;
     background: var(--surface); border: 1px solid var(--border);
     border-left: 4px solid var(--success);
     padding: .6rem 1rem; border-radius: var(--radius);
     box-shadow: var(--shadow-md); animation: toast-in .2s ease-out;
   }
   #toast > .toast.toast-error { border-left-color: var(--danger); }
   @keyframes toast-in { from { opacity: 0; transform: translateY(8px); } }
   ```

2. **Forms.** In `device.gohtml`, for the three forms
   (`rename`, `recipients`, `timezone` — NOT `delete`): keep `method`/
   `action` as no-JS fallback, add:

   ```html
   hx-post="<same URL>" hx-target="#settings-feedback" hx-swap="innerHTML"
   ```

   Add `<div id="settings-feedback"></div>` once, under the settings
   heading inside the `<details>`.

3. **Handler responses.** In `dashboard.go`, at the end of the three
   handlers' success paths: if `HX-Request == "true"`, respond 200 with
   `<div class="toast">Saved — new label here</div>` (e.g. the new device
   name / saved timezone) instead of the 303 redirect. On validation
   failure with `HX-Request == "true"`, respond 200 (htmx treats 2xx as
   success for swapping) with `<div class="toast toast-error">…reason…</div>`; keep the existing non-HTMX error behavior unchanged.

   The toast inside `#settings-feedback` lands inside the aria-live
   region? No — `#settings-feedback` is inside `<main>`, the live region
   is `#toast`. Simplest correct approach: have handlers return the toast
   markup and target `#toast` directly with `hx-target="#toast"`. Do
   that instead.

4. **Tests (write first):** for each of the three handlers, an HTMX-mode
   test asserting the partial toast response (and that a validation
   failure yields `toast-error`), plus unchanged non-HTMX tests proving
   the redirect/error-page behavior is intact.

**Success criteria:** tests green; manual: rename a device with JS on —
toast appears, no reload; with JS off (or via curl) the old full-page
flow still works (progressive enhancement intact).

**Status:** Not Started

---

## Stage 8 — Friendly app labels (stretch)

**Goal:** top-app pills and app tables show friendly labels instead of
raw package names.

**Files:** new `server/internal/web/labels.go`,
`dashboard.go` (or wherever `Label` is populated), tests.

1. `labels.go`:

   ```go
   // friendlyLabel maps well-known Android package names to short labels.
   var friendlyLabels = map[string]string{
       "com.google.android.youtube":   "YouTube",
       "com.android.chrome":           "Chrome",
       "com.google.android.gm":        "Gmail",
       "com.zhiliaoapp.musically":     "TikTok",
       "com.instagram.android":        "Instagram",
       "com.snapchat.android":         "Snapchat",
       "com.discord":                  "Discord",
       "com.spotify.music":            "Spotify",
       "com.mojang.minecraftpe":        "Minecraft",
       // extend as observed in real data
   }

   func friendlyLabel(pkg string) string {
       if f, ok := friendlyLabels[pkg]; ok { return f }
       // strip common namespace prefix: com.example.app → app
       parts := strings.Split(pkg, ".")
       if len(parts) > 2 { return parts[len(parts)-1] }
       return pkg
   }
   ```

2. Apply wherever `Label` is set for apps (NOT websites/domains — domains
   are already human-readable). Keep the raw package in the `title`
   attribute of the pill/cell for debuggability (hover-only info is fine
   when it is supplementary — the label itself must be the visible text).

3. Tests (write first): unit table for `friendlyLabel`; a dashboard
   render test with fixture package `com.google.android.youtube` asserting
   `YouTube` is visible.

**Status:** Not Started

---

## 9. Manual smoke test (run after Stages 3–5, and before finishing)

```sh
cd server && go run ./cmd/pcontrold --listen 127.0.0.1:8080 \
    --admin-password-hash "$(go run ./cmd/pcontrold hash-password <<< 'test')"
```

Visit `http://127.0.0.1:8080/`, log in, and check with at least one
registered device with data (or create fixture rows directly in SQLite
per the test helpers' pattern):

- [ ] Login page, dashboard, device page, limits page, register page all
      render in light AND dark mode (toggle top-right).
- [ ] Durations read "8h 15m" style everywhere; no raw "480 min".
- [ ] Whole card is clickable; focus ring visible with keyboard nav.
- [ ] Device grid auto-refreshes every 30 s (watch the network tab).
- [ ] No console errors in the browser dev tools.
- [ ] View-source check: no trailing whitespace introduced.

## 10. Out of scope (do not do)

- Day-timeline usage strip (per-hour blocks) — needs new aggregation
  queries; separate plan.
- Week-view toggle on the dashboard.
- Any change to `android/` or `server/internal/api/`.
- Websockets / server-push of any kind (dashboard stays pull-only).
