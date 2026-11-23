// Pure logic for the body dashboard: no DOM, so `node --test` runs it against the
// same fixtures the app's BodyFolderDocumentsTest writes against.

export const SUPPORTED_VERSION = 1;

export const SERIES = [
  { key: 'weight', field: 'kg', label: 'Weight', unit: 'kg' },
  { key: 'bmi', field: 'value', label: 'BMI', unit: '' },
  { key: 'bodyFat', field: 'percent', label: 'Body fat', unit: '%' },
  { key: 'leanMass', field: 'kg', label: 'Lean mass', unit: 'kg' },
  { key: 'boneMass', field: 'kg', label: 'Bone mass', unit: 'kg' },
  { key: 'bodyWater', field: 'kg', label: 'Body water', unit: 'kg' },
];

// The app's parts in its order, then the two unsided parts older exports carry
// (a card is drawn only for a part that has values).
export const SITES = [
  'neck', 'shoulders', 'chest', 'waist', 'hips',
  'right_upper_arm', 'left_upper_arm', 'right_forearm', 'left_forearm',
  'right_thigh', 'left_thigh', 'right_calf', 'left_calf',
  'upper_arm', 'thigh',
];

export const SITE_LABELS = {
  neck: 'Neck', shoulders: 'Shoulders', chest: 'Chest', waist: 'Waist', hips: 'Hips',
  right_upper_arm: 'Right upper arm', left_upper_arm: 'Left upper arm',
  right_forearm: 'Right forearm', left_forearm: 'Left forearm',
  right_thigh: 'Right thigh', left_thigh: 'Left thigh',
  right_calf: 'Right calf', left_calf: 'Left calf',
  upper_arm: 'Upper arm', thigh: 'Thigh',
};

function checkHeader(doc, format) {
  if (!doc || doc.format !== format) throw new Error(`Not an ${format} file`);
  if (doc.version !== SUPPORTED_VERSION) {
    throw new Error(`${format} version ${doc.version} is not supported (expected ${SUPPORTED_VERSION})`);
  }
}

const byTime = (a, b) => a.time - b.time;

function points(entries, field) {
  return (entries || [])
    .map((e) => ({ time: new Date(e.time), value: Number(e[field]) }))
    .filter((p) => !Number.isNaN(p.time.getTime()) && Number.isFinite(p.value))
    .sort(byTime);
}

/** Turns measurements.json into one sorted series per metric and per tape site. */
export function parseMeasurements(doc) {
  checkHeader(doc, 'openvitals-body-measurements');
  const series = {};
  for (const s of SERIES) if (s.key !== 'bmi') series[s.key] = points(doc[s.key], s.field);
  const height = points(doc.height, 'cm');
  series.bmi = bmiSeries(series.weight, height);
  const sites = {};
  for (const site of SITES) {
    sites[site] = points((doc.circumferences || []).filter((c) => c.site === site), 'cm');
  }
  return { exportedAt: new Date(doc.exportedAt), series, height, sites };
}

/** Turns photos.json into photos sorted oldest first, each with its local day and file path. */
export function parsePhotos(doc) {
  checkHeader(doc, 'openvitals-progress-photos');
  return (doc.photos || [])
    .map((p) => ({ time: new Date(p.time), date: p.date, file: p.file }))
    .filter((p) => typeof p.file === 'string' && /^photos\/[\w.-]+\.jpg$/.test(p.file))
    .sort(byTime);
}

/**
 * BMI for each weight, with the latest height measured at or before it (or the first height
 * when none came before). The app shows the same: weight in kg over height in metres squared.
 */
export function bmiSeries(weight, height) {
  if (!height.length) return [];
  return weight.map((w) => {
    let h = height[0];
    for (const candidate of height) if (candidate.time <= w.time) h = candidate;
    const metres = h.value / 100;
    return { time: w.time, value: Math.round((w.value / (metres * metres)) * 10) / 10 };
  });
}

/** The points within the last [days] days of [now]; all of them when days is null. */
export function inRange(series, days, now) {
  if (days == null) return series;
  const start = now.getTime() - days * 24 * 60 * 60 * 1000;
  return series.filter((p) => p.time.getTime() >= start);
}

/** Latest value and its change from the first value of the range; null for an empty range. */
export function summary(series) {
  if (!series.length) return null;
  const first = series[0];
  const latest = series[series.length - 1];
  return {
    latest: latest.value,
    latestTime: latest.time,
    change: series.length > 1 ? Math.round((latest.value - first.value) * 100) / 100 : null,
  };
}

/** The value measured on [date] (a local yyyy-mm-dd), else the last one before it; null when none. */
export function valueOn(series, date, timeZone) {
  let found = null;
  for (const p of series) {
    if (localDate(p.time, timeZone) <= date) found = p;
    else break;
  }
  return found;
}

/** A Date's local day as yyyy-mm-dd in [timeZone] (the browser's when undefined). */
export function localDate(date, timeZone) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone, year: 'numeric', month: '2-digit', day: '2-digit',
  }).formatToParts(date);
  const get = (type) => parts.find((p) => p.type === type).value;
  return `${get('year')}-${get('month')}-${get('day')}`;
}

/** Plots a series into a width × height box: SVG path data plus the value range it spans. */
export function chartPath(series, width, height, padding = 4) {
  if (!series.length) return { d: '', min: null, max: null };
  const times = series.map((p) => p.time.getTime());
  const values = series.map((p) => p.value);
  const t0 = Math.min(...times);
  const t1 = Math.max(...times);
  let min = Math.min(...values);
  let max = Math.max(...values);
  if (min === max) { min -= 1; max += 1; }
  const x = (t) => padding + (t1 === t0 ? (width - 2 * padding) / 2 : ((t - t0) / (t1 - t0)) * (width - 2 * padding));
  const y = (v) => padding + (1 - (v - min) / (max - min)) * (height - 2 * padding);
  const d = series
    .map((p, i) => `${i ? 'L' : 'M'}${x(p.time.getTime()).toFixed(1)},${y(p.value).toFixed(1)}`)
    .join(' ');
  return { d, min, max };
}
