# pcontrol — Web UI Polish Plan

This plan is written to be executed by coding agents. **Read the whole document
before starting any stage.** File paths, names, schemas, and semantics below
are normative. When something is ambiguous, prefer the simplest solution
consistent with this document and with the existing code.

---

## 0. How to use this plan (instructions for agents)

1. Work **one stage at a time, in order**. Do not start a stage until the
   previous stage's Success Criteria all pass.
2. **TDD is mandatory**: for every handler test, write the failing test first
   (red), write minimal code to pass (green), then refactor. CSS-only stages
   are manually verified. Never disable or skip a test to make a stage "pass".
3. Update the `**Status**` line of each stage as you go
   (`Not Started` → `In Progress` → `Complete`).
4. Commit after each green task with a message like
   `web: add design system CSS (Stage 1)`.
5. Run before marking any Go-touching stage complete:

   ```sh
   cd server && go test ./... && go vet ./...
   ```

6. **Constraints** (do NOT violate):
   - No npm, no build step, no CSS framework CDN. All CSS is inline in the
     `<style>` block of `layout.gohtml` (same pattern as today).
   - No new dependencies (no Sass, no PostCSS, no Tailwind).
   - Only static asset is `htmx.min.js` (already embedded via `go:embed`).
   - The server remains a single self-contained Go binary.
   - No dark mode in this plan (adds complexity; keep scope tight).
   - Backward compatibility is not affected (web UI changes only).

---

## 1. Current state audit

| Area | What works | What's rough |
|---|---|---|
| Layout | Nav bar, centered main, system fonts | Flat, no visual hierarchy, no footer |
| CSS | All in `<style>` block, no external deps | Ad-hoc colors, no design tokens, no `:focus` styles |
| Dashboard | Device cards, progress bars, battery, top apps | Cards look like bordered divs, no hover feedback |
| Device detail | Day picker, history bars, app/web tables, rename/delete | `border="1"` tables, no stats summary |
| Limits | HTMX add/delete, exclusions, kind picker | Same table styling, no feedback on HTMX actions |
| Login | Works, error message shown | Plain input + button, no visual identity |
| Register device | Works via `renderInline` | Not a proper templated page; raw HTML in Go |
| Accessibility | `viewport` meta, labels on inputs | No `:focus-visible`, no skip link, no `aria-*` |
| HTMX | Works for limits/exclusions CRUD | No loading spinner, no success/error feedback, no inline validation |
| Responsive | `max-width: 800px` + viewport meta | Tables overflow on narrow screens; form layouts break |
| Empty states | Dashboard says "No devices registered" | No visual empty-state card; inline text |

---

## Stage 1: Design system foundations (CSS tokens + layout upgrade)

**Goal**: Establish a cohesive visual language with CSS custom properties
(variables), consistent spacing, and a polished shell (nav, footer, card
styling).

**Status**: Complete

### 1.1 CSS custom properties + reset

Replace the existing inline `<style>` block in `layout.gohtml` with a
structured design system. Key changes:

```css
:root {
  --bg: #f8f9fa;
  --surface: #ffffff;
  --text: #1a1a2e;
  --text-muted: #6b7280;
  --border: #e5e7eb;
  --primary: #4361ee;
  --primary-hover: #3a56d4;
  --success: #10b981;
  --warning: #f59e0b;
  --danger: #ef4444;
  --radius: 8px;
  --shadow: 0 1px 3px rgba(0,0,0,.08), 0 1px 2px rgba(0,0,0,.06);
  --shadow-md: 0 4px 6px rgba(0,0,0,.07), 0 2px 4px rgba(0,0,0,.06);
  --font: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
  --font-mono: 'SF Mono', 'Fira Code', 'Cascadia Code', monospace;
}
```

### 1.2 Nav bar

- Gradient or richer dark background (`#16213e` → `#0f3460`).
- Logo text with optional emoji icon (`🛡️ pcontrol`).
- Right-aligned user section: "admin" label + Logout button.
- Remove inline form styles, use classes.
- Active link underline indicator.

### 1.3 Card component

- Replace `border: 1px solid #ccc` with `var(--shadow)` and `var(--radius)`.
- Add subtle hover lift effect (`transform: translateY(-1px)` + `var(--shadow-md)`).
- Consistent internal padding rhythm with a card header/body pattern.

### 1.4 Footer

- Simple sticky footer: `pcontrol · v1.0 · <year>`.
- Not over-engineered; just a visual anchor at the bottom.

### 1.5 Button system

- `.btn-primary`: filled, `var(--primary)` background, white text.
- `.btn-danger`: filled, `var(--danger)` background, white text.
- `.btn-ghost`: transparent, border-only, used in nav.
- `.btn-link`: text-only, underline on hover (replace existing).
- All buttons get `:focus-visible` ring for keyboard accessibility.
- `cursor: pointer`, `border-radius`, consistent padding.

### 1.6 Form elements

- Inputs, selects, textareas get consistent styling:
  - `border: 1px solid var(--border)`, `border-radius: var(--radius)`.
  - Focus ring using `:focus-visible` with `outline: 2px solid var(--primary)`.
  - Consistent padding (`0.5rem 0.75rem`).
- Labels: `display: block; margin-bottom: 0.25rem; font-weight: 500`.

### 1.7 Typography scale

- Body: `1rem` / `1.6` line-height.
- Headings: `h1` (1.75rem), `h2` (1.35rem), `h3` (1.15rem).
- `.text-muted` class.
- `.text-sm` for secondary information.

### Verification

```
Manual: Start the server, visit /login, /, /devices/1, /devices/1/limits.
Verify consistent look across all pages: cards, buttons, inputs, nav, footer.
```

---

## Stage 2: Dashboard page polish

**Goal**: The main dashboard (`/`) becomes a polished, informative overview
that makes device status immediately scannable.

**Status**: Complete

### 2.1 Page header

- Title "Devices" with a subtle subtitle showing device count:
  "2 devices" or "No devices yet".
- "Register device" becomes a prominent button (`.btn-primary`), not a text
  link buried in a `<p>`.

### 2.2 Device card redesign

Each card gets structured sections:

```
┌─────────────────────────────────────────┐
│ ○ ONLINE  🔋 85% ⚡                     │  ← Status row
│ 📱 Kid's Phone                          │  ← Device name (h2)
│ Last seen 2 min ago · 2026-07-14 12:34  │  ← Timestamp row
├─────────────────────────────────────────┤
│ Today: 45 min / 120 min                 │
│ ████████████░░░░░░░░ 38%                │  ← Usage bar + label
├─────────────────────────────────────────┤
│ Top: YouTube (25m) · Chrome (12m) · ... │  ← Top apps
└─────────────────────────────────────────┘
```

- Device name is a clickable link to `/devices/{id}`.
- Status badges (online/offline) get proper pill styling:
  `display: inline-block; padding: 2px 8px; border-radius: 12px; font-size: 0.8em`.
- Progress bar gets a label showing percentage and min/max.
- Tooltip/title on timestamp shows exact ISO time.

### 2.3 Empty state

```
┌─────────────────────────────────────────┐
│                                         │
│              📱                         │
│      No devices registered yet          │
│  Register your child's device to start  │
│          monitoring usage.              │
│                                         │
│          [Register Device]              │
│                                         │
└─────────────────────────────────────────┘
```

A centered card with an icon (emoji), explanatory text, and a CTA button.

### 2.4 Progress bar polish

- Add percentage label to the right of the bar (or centered overlay when
  bar is tall enough).
- Smooth CSS transition on width changes (`transition: width 0.5s ease`).
- Color: green (0–79%), yellow/orange (80–99%), red (100%+).
- Slightly taller bar (20px vs current 16px) with subtle gradient fill.

### 2.5 Top apps section

- Show app icons as labels with pill backgrounds:
  `<span class="app-pill">🎮 YouTube 25m</span>`.
- Max 5 entries (up from 3) with "..." overflow.
- If no apps used today: "No activity today" muted text.

### Verification

```
Manual: Dashboard with 0, 1, 2+ devices; check all states render cleanly.
Existing tests in dashboard_test.go must still pass (they assert on text content,
not CSS classes, so they should be robust).
```

---

## Stage 3: Device detail page polish

**Goal**: `/devices/{id}` provides a clear, information-dense view of a single
device's activity with intuitive navigation.

**Status**: Complete

### 3.1 Page header

- Breadcrumb: `← Dashboard / Kid's Phone`.
- Device name as `h1`.
- Quick stats row: Battery (if available), Today's usage, Status.

### 3.2 Day picker improvement

- Replace plain text input with a styled date input: `<input type="date">`.
- Show "← Yesterday | Today | Tomorrow →" navigation links.
- Default to most recent day with events (current behavior is correct).

### 3.3 Usage summary card

Top of the page, a compact card:

```
┌──────────────────────────────────────┐
│ Today's Usage                        │
│ ████████████░░░░░░░░ 45m / 120m     │
│ ⚠️ 10 min remaining until blocked   │
└──────────────────────────────────────┘
```

- Same bar as dashboard but larger (24px height).
- Show remaining time when close to limit.
- Badge states: "Using freely", "⚠️ Approaching limit", "⛔ Blocked".

### 3.4 7-day history chart

- Keep existing bar-chart layout but polish:
  - Day labels left-aligned in a column.
  - Bars use `var(--primary)` with rounded ends.
  - Today's bar highlighted with a different color.
  - Show values (minutes) to the right of each bar (already done).
  - Add a subtle grid/axis line at the bottom.
- Title: "Last 7 days" with total for the week.

### 3.5 Apps & websites tables

- Remove `border="1"` attribute; replace with CSS class-based table styling.
- Table CSS:
  - `border-collapse: collapse`, full width.
  - Header row: light gray background (`--bg`), uppercase small text.
  - Rows: border-bottom (`1px solid var(--border)`), hover highlight.
  - Status column: use pill badges for BLOCKED (red) / WARN (yellow).
- If no apps/websites used: "No apps tracked today" / "No websites tracked today"
  as a single table row with colspan.
- Separate section: apps first, then websites (already correct).

### 3.6 Device management section

- Move rename + delete to a collapsible "⚙️ Settings" section at the bottom.
- Rename: inline form with save/cancel.
- Delete: red outlined button with confirmation dialog text changed to:
  "This will permanently delete this device and all its data. Continue?"

### Verification

```
Manual: View device with/without battery, with/without usage, with/without limits.
Test edge cases: all days with 0 min, single day, device with only apps or only websites.
Existing tests in dashboard_test.go must still pass.
```

---

## Stage 4: Limits page polish

**Goal**: `/devices/{id}/limits` feels like a proper settings panel with clear
feedback and organization.

**Status**: Complete

### 4.1 Layout reorganization

Group into clear sections with card containers:

```
┌── Limits ─────────────────────────────┐
│ Total daily limit: 120 minutes        │
│ Warn at: 80%                          │
│ [Edit]                                │
└───────────────────────────────────────┘

┌── Per-App/Web Limits ────────────────┐
│ Kind │ Subject          │ Min/day │  │
│ app  │ com.roblox       │ 60      │✕ │
│ web  │ youtube.com      │ 30      │✕ │
│                                      │
│ [+ Add limit]                        │
└──────────────────────────────────────┘

┌── Exclusions ────────────────────────┐
│ Kind │ Subject          │            │
│ web  │ school.edu       │✕           │
│                                      │
│ [+ Add exclusion]                    │
└──────────────────────────────────────┘
```

### 4.2 Inline editing for total limit

Replace current always-visible form with a "read mode" / "edit mode" toggle:

- Default: shows current values as text with an "Edit" button.
- Edit mode: form appears inline with Save/Cancel.
- On save: HTMX replaces the section with updated read-only view.

### 4.3 HTMX UX improvements

- Add loading spinner: when HTMX request is in flight, show a small CSS
  spinner or "Saving…" text near the form.
- Add success feedback: brief green flash on the new row after add.
- Add error feedback: inline error message if server returns 400/500.
- The `hx-indicator` attribute + a CSS spinner class handles loading state.
- The `hx-on::after-request` already resets the form on success — keep that.

Implementation:
```css
.htmx-indicator { display: none; }
.htmx-request .htmx-indicator { display: inline; }
.htmx-request.htmx-indicator { display: inline; }
```

Wrap add-limit/add-exclusion forms:
```html
<form ... hx-indicator="#add-spinner">
  ...
  <button>Add</button>
  <span id="add-spinner" class="htmx-indicator spinner">…</span>
</form>
```

### 4.4 Datalist improvement

- The `<datalist id="subjects">` autocomplete is functional — keep it.
- Add a helper text below the subject input:
  "Start typing to see known apps and sites."

### 4.5 Settings section

- Merge into the total-limit card (see 4.2 above).
- Remove the standalone "Settings" heading.

### Verification

```
Manual: Add/remove limits and exclusions via HTMX; verify spinner appears,
row appears/disappears smoothly. Edit total limit.
Existing tests in limits_test.go must still pass.
```

---

## Stage 5: Login & registration pages

**Goal**: Login and device registration are the first things a user sees;
they should feel intentional and polished.

**Status**: Complete

### 5.1 Login page redesign

Center the login form vertically and horizontally:

```
┌──────────────────────────────────┐
│                                  │
│         🛡️ pcontrol             │
│     Parental Control Panel      │
│                                  │
│  ┌────────────────────────┐     │
│  │  Password              │     │
│  │  [················]    │     │
│  │  [Sign in]             │     │
│  └────────────────────────┘     │
│                                  │
│     ⚠ Invalid password          │
│                                  │
└──────────────────────────────────┘
```

- Unbranded layout (no nav, no footer — a "minimal" variant of the layout).
- Password field with show/hide toggle (simple JS: `<button type="button"
  onclick="toggle password visibility">`).
- Error message styled as a warning banner at the top of the card.
- Full viewport centering via `display: flex; min-height: 100vh`.

### 5.2 Register device page as proper template

Current state: `renderInline()` with raw HTML string in Go. This is fragile
and inconsistent.

1. Create `templates/register.gohtml` as a proper template.
2. Add a `registerData` view-model in `templatesData.go`.
3. Split into two views:
   - Form view (`GET /devices/new`): the registration form.
   - Success view (`POST /devices/new`): shows device name + token.
4. Use the same layout (with nav) for both — the user is already logged in.

Template structure:
```gohtml
{{if .Success}}
  <h1>Device Registered</h1>
  <div class="card">
    <p>Device: <strong>{{.DeviceName}}</strong></p>
    <div class="token-display">
      <label>Bearer token (copy now — it will not be shown again):</label>
      <pre><code>{{.Token}}</code></pre>
    </div>
    <a href="/devices/{{.DeviceID}}" class="btn btn-primary">View device</a>
    <a href="/" class="btn btn-ghost">Back to dashboard</a>
  </div>
{{else}}
  <h1>Register Device</h1>
  <div class="card">
    <form action="/devices/new" method="post">
      <label for="name">Device name</label>
      <input id="name" name="name" required placeholder="e.g. Kid's Phone">
      <p class="text-muted text-sm">This name appears on the dashboard.</p>
      <button type="submit" class="btn btn-primary">Register</button>
    </form>
  </div>
{{end}}
```

### Verification

```
Manual: Visit /login, verify centered card, toggle password visibility, enter
wrong password to see error, enter correct password to log in.
Visit /devices/new, register a device, verify token display, click link to device.
Existing tests must pass.
```

---

## Stage 6: Responsive design & mobile

**Goal**: The dashboard is usable on a phone browser (parent checking on the
go) and on tablet/desktop.

**Status**: Complete

### 6.1 Responsive breakpoints

Add media queries to `layout.gohtml`:

```css
/* Mobile-first base styles (already in place) */

/* Tablet+ */
@media (min-width: 768px) {
  main { padding: 0 2rem; }
  .card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(360px, 1fr)); gap: 1rem; }
}

/* Desktop */
@media (min-width: 1024px) {
  main { max-width: 960px; }
}
```

### 6.2 Dashboard card grid

- On mobile: single column (current behavior, fine).
- On tablet+: 2-column card grid for device cards (`.card-grid` class).
- Each card is `min-width: 360px`.

### 6.3 Table overflow

Tables on narrow screens:
- Wrap in `<div class="table-wrapper">` with `overflow-x: auto`.
- Add `-webkit-overflow-scrolling: touch` for iOS.
- Ensure row hover states still work.

### 6.4 Form layouts

- On mobile: inputs full-width, stacked labels.
- On desktop: nothing changes (forms are simple enough).

### Verification

```
Manual: Resize browser to 375px, 768px, 1024px widths. Verify:
- No horizontal scroll (except wrapped tables, which scroll individually).
- Cards stack on mobile, 2-column on tablet+.
- Buttons large enough to tap (≥44px touch target).
- Nav does not wrap awkwardly (links stay inline).
```

---

## Stage 7: Accessibility & polish pass

**Goal**: Address basic a11y concerns and add final polish details.

**Status**: Complete

### 7.1 Skip link

```html
<a href="#main-content" class="skip-link">Skip to main content</a>
```

CSS: visually hidden until focused (`position: absolute; top: -100%` →
`top: 0` on `:focus`).

### 7.2 Focus indicators

- All interactive elements get visible focus ring:
  `:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }`
- This is already partially covered by Stage 1 — verify completeness.

### 7.3 Color contrast

Check all text/background combinations:
- Nav: white text on `#16213e` → contrast ratio ~13:1 ✓
- Primary button: white on `#4361ee` → ~5:1 ✓
- Text on surface: `#1a1a2e` on `#ffffff` → ~15:1 ✓
- Muted text: `#6b7280` on `#f8f9fa` → ~4.6:1 (acceptable for non-critical text)

### 7.4 Semantic HTML

- Ensure all pages use proper heading hierarchy (`h1` → `h2` → `h3`).
- Tables get `<thead>` / `<tbody>` separation.
- Forms use `<label>` with matching `for`/`id` attributes.
- Navigation uses `<nav>` with `aria-label`.

### 7.5 Favicon

- Add a simple emoji-based SVG favicon inline in `<head>`:
  ```html
  <link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>🛡️</text></svg>">
  ```

### 7.6 Page titles

- Each page gets a unique `<title>`:
  - Dashboard: `pcontrol — Devices`
  - Device: `pcontrol — <device name>`
  - Limits: `pcontrol — Limits · <device name>`
  - Login: `pcontrol — Sign in`
- Pass `Title` through the `layoutData` struct.

### Verification

```
Manual: Tab through every page; confirm all focusable elements get a visible ring.
Run axe DevTools or Lighthouse accessibility audit on /, /devices/1, /login.
Verify no violations at WCAG 2.1 AA.
```

---

## Stage 8: Tests & final verification

**Goal**: All existing tests pass; new tests cover changed behaviors.

**Status**: Not Started

### 8.1 Update existing tests

- Check `dashboard_test.go`, `limits_test.go`, `router_test.go` for any
  assertions that rely on old CSS classes or HTML structure.
- Tests that assert on text content (device names, "0 min", "Last usage report:")
  should pass without changes. Tests that assert on specific CSS class names
  need updating.

### 8.2 Add new tests

- **Login page**: test that password toggle exists (manual verification for
  JS behavior; Go test for page structure).
- **Register page**: test that the new template renders correctly (form mode
  and success mode).
- **Empty states**: test that empty-state messages render for:
  - Dashboard with 0 devices.
  - Device with 0 apps / 0 websites.
  - Limits page with 0 limits / 0 exclusions.
- **HTMX feedback**: test that HTMX responses contain expected structure
  (new row `<tr>`, spinner element).

### 8.3 Full pass

```sh
cd server && go test -count=1 ./... && go vet ./...
```

All existing tests must pass. New coverage should not regress.

### Verification

```
CI: Push a branch and verify GitHub Actions passes (server-tests.yml).
```

---

## Summary of files changed

| File | Stage(s) | Change |
|---|---|---|
| `templates/layout.gohtml` | 1, 6, 7 | Complete CSS rewrite, nav/footer, skip-link, favicon |
| `templates/dashboard.gohtml` | 2 | Card redesign, empty state, progress bars |
| `templates/device.gohtml` | 3 | Breadcrumb, tables, history chart, management section |
| `templates/limits.gohtml` | 4 | Layout, inline edit, HTMX spinners |
| `templates/login.gohtml` | 5 | Centered card, password toggle, error styling |
| `templates/register.gohtml` | 5 | New file — proper template for device registration |
| `templatesData.go` | 5 | Add `registerData` struct, possibly `Title` field |
| `render.go` | 5, 7 | Pass `Title` to layout; remove `renderInline` |
| `dashboard.go` | 5 | Use `register.gohtml` template; remove inline HTML |
| `dashboard_test.go` | 8 | Update assertions if needed; add empty-state tests |

### Files NOT changed

| File | Reason |
|---|---|
| `auth.go`, `limits.go`, `router.go` | Logic unchanged; only templates change |
| `static/` | No new static assets added (favicon is inline data URI) |
| `store/`, `api/`, `domain/` | Backend unchanged |

---

## Design decisions

| Decision | Rationale |
|---|---|
| No CSS framework / build step | Single-binary deploy; zero-dependency ethos of the project |
| Inline `<style>` in `layout.gohtml` | Same pattern as current; no file-serving complexity |
| CSS custom properties | DRY colors/spacing without a preprocessor; modern browsers support them |
| Emoji icons (🛡️, 📱, 🔋) | No icon library dependency; works everywhere |
| Emoji SVG favicon | No extra file; works in all modern browsers |
| HTMX for all dynamic behavior | Already the project standard; no JS framework needed |
| Password toggle via vanilla JS | 5-line inline `<script>`; no library overhead |
| No dark mode | Adds CSS variable fallback complexity; defer to future plan |

---

## Non-goals (this plan)

- Dark mode toggle.
- Real-time dashboard (WebSocket / SSE polling).
- Usage charts (line/bar charts with a charting library).
- Multi-user auth or role-based access.
- Email/push notifications from the web UI.
- Internationalization (i18n).
- PWA / service worker.
- Custom favicon file (emoji SVG is sufficient).
