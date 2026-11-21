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

- **Fuzzier app search**, on top of upstream's fzf-style abbreviation matching (`ytm` finds YT Music) and typo tolerance (`settimgs` finds Settings):
  - Words in any order: `music yt` finds YT Music, `store play` finds Play Store
  - Typos in part of a name: `yuotube` finds YouTube Music, and `clac` finds Calculator before you have typed it all
- **Switch apps along the bottom edge** (Settings → Gestures → Navigation)
  - Swipe right along the bottom edge for the previous app and left to go back, like the system's quick switch gesture. Honor (MagicOS) and Huawei (EMUI) turn that gesture off when a third-party launcher is the default home app; this brings it back
  - Uses the launcher's accessibility service: a thin, invisible strip over the gesture bar catches sideways swipes. Swipe up for home still goes to the system, and taps on the strip go through to the app below it

### Notes
- Bottom edge app switching follows the order in which you opened apps after the accessibility service started, so the first switch after a reboot only knows the apps opened since. It switches by package, so a work profile app opens its personal copy.
- Launch times are recorded starting with this version, so *Recently used* fills in as you use apps.
- A database migration (v34 → v35) adds the `lastLaunch` column. Backups now include it too.
