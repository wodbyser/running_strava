# UI Guidelines (owner feedback, binding for every phase)

Written 2026-10-30 after the owner's review of Phase 3 (Prestaties) and Phase 2 (Herstel). The first builds of both pages were
correct in their numbers but hard to read: long tables, internal jargon, ISO dates, no visible evolution, no explanation of
difficult terms. The owner had to ask for a full redesign. Build new pages like this from the start.

Reference implementations: `templates/performance.html`, `templates/recovery.html`, `templates/performance-run.html`,
shared pieces in `templates/fragments.html` and the CSS in `templates/layout.html`.

## 1. The core rule

A page must tell the user **at a glance** what is happening and whether it is going well. Numbers alone are not enough.
For every section ask: "Can the owner see in 5 seconds what this means and how it evolves?" If not, redesign it.

## 2. Structure of a page

1. **Most important first.** Start with the conclusion (for example the "Conditietrend" card on Prestaties, the hero card on
   Herstel). The owner decides the order of sections; when in doubt, ask.
2. **Jump links** (`.toc`) on long pages.
3. **Every section has the same shape:**
   - a title, a badge for the kind of number (`gemeten`, `berekend`, `app-schatting`) and a `?` when the title is a hard term;
   - one **lead sentence** in plain Dutch: what you see and what "good" looks like ("Hoe dichter bij 100 %, hoe beter");
   - **summary tiles** (`.stat-grid`) or comparison cards with the key numbers;
   - a **chart** that shows the evolution;
   - the detailed **table folded away** (`<details class="more">`, "Toon als tabel" / "Toon alle runs");
   - an optional `<details class="explain">` "Meer uitleg" with method details and caveats.
4. A **"Begrippen"** list at the bottom with every explained term (anchor `#term-<key>`).

## 3. Charts over tables

- Prefer a chart whenever there is a time dimension: line or dot charts for trends, stacked bars for distributions,
  small multiples (`.chart-grid`) for several metrics side by side.
- Give charts **meaning**, not just data:
  - a **reference band or line** (own norm band, a rule of thumb such as 5 % decoupling, the sleep target, 100 % = start);
  - **colour by meaning** (within norm / deviating / strongly deviating), and explain the colours in a legend;
  - highlight the selected day;
  - break lines over long data gaps instead of drawing a straight line across months without data;
  - tooltips with the full Dutch date and the run name; clickable points that open the run.
- Tables that stay visible must be short and readable: colour only the cells that deviate, right-align numbers,
  show the Strava run **name** next to every date, and link dates to the detail page.
- Small inline visuals work well: rep bars (taller = faster), sleep stage bar, 7-night bars against the target, range bars.

## 4. Language and wording (UI in Dutch)

- Plain Dutch for a runner, not for a statistician. No internal or technical words in the UI:
  "stroomdata" became "detailmetingen", "Runs (eerder tegen recent)" became "9 ervoor, 4 recent" with the date ranges,
  "Verval" became "1.6 % trager", bracket tags such as `[berekend]` became badges.
- **Explain only what is really hard.** Use the `?` popover (`fragments :: help(term)`) with a term from
  `PerformanceGlossary` / `RecoveryGlossary`. Do not explain easy words (Herstel has few hard words: HRV, Body Battery,
  stress, eigen norm, SD, slaaptekort).
- Every verdict comes with one sentence that says **why** ("Te weinig runs voor een oordeel: minstens 5 per periode nodig,
  nu 9 ervoor en 4 recent").
- **When data is missing, say why**, do not show rows of dashes. Example: zone 3 and zone 4+ had no EF comparison because
  steady runs are easy runs by definition; the page now says that. Hide columns that are empty for every row
  (for example temperature) and mention once that they were hidden.
- Keep the project's caution rules: no medical claims, "verband, geen oorzaak", higher or lower is not good or bad on
  recovery data. Colours on recovery mean "distance from your own norm", not good or bad.

## 5. Dates and numbers

- Never show ISO dates (`2026-10-26`) in the UI. Use `PerfDates`: `26 okt 2026`, `vrijdag 30 oktober 2026`,
  `okt 2026`, `week van 20 apr 2026`, ranges `3 okt – 30 okt 2026`.
- Round sensibly; show units; prefer intuitive units (EF as "meter per hartslag" instead of 0.0209).

## 6. Every period is adjustable

- Every window a page uses (28 days, 90 days, 26 weeks, ...) must be changeable by the user, **with the current value as
  the default**. Use the shared `fragments :: windowForm(keys, anchor)` with a settings object
  (`PerformanceWindows`, `RecoveryWindowSettings`): label, unit, min, max, default per key.
- Changing one period keeps all other choices (hidden fields), links keep them too, and a "Alle periodes terug naar
  standaard" link appears when anything differs from the default. Texts follow the chosen value.
- Minimum data counts must scale with the window (70 % rule), and the service must load enough history.

## 7. Before you report: look at it

- **Open every page you changed in a real browser with real data** and look at the screenshots. Headless Chrome works:
  `google-chrome --headless=new --window-size=1280,6000 --virtual-time-budget=4000 --screenshot=out.png <url>`.
- Run a second instance on another port so the owner's app keeps running:
  `./gradlew :runner:bootRun --args='--server.port=8091 --strava.sync.cron=- --garmin.sync.cron=-'`.
  Never stop the owner's app on 8080. Stop your instance with a pattern that cannot match your own shell command
  (for example `pkill -f "server.port=809[1]"` in a command that does not itself contain `8091`).
- Check the data behind a chart before trusting its shape (Phase 3: big gaps in the EF chart were missing stream
  downloads, not a training gap; fixed with "Detaildata aanvullen" on the dashboard).
- HTML pitfalls seen: `<details>` inside `<p>` breaks the paragraph (use `<div>`); SpEL `map[k]` with a bare variable is a
  literal key (use `map.get(k)`); `th:replace` runs before `th:if` on the same element (wrap it).

## 8. Working with the owner

- When a rule or classification changes behaviour, first **show the impact on real data** (the list of runs that change)
  and ask for permission. Example: the hilly auto-lap rule for interval detection.
- Explain proposals in short, concrete, plain language. If the owner writes Dutch, answer in Dutch.
- Do not over-explain in the UI and do not add features the owner did not ask for; do polish what you touch.
