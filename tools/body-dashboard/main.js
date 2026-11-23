// The page: fetches the two files from the data folder and draws them.
// The data folder is `data/` beside this page unless `?data=<path>` names another
// same-origin path; the Content-Security-Policy keeps every request on this server.
import {
  SERIES, SITES, SITE_LABELS, parseMeasurements, parsePhotos, inRange, summary, valueOn, chartPath,
} from './dashboard.js';

const params = new URLSearchParams(location.search);
const dataPath = (params.get('data') || 'data/').replace(/^\/*/, '').replace(/\/?$/, '/');
const dataUrl = (name) => new URL(dataPath + name, location.href);

const state = { days: 90, measurements: null, photos: [], selected: [] };
const $ = (id) => document.getElementById(id);
const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 });
const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else node.setAttribute(k, v);
  }
  for (const child of children) node.append(child);
  return node;
}

function svg(tag, attrs = {}) {
  const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  return node;
}

async function fetchJson(name) {
  const response = await fetch(dataUrl(name), { cache: 'no-store' });
  if (response.status === 404) return null;
  if (!response.ok) throw new Error(`${name}: HTTP ${response.status}`);
  return response.json();
}

function card(title, unit, series) {
  const visible = inRange(series, state.days, new Date());
  const s = summary(visible);
  const node = el('article', { class: 'card' }, el('h3', {}, title));
  if (!s) {
    node.append(el('p', { class: 'empty' }, 'No data in this range'));
    return node;
  }
  const value = el('p', { class: 'value' }, `${number.format(s.latest)}${unit ? ` ${unit}` : ''}`);
  node.append(value);
  if (s.change != null) {
    const sign = s.change > 0 ? '+' : s.change < 0 ? '−' : '';
    node.append(el('p', { class: 'change' }, `${sign}${number.format(Math.abs(s.change))} in range`));
  }
  const { d, min, max } = chartPath(visible, 300, 80);
  const chart = svg('svg', { viewBox: '0 0 300 80', class: 'chart', role: 'img',
    'aria-label': `${title}, ${visible.length} points from ${number.format(min)} to ${number.format(max)}` });
  chart.append(svg('path', { d, class: 'line' }));
  node.append(chart, el('p', { class: 'meta' }, `Latest ${dateFormat.format(s.latestTime)}`));
  return node;
}

function renderCards() {
  const m = state.measurements;
  const body = $('body-cards');
  const tape = $('tape-cards');
  body.replaceChildren();
  tape.replaceChildren();
  if (!m) return;
  for (const s of SERIES) if (m.series[s.key].length) body.append(card(s.label, s.unit, m.series[s.key]));
  for (const site of SITES) if (m.sites[site].length) tape.append(card(SITE_LABELS[site], 'cm', m.sites[site]));
  if (!body.children.length) body.append(el('p', { class: 'empty' }, 'No body measurements exported yet.'));
  if (!tape.children.length) tape.append(el('p', { class: 'empty' }, 'No tape measurements yet.'));
}

function photoFacts(photo) {
  const m = state.measurements;
  if (!m) return '';
  const weight = valueOn(m.series.weight, photo.date);
  const waist = valueOn(m.sites.waist, photo.date);
  return [weight && `${number.format(weight.value)} kg`, waist && `waist ${number.format(waist.value)} cm`]
    .filter(Boolean).join(' · ');
}

function photoFigure(photo, onClick) {
  const img = el('img', { src: dataUrl(photo.file).href, alt: `Progress photo, ${photo.date}`, loading: 'lazy' });
  const figure = el('figure', {}, img, el('figcaption', {}, dateFormat.format(new Date(`${photo.date}T12:00:00`)),
    el('br'), photoFacts(photo)));
  if (onClick) {
    const button = el('button', { type: 'button', class: 'photo', 'aria-pressed': String(state.selected.includes(photo)) }, figure);
    button.addEventListener('click', onClick);
    return button;
  }
  return figure;
}

function renderPhotos() {
  const grid = $('photos');
  const visible = inRange(state.photos, state.days, new Date()).slice().reverse();
  grid.replaceChildren(...visible.map((photo) => photoFigure(photo, () => toggle(photo))));
  if (!visible.length) grid.append(el('p', { class: 'empty' }, 'No progress photos in this range.'));
  const compare = $('compare');
  compare.hidden = state.selected.length !== 2;
  compare.replaceChildren(...state.selected.slice().sort((a, b) => a.time - b.time).map((p) => photoFigure(p)));
}

function toggle(photo) {
  state.selected = state.selected.includes(photo)
    ? state.selected.filter((p) => p !== photo)
    : [...state.selected, photo].slice(-2);
  renderPhotos();
}

function render() {
  renderCards();
  renderPhotos();
}

for (const button of $('ranges').querySelectorAll('button')) {
  button.addEventListener('click', () => {
    state.days = button.dataset.days === 'all' ? null : Number(button.dataset.days);
    for (const b of $('ranges').querySelectorAll('button')) b.setAttribute('aria-pressed', String(b === button));
    render();
  });
}

async function load() {
  try {
    const [measurements, photos] = await Promise.all([fetchJson('measurements.json'), fetchJson('photos.json')]);
    state.measurements = measurements ? parseMeasurements(measurements) : null;
    state.photos = photos ? parsePhotos(photos) : [];
    $('status').textContent = state.measurements
      ? `Exported ${dateFormat.format(state.measurements.exportedAt)}`
      : 'No measurements.json yet: export it from OpenVitals, Settings → Body profile.';
    render();
  } catch (error) {
    $('status').textContent = `Could not read the data folder: ${error.message}`;
  }
}

load();
