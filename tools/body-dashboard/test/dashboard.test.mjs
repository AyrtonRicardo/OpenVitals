// Run with: node --test tools/body-dashboard/test/
// The fixtures are the ones the app's BodyFolderDocumentsTest checks its output against.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  parseMeasurements, parsePhotos, bmiSeries, inRange, summary, valueOn, localDate, chartPath, SITES, SITE_LABELS,
} from '../dashboard.js';

const fixture = (name) => JSON.parse(readFileSync(new URL(`./fixtures/${name}`, import.meta.url), 'utf8'));

test('reads every series of the app export, sorted by time', () => {
  const m = parseMeasurements(fixture('measurements.json'));
  assert.deepEqual(m.series.weight.map((p) => p.value), [82.4, 81.55, 80.12]);
  assert.deepEqual(m.series.bodyFat.map((p) => p.value), [22.5, 21.8]);
  assert.equal(m.series.leanMass.length, 1);
  assert.equal(m.series.boneMass.length, 1);
  assert.equal(m.series.bodyWater.length, 1);
  assert.deepEqual(m.sites.waist.map((p) => p.value), [88, 86.5]);
  assert.deepEqual(m.sites.hips.map((p) => p.value), [100]);
  assert.deepEqual(m.sites.left_forearm.map((p) => p.value), [34]);
  assert.deepEqual(Object.keys(m.sites), SITES);
  assert.equal(m.exportedAt.toISOString(), '2026-11-21T18:00:00.000Z');
});

test('BMI uses the height measured before each weight', () => {
  const m = parseMeasurements(fixture('measurements.json'));
  assert.deepEqual(m.series.bmi.map((p) => p.value), [25.4, 25.2, 24.7]);

  const t = (iso) => new Date(iso);
  const weight = [{ time: t('2026-01-01T00:00:00Z'), value: 80 }, { time: t('2026-06-01T00:00:00Z'), value: 80 }];
  const height = [{ time: t('2026-03-01T00:00:00Z'), value: 200 }, { time: t('2026-05-01T00:00:00Z'), value: 150 }];
  // Before any height, the first one; after both, the latest.
  assert.deepEqual(bmiSeries(weight, height).map((p) => p.value), [20, 35.6]);
  assert.deepEqual(bmiSeries(weight, []), []);
});

test('reads the photo index and refuses paths outside photos/', () => {
  const photos = parsePhotos(fixture('photos.json'));
  assert.deepEqual(photos.map((p) => p.date), ['2026-11-01', '2026-11-21']);
  assert.equal(photos[0].file, 'photos/2026-11-01_073000_aaaa1111.jpg');

  const hostile = {
    format: 'openvitals-progress-photos', version: 1,
    photos: [
      { time: '2026-11-01T00:00:00Z', date: '2026-11-01', file: '../secret.jpg' },
      { time: '2026-11-01T00:00:00Z', date: '2026-11-01', file: 'https://example.com/x.jpg' },
      { time: '2026-11-01T00:00:00Z', date: '2026-11-01', file: 'photos/ok_1.jpg' },
    ],
  };
  assert.deepEqual(parsePhotos(hostile).map((p) => p.file), ['photos/ok_1.jpg']);
});

test('a file of another format or version is refused', () => {
  assert.throws(() => parseMeasurements(fixture('photos.json')), /Not an openvitals-body-measurements file/);
  assert.throws(() => parsePhotos({ format: 'openvitals-progress-photos', version: 2 }), /version 2 is not supported/);
});

test('a range keeps the last days, and the summary is latest and change within it', () => {
  const m = parseMeasurements(fixture('measurements.json'));
  const now = new Date('2026-11-21T18:00:00Z');
  const lastWeek = inRange(m.series.weight, 7, now);
  assert.deepEqual(lastWeek.map((p) => p.value), [81.55, 80.12]);
  assert.equal(inRange(m.series.weight, null, now).length, 3);
  assert.deepEqual(summary(lastWeek), { latest: 80.12, latestTime: new Date('2026-11-20T07:00:00Z'), change: -1.43 });
  assert.equal(summary([]), null);
  assert.equal(summary(m.series.leanMass).change, null);
});

test('the value on a photo day is that day or the last before it', () => {
  const m = parseMeasurements(fixture('measurements.json'));
  assert.equal(valueOn(m.series.weight, '2026-11-15', 'UTC').value, 81.55);
  assert.equal(valueOn(m.series.weight, '2026-11-18', 'UTC').value, 81.55);
  assert.equal(valueOn(m.series.weight, '2026-10-01', 'UTC'), null);
  assert.equal(localDate(new Date('2026-11-20T23:30:00Z'), 'Europe/Berlin'), '2026-11-21');
});

test('a chart path spans the box, and a flat or single series still draws', () => {
  const t = (iso) => new Date(iso);
  const rising = [{ time: t('2026-01-01T00:00:00Z'), value: 1 }, { time: t('2026-01-02T00:00:00Z'), value: 3 }];
  assert.deepEqual(chartPath(rising, 100, 50, 0), { d: 'M0.0,50.0 L100.0,0.0', min: 1, max: 3 });
  assert.equal(chartPath([{ time: t('2026-01-01T00:00:00Z'), value: 5 }], 100, 50, 0).d, 'M50.0,25.0');
  assert.deepEqual(chartPath([], 100, 50), { d: '', min: null, max: null });
});

test('a file exported before the sides existed still charts its upper arm and thigh', () => {
  const old = fixture('measurements.json');
  old.circumferences = [
    { time: '2026-10-01T08:00:00Z', site: 'upper_arm', cm: 33.5 },
    { time: '2026-10-01T08:01:00Z', site: 'thigh', cm: 57.0 },
  ];
  const m = parseMeasurements(old);
  assert.deepEqual(m.sites.upper_arm.map((p) => p.value), [33.5]);
  assert.deepEqual(m.sites.thigh.map((p) => p.value), [57]);
  assert.equal(SITE_LABELS.upper_arm, 'Upper arm');
  assert.equal(SITE_LABELS.thigh, 'Thigh');
});

test('every part the app exports has a label, sides spelled out', () => {
  const parts = ['neck', 'shoulders', 'chest', 'waist', 'hips', 'right_upper_arm', 'left_upper_arm',
    'right_forearm', 'left_forearm', 'right_thigh', 'left_thigh', 'right_calf', 'left_calf'];
  assert.deepEqual(SITES.slice(0, parts.length), parts);
  assert.equal(SITE_LABELS.right_calf, 'Right calf');
  assert.equal(SITE_LABELS.shoulders, 'Shoulders');
  for (const site of SITES) assert.ok(SITE_LABELS[site], site);
});
