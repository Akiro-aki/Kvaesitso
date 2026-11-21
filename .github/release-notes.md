Fork of [Kvaesitso](https://github.com/MM2-0/Kvaesitso) with a few extra launcher features.

Installs as **`de.mm20.launcher2.fork`**, next to any official Kvaesitso build.

> **Updating from v1.41.0-fork.1:** builds before this one were signed with a throwaway key, so Android refuses to update over them. Back up your settings (Settings → Backup & restore), uninstall the old fork once, install this one and restore. From now on, every release updates in place.

### New in this release
- **Split swipe down** (Settings → Gestures → Split swipe down), like TBLauncher's "fling from the left / right side"
  - Swiping down on the left and right half of the home screen runs different actions, for example notifications on the left and quick settings on the right
- **Bottom edge app switching, reworked for Honor and Huawei**
  - Honor's own launcher briefly opens its recents screen on every sideways swipe along the bottom (the flicker). The switch now waits for it instead of getting swallowed by it, and that screen no longer counts as going home, so the right app opens
  - Works in both directions: the first swipe goes to the previous app whichever way you swipe, more swipes that way go further back, and swiping the other way comes forward again
  - Quick swipes add up: swiping three times goes back three apps
  - Snappier: haptic feedback as soon as the swipe ends, a shorter wait and a faster slide
  - The list of recent apps survives updates and restarts, and other installed launchers count as normal apps
- **Scroll wallpaper, now with static wallpapers** (Settings → Home screen → Scroll wallpaper)
  - Android no longer scrolls static wallpapers, so the launcher now draws a static wallpaper itself and pans it, with the wallpaper blur applied. It follows wallpaper changes, for example from Paperize. This needs all files access to read the wallpaper; the setting shows a button to grant it. Live wallpapers keep scrolling on their own
  - Swiping up or down to a page also pans sideways, since most wallpapers (and Paperize's live wallpaper) only scroll sideways
  - **Sideways swipes** without an action can move the wallpaper: *Only when a page opens*, *Pan and stay* (like moving between home pages) or *Follow and spring back*
  - **Keep sliding**: with *Pan and stay*, the wallpaper keeps sliding after a flick and slows down
  - Fixed: scrolling never worked, because the effect followed a stale copy of the gesture state

### Also in this fork
- **App list sort order** (Settings → Search → Apps → Sort order): *Alphabetical*, *Recently used (new apps first)* (KISS-style), *Most used*, *Adaptive* and *Recently installed*
- **Fuzzier app search**: words in any order (`music yt` finds YT Music) and typos in part of a name (`yuotube`, `clac`)
- **Switch apps along the bottom edge** (Settings → Gestures → Navigation): brings back the quick switch gesture on systems that turn it off for third-party launchers, through the launcher's accessibility service

### Notes
- Bottom edge app switching knows the apps you opened since you turned it on, and switches by package, so a work profile app opens its personal copy.
- How far the wallpaper can scroll depends on how much wider (or taller) the image is than the screen.
