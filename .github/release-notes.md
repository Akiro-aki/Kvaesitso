Fork of [Kvaesitso](https://github.com/MM2-0/Kvaesitso) with a few extra launcher features.

Installs as **`de.mm20.launcher2.fork`**, next to any official Kvaesitso build.

### New
- **App list sort order** (Settings → Search → Apps → Sort order)
  - *Alphabetical*: the upstream default
  - *Recently used (new apps first)*: KISS-style. The most recently launched apps come first, and a freshly installed app counts as just used, so it shows up at the top
  - *Most used*: sorted by launch count
  - *Adaptive*: Kvaesitso's ranking weight, a mix of recent and frequent use
  - *Recently installed*: newest installs first
- **Scroll wallpaper** (Settings → Home screen → Wallpaper → Scroll wallpaper)
  - Pans the wallpaper as you swipe between the home screen and other pages: left/right swipes pan horizontally, up/down swipes pan vertically. The effect only shows with a wallpaper wider or taller than the screen.

### Already included from upstream
- **Fuzzy app search**: fzf-style abbreviation matching (`ytm` finds YT Music) plus typo tolerance (`settimgs` finds Settings)

### Notes
- Launch times are recorded starting with this version, so *Recently used* fills in as you use apps.
- A database migration (v34 → v35) adds the `lastLaunch` column. Backups now include it too.
