# ActiveTasks

A companion app to [MicroTasking](https://github.com/vemcg/MicroTasking): a traditional
to-do list, sharing the same Google Sheet (each tab is a list, each checked row is an item),
prioritized with an Eisenhower matrix (important x urgent) instead of a flat priority field.

MicroTasking nudges you to do things periodically/semi-randomly from a pool. ActiveTasks is the
opposite mode: a pull, browse-and-pick list you check when you're ready to plan, not when
you're prompted.

## Status

Lists are the Sheet's tabs, shown as a swipeable carousel that opens on the last list you
viewed (else the highest-priority one). A list only contains tasks you've activated - referred
from MicroTasking, or found in ActiveTasks itself with the Find Task (+) buttons - ranked by an
Eisenhower-matrix priority; each item is a card with Complete (for
now) / Fully complete buttons underneath. See [SPEC.md](SPEC.md) for the full intended feature
set and [PUNCH_LIST.md](PUNCH_LIST.md) for what's next.

## Structure

- `app/` — Android application module (Kotlin + Jetpack Compose)
  - `MainActivity.kt` — Activity + Compose screens
  - `ToDoData.kt` — data model, persistence, priority/sort logic
  - `SheetImport.kt` — Google Sheet tab discovery + CSV fetch (generic, no app-specific mapping)
- `settings.gradle.kts`, `build.gradle.kts` — Gradle project config
- `.github/workflows/release-apk.yml` — CI: build, GitHub release, install page + QR on GitHub Pages
- `scripts/generate_install_page.py` — install/onboarding page generator

## License

Copyright (c) 2026 Vern McGeorge. All rights reserved. See [LICENSE](LICENSE).
