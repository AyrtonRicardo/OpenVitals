# Share With Other Apps

> **Status:** In every build. Off until the user turns it on.
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
| Heart | heart rate, resting heart rate, HRV, maximum heart rate |
| Vitals | blood pressure, SpO2, breathing rate, temperatures, VO2 max, blood glucose |
| Body | weight, height, body composition, BMR, BMI, FFMI |
| Nutrition | food and nutrients, hydration, caffeine |
| Recovery | Body Energy, mindfulness |
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
- A recorded workout as a file: `workouts/sessions/<session_id>/fit`, the FIT file "Save as FIT" writes (`features/activity/ActivityWorkoutFitFile`). It is opened, not queried. `?route=true` adds the GPS track and needs the routes group too. A file cannot say what is missing from it, so anything short of the whole file is refused: the `FileNotFoundException`'s message starts with the status and a colon (`sharing_off: `, `background_read_missing: `, `rate_limited: `, `partial: `, or `not_found: ` when no recorded workout has the id) and names no workout.
- OpenVitals' own data, with origin `openvitals`:
  - **stored in the app:** Body Energy as last computed (days and 5-minute buckets), the cycle journal and pill intakes. These need no Health Connect access, so they arrive even when Health Connect cannot be read.
  - **worked out on request**, with the same functions the screens use (they live in the `health-calculations` library): the sleep score, efficiency, WASO and midpoint; BMI, FFMI and the BMR estimate as of each day; the maximum heart rate the load estimates use; the caffeine curve; the cycle day, phase, predicted window, averages and thermal shift as they stood on each day; a workout's moving time, maxima, recovery drop and splits.
- Not served: readiness, stress, HRV status, cardio load and intensity minutes. They cost too many Health Connect reads to compute on request. They are not in the catalog, and can join it without a new API version once the app keeps a daily record of them.

Health Connect data needs four things, or the answer says why it is missing: sharing on, Health Connect available and not paused, and the background-read grant. OpenVitals' stored data still arrives. Without the history grant, Health Connect serves the last 30 days and the cursor says where the data starts. A read OpenVitals has no permission for, or one that fails, makes the answer `partial`; the other rows are still right.

How a day's value is chosen:

| Value | Rule |
|---|---|
| Maximum heart rate | one row, for the last day of the range: the observed maximum looks a year back |
| Body measurements for BMI and FFMI | the last one on or before the day |
| Cycle values | only what was recorded by that day |
| Period flow | the day's heaviest |
| Mucus and ovulation tests | the day's last reading |

A query runs on one of the provider's binder threads and has 20 seconds; a slower one answers `partial` with no rows. At most four queries wait at once, over every calling app; one more answers `rate_limited`.

The URI and column contract lives in `sharing/contract/DataSharingContract.kt` and `DataSharingMetrics.kt`.
