# TimerBoard UX Release Checklist

Use this checklist before app releases that affect UI, workflows, alarms, notifications, or persistence.

## Core Workflows

- Create a countdown timer from a quick preset and from manual input.
- Create a Pomodoro timer from each cycle template and from manual focus/break/session input.
- Create an interval timer from each template and from manual input.
- Start, pause, resume, reset, duplicate, edit, and delete a timer.
- Confirm destructive delete actions require explicit confirmation.
- Confirm completed timers can be restarted from their full duration.

## Timer Reliability

- Start one or more timers, background the app, and confirm the foreground notification remains accurate.
- Pause, resume, and reset timers from notification actions.
- Kill and reopen the app while a timer is running; confirm remaining time is restored from wall-clock time.
- Kill and reopen the app while a timer is paused; confirm remaining time is preserved.
- Confirm a completed timer records history once.
- Open History and confirm recent completions show newest first.
- Confirm History filters show all, countdown, Pomodoro, and interval completions correctly.
- Confirm Export shares only the currently filtered history.
- Confirm Pomodoro completions update the History Pomodoro session and focus-time metrics.
- Confirm Pomodoro full-screen mode shows focus, break, and long-break phases.
- Clear History and confirm the destructive action requires confirmation.

## Accessibility And Layout

- Check timer cards at default and large Android font sizes.
- Confirm all icon-only actions have useful content descriptions.
- Confirm primary actions remain at least Material button/icon-button touch target size.
- Confirm timer names truncate rather than overlapping actions.
- Confirm status text does not push top-bar actions off small screens.

## Permissions And Sounds

- Confirm notification permission is requested only when starting timers on Android 13+.
- Confirm built-in TimerBoard alarms play and vibrate.
- Confirm Android phone sound selection persists and plays.
- Confirm alarm signal time uses 24-hour `HH:mm:ss` format.

## Release Metadata

- Bump `versionName` and `versionCode` in `app/build.gradle.kts`.
- Update `README.md` current version and feature list.
- Run `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew assembleDebug`.
- Commit and push after the build passes.
