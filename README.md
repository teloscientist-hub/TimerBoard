# TimerBoard

TimerBoard is a local-first Android timer app built with Kotlin and Jetpack Compose.

## Current version

Version: `1.8`

- Saved countdown timers
- Saved interval timers with warmup, work, rest, cooldown, and rounds
- Full-screen interval phase display
- Default starter timers: Coffee, Stretch, Focus
- Create timers with a name, duration, and color
- Tap a timer's time display to edit hours, minutes, and seconds
- Choose from TimerBoard alarm patterns or Android's built-in phone sounds
- Running timers show the 24-hour signal time down to the second
- Running timers keep a persistent notification showing the next timer to finish
- Running timer notification includes pause-all and reset-all actions
- Start, pause, reset, delete individual timers
- Visible edit action and delete confirmation for safer timer management
- Loading and completion states for clearer timer feedback
- Start all and pause all timers from the top bar
- Local persistence with Room, including migration from the earlier `SharedPreferences` store
- Completion tone and vibration

## Open in Android Studio

1. Open Android Studio.
2. Choose **Open**.
3. Select this folder:
   `/Users/mark/Library/CloudStorage/Dropbox-BPTNB/mark lewis/_GPT Meta/App Development/TimerBoard`
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

1. Add Pomodoro mode with daily focus stats.
2. Add history and completed timer analytics.
