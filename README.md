# TimerBoard

TimerBoard is a local-first Android timer app built with Kotlin and Jetpack Compose.

## Current version

Version: `2.6`

- Complete Material 3 color scheme (light and dark) instead of a partially themed default, generated from TimerBoard's brand colors
- Automatic Material You dynamic color on Android 12+, matching the device wallpaper
- Dark theme that follows the system setting
- Subtle tonal elevation on timer, history, and filter cards for visual depth instead of flat surfaces
- Saved countdown timers
- Saved Pomodoro timers with focus, break, session, and long-break cycles
- Saved interval timers with warmup, work, rest, cooldown, and rounds
- Multiple saved stopwatches with centisecond display
- Stopwatch laps, splits, best/slowest lap labels, rename, delete, and share
- Full-screen interval and Pomodoro phase display
- Default starter timers: Coffee, Stretch, Focus
- Create timers with a name, duration, and color
- Tap a timer's time display to edit hours, minutes, and seconds
- Choose from TimerBoard alarm patterns or Android's built-in phone sounds
- Choose how many times a timer alarm repeats
- Duration fields select their current value on focus and allow large minute values
- Create and edit dialogs include Save and Start
- Running timers show the 24-hour signal time down to the second
- Running timers keep a persistent notification showing the next timer to finish
- Running timer notification includes pause-all and reset-all actions
- Running timer notification includes a resume-all action for paused timers
- Start, pause, reset, delete individual timers
- Snooze completed timers for 5 minutes
- Visible edit action and delete confirmation for safer timer management
- Loading and completion states for clearer timer feedback
- Duplicate timer action for repeated workflows
- Move timers up or down to customize board order
- Dynamic launcher shortcuts for quickly starting saved timers
- Export and import saved timer presets as JSON backups
- Quick countdown presets in the create timer dialog
- Interval templates for Tabata, HIIT, Boxing, and Focus Sprint
- Notification permission is requested when timers start, not on app launch
- Start all and pause all timers from the top bar
- Local persistence with Room, including migration from the earlier `SharedPreferences` store
- Active timer runtime state is restored after app process death
- Completed timers are recorded in local history
- Daily completed-timer count and total completed time are shown in the app status line
- Dedicated history screen with recent completions, today summary, refresh, and clear-history confirmation
- History can be filtered by all, countdown, Pomodoro, or interval timers
- Filtered history can be exported through Android share targets
- Pomodoro sessions have dedicated daily session and focus-time reporting in History
- Pomodoro templates include Classic, Short, and Deep cycle presets
- Completion tone and vibration

## Open in Android Studio

1. Open Android Studio.
2. Choose **Open**.
3. Select this folder:
   `/Users/mark/A/code/TimerBoard`
4. Let Gradle sync, then run the `app` configuration.

## Versioning

Every app change should update `versionCode` and `versionName` in `app/build.gradle.kts`.

- Significant changes: increment by `0.1`, for example `1.0` to `1.1`.
- Less significant changes: increment by `0.01`, for example `1.0` to `1.01`.
- Always increment `versionCode` as an integer. The current convention is `versionName * 100`, so `1.0` is `100`, `1.01` is `101`, and `1.1` is `110`.

## Build verified

The debug build was verified from the command line with Android Studio's bundled JDK:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug
```

## Suggested next steps

1. Add stopwatch history persistence into the History screen.
2. Add per-timer alarm repeat spacing and dismiss controls.
