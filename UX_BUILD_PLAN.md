# TimerBoard UX Build Plan

Based on `../top-ux-design-for-phone-apps-guidebook.md`.

## Priority 1: Obvious and Recoverable Core Actions

- Add visible edit controls instead of relying only on tapping the timer text.
- Confirm destructive delete actions.
- Make card actions more explicit and accessible.
- Keep one primary action per timer card: start/pause.

## Priority 2: Accessibility and State Feedback

- Add semantic labels for icon-only controls. Started in v1.7.
- Improve large text resilience for timer cards and dialogs. Started in v1.8 by restructuring card actions.
- Add clear empty, loading, disabled, and completion states. Loading and completion states added in v1.8.
- Audit touch target spacing.

## Priority 3: Workflow Efficiency

- Add quick timer presets and recent durations. Quick countdown presets added in v1.82.
- Add duplicate timer action for repeated workflows. Added in v1.81.
- Add better interval defaults and templates. Interval templates added in v1.83.

## Priority 4: App Reliability UX

- Persist active timer runtime state for process death recovery.
- Add notification resume support after persistence is in place.
- Improve permission timing and explanation. Notification permission now waits until timer start in v1.84.

## Priority 5: Measurement and Review

- Add completed timer history.
- Add lightweight daily stats.
- Add manual UX checklist before releases.
