# TimerBoard UX Build Plan

Based on `../top-ux-design-for-phone-apps-guidebook.md`.

## Priority 1: Obvious and Recoverable Core Actions

- Add visible edit controls instead of relying only on tapping the timer text. Completed in v1.7.
- Confirm destructive delete actions. Completed in v1.7.
- Make card actions more explicit and accessible. Completed in v1.7 and v1.8.
- Keep one primary action per timer card: start/pause. Completed in v1.8.

## Priority 2: Accessibility and State Feedback

- Add semantic labels for icon-only controls. Completed in v1.7.
- Improve large text resilience for timer cards and dialogs. Completed in v1.8 by restructuring card actions.
- Add clear empty, loading, disabled, and completion states. Completed in v1.8.
- Audit touch target spacing. Completed across v1.7-v1.9 by using Material buttons, icon buttons, and spaced rows.

## Priority 3: Workflow Efficiency

- Add quick timer presets and recent durations. Quick countdown presets added in v1.82.
- Add duplicate timer action for repeated workflows. Added in v1.81.
- Add better interval defaults and templates. Interval templates added in v1.83.

## Priority 4: App Reliability UX

- Persist active timer runtime state for process death recovery. Completed in v1.9.
- Add notification resume support after persistence is in place. Resume-all notification action added in v1.9.
- Improve permission timing and explanation. Notification permission now waits until timer start in v1.84.

## Priority 5: Measurement and Review

- Add completed timer history. Completed in v1.9.
- Add lightweight daily stats. Completed in v1.9.
- Add manual UX checklist before releases. Completed in `UX_RELEASE_CHECKLIST.md`.

## Status

The UX build plan from the phone-app UX guidebook is complete as of TimerBoard v1.9. Further work should move into a new feature plan rather than extending this implementation checklist.
