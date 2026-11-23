# Tape Measurements And Progress Photos

> **Status:** Current implemented behavior.
> **Audience:** Users and contributors.
> **Implementation:** `features/circumference`, `features/manualentry/circumference`, `features/progressphotos`, `features/bodyfolder`, `data/local/circumference`, `data/local/photos`.
> **Navigation:** Dashboard, Circumferences; Add entry, Circumference or Progress photos; Settings, Body profile, Body measurements folder.
> **Related:** [Feature map](feature-map.md), [Body metrics](body-metrics.md), [Manual entry of metrics](manual-entry-metrics.md), [Settings and preferences](settings-and-preferences.md).

Health Connect has no record type for a tape measurement or a photo, so both stay with OpenVitals: the measurements and the photo list in its database, the photos' full images in a folder you pick. Weight and body composition stay in Health Connect, where a scale app such as Samsung Health writes them.

## Tape Measurements

The Circumferences tile on the dashboard shows the latest waist. Its screen opens on a body figure facing you, so your right side is on the screen's left: beside each part — neck, shoulders, chest, waist, hips, and the right and left upper arm, forearm, thigh and calf — a tile with its latest value and its change, joined to a tape band around that part. Shoulders are measured around both shoulders at their widest, like the chest. Arms and legs are measured on each side because people are rarely symmetrical; upper-arm and thigh values saved before the sides existed became the right side. Without a date the change is from the part's first measurement; pick a date to compare with that day instead (a part not measured that day uses its last value before it, and the tile shows which day). Arrows show smaller (▼) or larger (▲) without calling either good. Tap a part to measure it: the entry form opens on that part with the keyboard up, and back on the screen its history shows below over a day, week, month or year, with the latest value, the change and the average; swipe a measurement to delete it.

Add entry, Circumference opens the same figure for a measuring session: pick the date, tap each part you measured and type its value (the tap puts the cursor in its box), in centimetres or inches following the height unit, then save them all at once. A part that already has a value on that date is flagged while you type, naming every value it holds that day, and saving asks before replacing them.

## Progress Photos

Add entry, Progress photos, or the link on the Circumferences screen, opens the photos over a period, grouped by day. **Add photo** takes one with the camera app or picks one from the gallery. In the Day view of an earlier day, the photo is linked to that day; otherwise to now.

The phone keeps a thumbnail of every photo. The full image is written again as a JPEG, which drops the camera's metadata, location included, and is saved to the body measurements folder. Until it is there, it waits on the phone with a mark on its thumbnail; a folder on a network share that is offline is retried later, and **Save photos now** retries at once. Deleting a photo removes it from the phone and, when it was saved, from the folder.

## Body Measurements Folder

Settings, Body profile, Body measurements folder picks the folder with Android's folder picker. OpenVitals has no network permission, so a network share works through a document provider app that offers it in the picker: CIFS Documents Provider for an SMB share, RSAF for WebDAV or Nextcloud. Google Drive cannot be picked as a folder.

**Export measurements** writes `measurements.json` there: every weight, height, body fat, lean mass, bone mass and body water record Health Connect lets OpenVitals read, and every tape measurement. A type without a read permission is left empty. The folder then holds:

| File | Holds |
|---|---|
| `measurements.json` | the body records and tape measurements, written by **Export measurements** |
| `photos.json` | the saved photos, each with its day, rewritten after each save or delete |
| `photos/` | the full images |
