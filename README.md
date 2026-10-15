# न्यूज़ अड्डा (News Adda)

A fast, text-only Hindi news aggregator for **Uttar Pradesh politics**, built to spot political developments early so they can be researched and turned into news videos.

- **Android app** (Kotlin + Jetpack Compose), text only, no images. The interface is in **English or Hindi** (switch in Settings → Language); the **news itself — headlines, summaries, statements — is always in Hindi**, exactly as published.
- **Backend** (Python) that collects UP news from publisher RSS feeds and Google News, groups duplicate reports, tags leaders/districts/parties, marks breaking news and allegations, and publishes a feed for the app.
- Runs for free on **GitHub Actions**: one workflow collects news every ~5 minutes, another builds and publishes the APK.

## Install

1. On your phone, open **Releases** on this repository page and download the newest `NewsAdda-vX.Y.Z.apk`.
2. Open it and allow "Install unknown apps" for your browser when asked.

After that the app updates itself:

- It checks for a new version **every time it starts** and **in the background every 1, 3, 6 or 12 hours** (Settings → App updates).
- A new version is downloaded automatically (can be turned off), verified (SHA-256, package name and version) and you get a notification. Tap **Install**; Android always asks you to confirm an install — no ordinary app can install silently.
- The first time, Android asks you to allow News Adda to install apps; allow it once.

`-debug.apk` files in each release are a separate test app ("News Adda (debug)"); they install alongside the normal app and never self-update.

## How it works

```
 Publisher RSS feeds ─┐
 Google News RSS  ────┤   GitHub Actions "Collect news" (every ~5 min)
 X API (optional) ────┘   fetch → parse → UP filter → tag → group → label
                                     │
                                     ▼
                 data branch: feed.json, health.json, state.db.gz
                                     │  (raw.githubusercontent.com)
                                     ▼
          Android app: refresh on open / on return / every 1–30 min /
          pull-down / "अभी अपडेट करें"; cached for offline reading
```

The phone never scrapes publishers; it only reads the backend's feed. Each story keeps **every publisher's link**, publication time, update time and the time News Adda first saw it, all shown in IST.

### What the app looks like

A card feed on a neutral background. Every story shows the **publisher's own site icon** next to its name, how long ago it was published, the leaders and districts it mentions, and labels such as Breaking or Allegation. Stories carried by several outlets show their icons stacked with "N sources". Home opens with a red **Breaking now** carousel and the **Live wire** (see below), then the latest stories. When a new breaking story arrives while the app is open, a red banner slides in with a short vibration. Light and dark themes follow the phone.

Publisher icons are the sites' own favicons, fetched by the phone from Google's favicon service and cached; if one cannot load, a lettered badge is shown instead.

### Speed

- The app refreshes when opened, when you return to it, every 1–30 minutes while open (5 by default), on pull-down and on **Update now**.
- Each refresh downloads a small **delta** (stories changed in the last 45 minutes) instead of the whole feed, with a cache-buster so GitHub's CDN cannot serve a stale copy. The full feed is fetched only when the phone's copy is older than 40 minutes.
- **Live wire**: on every refresh the phone also reads the fastest public feeds itself (Amar Ujala UP, News18 UP, TV9 UP, ABP UP, India TV UP and Google News searches for UP politics, leaders and political rows; the list comes from `live_wire: true` in `sources.yaml`; Google results only from trusted publishers). New headlines can therefore appear within minutes of publication even before the backend's next run. They are shown as-is, marked "Direct", not yet grouped or labelled. You can turn this off in Settings.
- The backend polls the fastest sources on every run.

### App sections

Home · Breaking · Leader tracker · Controversies · Seats & Bahubalis · Statements · Districts · Saved · Settings (in Hindi: होम · ब्रेकिंग न्यूज़ · नेता ट्रैकर · राजनीतिक विवाद · सीट और बाहुबली · नेताओं के बयान · जिला समाचार · सहेजी गई · सेटिंग्स), plus search with filters for party, category, leader/district/seat, source and date. Search accepts Hindi or English: typing "yogi" or "lucknow" finds Hindi headlines through the English names of leaders and districts.

### Labels and what they mean

Labels are shown in the interface language (English names below, Hindi in brackets).

| Label | Rule |
|---|---|
| Breaking (ब्रेकिंग) | Headline signals (resignation, arrest, suspension, defection, ticket/candidate list, Election Commission, bypoll, High/Supreme Court, conviction/bail, raids…) in a political story, **or** 3+ publishers reporting within 2 hours. Shown for 6 hours. The reasons are listed in every story. |
| New (नई खबर) | First seen in the last 30 minutes. |
| Allegation / Counter-attack (आरोप / पलटवार) | The report describes an allegation / counter-attack. An allegation is never presented as fact. |
| Official statement (आधिकारिक बयान) | Official wording (government order, Commission, spokesperson…). |
| Reported by N sources | Reported by N different publishers (one outlet through several feeds counts once). |
| Awaiting confirmation (पुष्टि की प्रतीक्षा) | A political single-source report that is breaking, an allegation, or mentions legal action. |
| Mentioned in report: … | Legal status words used by the report (allegation, FIR, investigation, arrest, chargesheet, bail, acquittal, conviction). The app never calls anyone a criminal. |

All labels come from transparent keyword rules (`backend/newsadda/classify.py`), not popularity or ideology. They can be wrong; the reasons are always visible.

## What gets in (scope and credibility)

- **Uttar Pradesh only.** A story passes when it names UP (state, any of the 75 districts, a tracked seat, UP landmarks such as Gorakhnath temple), a tracked UP leader or regional figure, or a UP party (SP, BSP, SBSP, RLD, Apna Dal (S), Nishad Party, ASP). Without such a signal it depends on the source (`up_section` in `sources.yaml`): district/city editions keep their stories unless they are about another state; the UP state sections need a local-governance word; searches for UP leaders/parties need a state-politics word; national feeds (Aaj Tak, NDTV India, Bharat Samachar) and broad Google searches need a strong UP signal. National news without a UP leader is dropped, and anything naming another state or country without a UP signal is dropped.
- **Hindi only.** Headlines must be at least half Devanagari.
- **Trusted publishers only.** Google News results are kept only when the original publisher is on the `trusted_publishers` list in `sources.yaml` (established Hindi newspapers, TV channels and agencies) or is one of the direct-feed publishers. Unknown blogs, aggregators and portals are dropped. The phone's Live wire applies the same list.
- **Politics first.** Home shows UP politics by default (switch with the "UP politics" chip); leaders, seats, bahubali/regional figures, controversies, statements and counter-attacks are tagged and prioritised for gists.
- Everything filtered is counted in `health.json` (`filtered_last_run`).

### Gists (2–3 lines) and direct links

Many items, especially from Google News, arrive as a headline only. Every run, for up to 40 of the most important new items (breaking first, then stories naming leaders, then other politics), the backend:

1. resolves the Google News link to the **publisher's own article URL**, so "Read more" opens the publisher directly, and
2. reads that page's own preview description (the `og:description` the publisher writes) as the gist, trimmed to about 300 characters and marked "From the publisher's page".

Text is never rewritten or invented. `robots.txt` is honoured, the step is limited to 45 seconds so it never delays the feed, and if Google starts refusing requests the remaining items are retried next run. When no description exists the card shows the headline only. Results are in `health.json` (`gists_last_run`).

## Source status (live check, 13 Oct 2026, from GitHub Actions)

95 live RSS feeds plus 16 Google News sitemaps (Jagran, Bhaskar, Aaj Tak, NBT, Zee, UP Tak, Amar Ujala, Live Hindustan, News18, ABP, TV9, India TV, Patrika, Republic Bharat, Prabhat Khabar, The Lallantop): portals list each article in their news sitemap the moment it is published, so even portals without RSS are read directly within minutes; the 2–3 line gist is read from the article page in the same run. The fastest UP desks (Amar Ujala UP/Lucknow, News18 UP/Lucknow, TV9, ABP, India TV, Live Hindustan, Bharat Samachar) are read on **every** collection run; the main Google News political searches every ~4 minutes; district editions and other searches every 10–15 minutes.

| Publisher | How it is collected | Status |
|---|---|---|
| Amar Ujala — UP, Lucknow and 51 district editions (all political hubs: Varanasi, Gorakhpur, Prayagraj, Ayodhya, Azamgarh, Rampur, Mainpuri, Kannauj, Raebareli, Amethi, Ghazipur, Mau…) | Official RSS | ✅ all verified |
| News18 Hindi — UP + Lucknow, Varanasi, Kanpur, Meerut, Agra, Ayodhya | Official RSS | ✅ verified |
| Live Hindustan — Lucknow/UP + Gorakhpur, Ayodhya, Agra, Bareilly, Basti | Official RSS | ✅ verified |
| TV9 Bharatvarsh (UP), ABP News (UP), India TV (UP) | Official RSS | ✅ verified |
| Aaj Tak, NDTV India, Bharat Samachar (national; strict UP filter) | Official RSS | ✅ verified |
| Dainik Bhaskar, UP Tak, Dainik Jagran, Navbharat Times, Aaj Tak UP, Zee News, Jansatta, The Lallantop, Patrika, ETV Bharat | Google News RSS `site:` searches (no reachable public feed: 404/403/none) | ✅ via Google News, with direct links resolved |
| Targeted searches: UP politics, Yogi Adityanath, Akhilesh Yadav/SP, Mayawati/BSP, allies & opposition, regional figures, bahubali/strongmen, political rows (वार-पलटवार), UP cabinet, elections, panchayat/MLC/bypolls, Lucknow section | Google News RSS | ✅ verified |
| X (Twitter) posts of leaders | Official X API v2 | ⛔ needs `X_BEARER_TOKEN` (paid X API plan); shown as unavailable until set |

Feeds that were tested and failed (Zee, Patrika, UP Tak, Lallantop, PIB, DD News, Jansatta direct) are covered through Google News. The daily source check re-tests 100+ candidates (`backend/newsadda/data/probes.yaml`).

Current per-source status is always in the app (Settings → News sources) and in `health.json` on the `data` branch. The daily **source check** re-tests every URL and autodiscovers feeds; results are on the `ci-backend` branch (`discovery.txt`).

## Making collection run every 5 minutes reliably

GitHub's built-in schedule is best-effort: runs can be late, and on this repository it had not started on its own a few hours after setup. Two ways to make collection dependable:

**Option A — free external trigger (about 5 minutes to set up).**

1. On GitHub: Settings → Developer settings → Personal access tokens → **Fine-grained tokens** → Generate. Repository access: only `news-adda`. Permissions: **Actions: Read and write**. Copy the token.
2. On [cron-job.org](https://cron-job.org) (free), create a job:
   - URL: `https://api.github.com/repos/piyushpriyanshu/news-adda/actions/workflows/collect.yml/dispatches`
   - Schedule: every 5 minutes — or **every 2 minutes** for the fastest breaking news (the fastest feeds are then read every 2 minutes; public repositories have unlimited free Actions minutes). Request method: **POST**.
   - Headers: `Authorization: Bearer <your token>`, `Accept: application/vnd.github+json`, `Content-Type: application/json`
   - Body: `{"ref":"main"}`
3. The Actions tab will show a "Collect news" run every 5 minutes.

**Option B — run the backend on a small always-on server** (any VPS or free-tier VM with Docker), then set its address in the app (Settings → Backend address). This gives the fastest results (sources checked every minute or two) and does not depend on GitHub at all. See *Backend* below.

## Configuration

| What | Where |
|---|---|
| Sources (add, remove, disable, polling interval) | `backend/newsadda/data/sources.yaml` |
| Leaders, regional figures, parties, 75 districts, seats | `backend/newsadda/data/entities.yaml` (sent to the app inside the feed, so no app update is needed) |
| X API | repository secret `X_BEARER_TOKEN` |
| Optional AI Hindi summaries of English publisher descriptions | secret `ANTHROPIC_API_KEY` + repository variable `NEWSADDA_AI_SUMMARY=1` (off by default; everything works without it) |
| App signing key (recommended) | secrets `NEWSADDA_KEYSTORE_BASE64`, `NEWSADDA_KEYSTORE_PASSWORD`, `NEWSADDA_KEY_ALIAS`, `NEWSADDA_KEY_PASSWORD` |

No secret is ever placed in the APK or the feed.

## Building

### APK on GitHub (no setup)

Every push to `main` that changes `android/` runs **Build Android APK**: unit tests, debug + release APKs, and a GitHub Release with `update.json` for the in-app updater. The **emulator smoke test** workflow installs the debug build on an Android emulator, opens every section against the live feed, checks offline mode and fails on any crash; screenshots are on the `ci-android-smoke` branch.

### APK locally

Requirements: JDK 17 and the Android SDK (platform 36, build-tools 35).

```bash
cd android
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk (signed)
```

Debug builds use the id `com.newsadda.app.debug`, a different name, and no self-update. Release builds are minified and signed.

**Signing note:** a release keystore is committed in `android/keystore/` (the same approach as this account's other app) so CI can sign builds that update over each other. Because the repository is public, anyone can read that key. For stronger protection, create your own key, store it in the secrets listed above (CI uses secrets first), and remove the committed one — note that changing the key means reinstalling the app once.

### Backend

The default backend is the **Collect news** workflow (nothing to set up). To get faster updates (the app supports a 1-minute refresh), run the server yourself and set its URL in the app (Settings → Backend address → `https://your-server/feed.json`):

```bash
cd backend
pip install -r requirements.txt
python -m newsadda serve --port 8000      # API + built-in scheduler
# or
docker build -t newsadda backend && docker run -p 8000:8000 -v newsadda:/data newsadda
```

Other commands: `python -m newsadda run --export out/` (one pass, write feed files), `python -m newsadda discover --out report.json` (live-check every feed), `python -m newsadda sources`. Settings are environment variables, listed in `backend/.env.example`.

API: `GET /feed.json[?since=…]` (incremental), `GET /api/v1/stories` (search: `q, leader, figure, party, district, seat, source, category, label, breaking, statement, from, to`), `GET /api/v1/sources`, `GET /api/v1/entities`, `GET /health`, `POST /api/v1/refresh` (admin token).

Tests: `pip install -r requirements-dev.txt && python -m pytest`.

## Tested

- **Backend: 54 automated tests** — feed parsing, IST/UTC timestamps (missing, zone-less and future times are never invented or silently trusted), Hindi normalisation, UP relevance, entity tagging, breaking/allegation/statement/legal-status labels, near-duplicate grouping (same story grouped; different stories kept apart), publisher aliasing, source failures and fallbacks, conditional requests and backoff, incremental feed, API search and filters, X post parsing.
- **Live sources** checked from GitHub Actions (table above) and the collector producing a real feed (~600+ stories in the first run).
- **Android: 11 unit tests** — parsing the backend's feed format, cache merge/retention, new-story detection, search folding and English-name search, refresh interval, source hiding, IST formatting and labels in both languages.
- **Release build** compiles, is signed and is published with `update.json`; `releases/latest/download/update.json` resolves.
- **Emulator smoke test** against the live feed: app starts in English, every section opens, switching to Hindi works with news unchanged, background → foreground refresh, offline mode shows cached news with a notice, no crashes.

Not tested on a physical phone, and the in-app update install step needs a second release to exercise end to end.

## Known limitations

- **Freshness:** GitHub runs scheduled workflows on a best-effort basis (often 5–15+ minutes, sometimes skipped; on this repository the schedule had not started on its own a few hours after setup). Use one of the options in *Making collection run every 5 minutes reliably*. The phone's Live wire works regardless.
- Running a news collector continuously on GitHub Actions is a grey area of GitHub's Actions terms, which are meant for software-project automation. A small server of your own (Option B) avoids that question entirely.
- **Background on the phone:** Android runs background work at most every ~15 minutes and may delay it (battery saver, manufacturer restrictions). One-minute refresh applies only while the app is open.
- **Google News links** open news.google.com, which forwards to the publisher; the publisher name is shown, marked "गूगल न्यूज़".
- **X posts** need a paid X API plan; without it the statements section shows statements reported in the news.
- **No full-article extraction:** summaries are trimmed from what each publisher's feed provides; many items are headline-only.
- **Rule-based classification** has false positives/negatives; grouping is conservative, so the same event may sometimes appear as two cards.
- Scheduled workflows in public repositories pause after 60 days without repository activity; any push re-enables them.
- Publishers' feeds and terms can change; the daily source check shows when a feed stops working.
