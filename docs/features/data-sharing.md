# Share With Other Apps

> **Status:** In progress. Debug and ci builds only: `BuildConfig.DATA_SHARING` hides the settings screen elsewhere, and the manifest placeholder `dataSharingEnabled` leaves the provider disabled.
> **Audience:** Users and contributors.
> **Implementation:** `sharing`, `sharing/contract`, `features/settings`.
> **Navigation:** `Screen.SettingsDataSharing`; settings section `DATA_SHARING`.
> **Related:** [Feature map](feature-map.md), [Settings and preferences](settings-and-preferences.md), [Permissions](../app/permissions.md), [Privacy](../app/privacy.md).

Other apps can read OpenVitals data through a read-only Android content provider. The data is split into 11 groups. An app asks for each group on its own, and the user allows or denies each one.

## What The User Controls

- **The switch.** Settings, Share with other apps. It starts off. While it is off, the provider returns no data.
- **Each group, for each app.** A group is an Android runtime permission. Android shows its own Allow or Deny dialog when an app asks. The user changes it later in Android's settings for that app.
- **Health Connect data in the background.** An app usually asks while OpenVitals is closed. Health Connect then gives OpenVitals only its own records, unless the user allows background reads. The screen offers that grant when it is missing.

The screen also lists every app that has asked: what it may read now, when it last asked, a link to that app's Android settings, and a button to remove it from the list.

## The Groups

| Group | Contents |
|---|---|
| Activity | steps, distance, floors, elevation, wheelchair pushes, calories |
| Workouts | workouts, workout plans, speed, power, cadence, splits, the heart-rate recovery test |
| Workout routes | GPS routes. A group of its own, because a route shows where the user has been. |
| Sleep | sessions and stages, sleep score, efficiency |
| Heart | heart rate, resting heart rate, HRV, maximum heart rate, HRV status |
| Vitals | blood pressure, SpO2, breathing rate, temperatures, VO2 max, blood glucose |
| Body | weight, height, body composition, BMR, BMI, FFMI |
| Nutrition | food and nutrients, hydration, caffeine |
| Recovery | readiness, stress, Body Energy, cardio load, intensity minutes, mindfulness |
| Cycle | periods, fertility signs, predictions, the cycle journal, pill intakes. Journal notes are never shared. |
| Sexual activity | sexual activity records. A group of its own, because it is the most private. |

Medical records are not shared.

## What Is Built

- The 11 permissions, the provider, the switch, the access log and the settings screen.
- `/groups` (which groups the calling app holds, and why data may be missing) and `/metrics` (the full catalog).
- Health Connect data for every group:
  - **daily** values through the same repository reads as the PDF report, so the Room caches apply. A day is a day in the phone's time zone; a night belongs to the day it ends on.
  - **samples** straight from the records, up to 48 hours per query, mapped in `healthconnect/SharedSamplesHealthReader`. They are screen reads: paced, counted and behind the one rate-limit backoff, so a refusal makes the answer `rate_limited` at once.
  - **sessions** for workouts (recorded and planned), sleep and mindfulness, each session's summary, and a workout's route.
- Values OpenVitals works out itself (scores, splits, the cycle journal) come next.

Health Connect data needs four things, or the answer says why it is empty: sharing on, Health Connect available and not paused, and the background-read grant. Without the history grant, Health Connect serves the last 30 days and the cursor says where the data starts. A read OpenVitals has no permission for, or one that fails, makes the answer `partial`; the other rows are still right.

A query runs on one of the provider's binder threads and has 20 seconds; a slower one answers `partial` with no rows. At most four queries wait at once, over every calling app; one more answers `rate_limited`.

The URI and column contract lives in `sharing/contract/DataSharingContract.kt` and `DataSharingMetrics.kt`.
