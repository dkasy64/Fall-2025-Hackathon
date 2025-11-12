import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
// ical.js CommonJS interop can differ under ESM `type: module`. We normalize the import
// so that `Component`, `Event`, `Time`, and `parse` are always reachable. In some bundling
// situations `import * as ICAL` yields an object whose actual exports live under `default`.
import * as ICALRaw from 'ical.js';
const ICAL = (ICALRaw.Component ? ICALRaw : (ICALRaw.default ? ICALRaw.default : ICALRaw));
import dayjs from 'dayjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

function repoRoot() {
  return path.resolve(__dirname, '..');
}

function resolveCalendarFile() {
  const primary = path.resolve(repoRoot(), 'src/main/resources/sample-calendar.ics');
  if (fs.existsSync(primary)) return primary;
  const dataDir = path.resolve(repoRoot(), 'node-app/data');
  if (!fs.existsSync(dataDir)) fs.mkdirSync(dataDir, { recursive: true });
  return path.join(dataDir, 'sample-calendar.ics');
}

function loadOrCreate() {
  const file = resolveCalendarFile();
  if (fs.existsSync(file)) {
    const txt = fs.readFileSync(file, 'utf8');
    try {
      const jcal = ICAL.parse(txt);
      return new ICAL.Component(jcal);
    } catch (e) {
      // If parsing fails, we'll create a fresh calendar below.
    }
  }
  // Create a minimal VCALENDAR structure
  const comp = new ICAL.Component(['vcalendar', [], []]);
  comp.addPropertyWithValue('prodid', '-//AI Calendar//Gemini Tool//EN');
  comp.addPropertyWithValue('version', '2.0');
  comp.addPropertyWithValue('calscale', 'GREGORIAN');
  return comp;
}

function save(comp) {
  const file = resolveCalendarFile();
  const dir = path.dirname(file);
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(file, comp.toString(), 'utf8');
}

function readCalendarContent() {
  const file = resolveCalendarFile();
  try { return fs.readFileSync(file, 'utf8'); } catch { return ''; }
}

function replaceCalendarContent(icsText) {
  if (!icsText) icsText = '';
  try {
    ICAL.parse(icsText); // validate parseable
  } catch (e) {
    throw new Error('Uploaded ICS is invalid: ' + e.message);
  }
  const file = resolveCalendarFile();
  const dir = path.dirname(file);
  if (!fs.existsExists) { /* placeholder to satisfy diff context; will remove next */ }
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(file, icsText, 'utf8');
}

function jsDateFrom(dateStr, timeStr) {
  return dayjs(`${dateStr} ${timeStr}`, 'YYYY-MM-DD HH:mm').toDate();
}

function timeToICAL(date) {
  const t = ICAL.Time.fromJSDate(date, false); // floating local time
  t.isDate = false;
  return t;
}

function findMatchingEvent(comp, title, dateStr, timeStr) {
  const js = jsDateFrom(dateStr, timeStr);
  const dtStr = dayjs(js).format('YYYY-MM-DD HH:mm');
  const vevents = comp.getAllSubcomponents('vevent');
  for (const ve of vevents) {
    const ev = new ICAL.Event(ve);
    const sum = ev.summary || '';
    const evStart = ev.startDate && ev.startDate.toJSDate ? ev.startDate.toJSDate() : null;
    if (!evStart) continue;
    const sStr = dayjs(evStart).format('YYYY-MM-DD HH:mm');
    if (sum.toLowerCase() === (title || '').toLowerCase() && sStr === dtStr) {
      return ev;
    }
  }
  return null;
}

async function createCalendarEvent(date, time, recurring, title = 'AI-Created Event', durationMinutes = 60) {
  const comp = loadOrCreate();
  const startJs = jsDateFrom(date, time);
  const endJs = new Date(startJs.getTime() + Math.max(1, durationMinutes) * 60 * 1000);

  const ve = new ICAL.Component('vevent');
  const ev = new ICAL.Event(ve);
  ev.summary = title && title.trim() ? title : 'AI-Created Event';
  ev.startDate = timeToICAL(startJs);
  ev.endDate = timeToICAL(endJs);
  ve.addPropertyWithValue('uid', cryptoRandomUid());

  switch ((recurring || 'non-recurring').toLowerCase()) {
    case 'daily': ve.addPropertyWithValue('rrule', 'FREQ=DAILY'); break;
    case 'weekly': ve.addPropertyWithValue('rrule', 'FREQ=WEEKLY'); break;
    case 'monthly': ve.addPropertyWithValue('rrule', 'FREQ=MONTHLY'); break;
    case 'yearly': ve.addPropertyWithValue('rrule', 'FREQ=YEARLY'); break;
    default: // none
  }

  comp.addSubcomponent(ve);
  save(comp);
}

async function updateEventDuration(title, date, time, newDurationMinutes) {
  const comp = loadOrCreate();
  const ev = findMatchingEvent(comp, title, date, time);
  if (!ev) return false;
  const startJs = ev.startDate.toJSDate();
  const endJs = new Date(startJs.getTime() + Math.max(1, newDurationMinutes || 60) * 60 * 1000);
  ev.endDate = timeToICAL(endJs);
  save(comp);
  return true;
}

async function updateEventByTitleAndStart(title, date, time, newDate, newTime) {
  const comp = loadOrCreate();
  const ev = findMatchingEvent(comp, title, date, time);
  if (!ev) return false;
  const startJs = ev.startDate.toJSDate();
  const endJs = ev.endDate && ev.endDate.toJSDate ? ev.endDate.toJSDate() : new Date(startJs.getTime() + 60 * 60 * 1000);
  const duration = Math.max(0, endJs.getTime() - startJs.getTime());
  const newStart = jsDateFrom(newDate, newTime);
  const newEnd = new Date(newStart.getTime() + duration);
  ev.startDate = timeToICAL(newStart);
  ev.endDate = timeToICAL(newEnd);
  save(comp);
  return true;
}

async function deleteEventByTitleAndStart(title, date, time) {
  const comp = loadOrCreate();
  const vevents = comp.getAllSubcomponents('vevent');
  const js = jsDateFrom(date, time);
  const dtStr = dayjs(js).format('YYYY-MM-DD HH:mm');
  for (const ve of vevents) {
    const ev = new ICAL.Event(ve);
    const sum = ev.summary || '';
    const evStart = ev.startDate && ev.startDate.toJSDate ? ev.startDate.toJSDate() : null;
    if (!evStart) continue;
    const sStr = dayjs(evStart).format('YYYY-MM-DD HH:mm');
    if (sum.toLowerCase() === (title || '').toLowerCase() && sStr === dtStr) {
      comp.removeSubcomponent(ve);
      save(comp);
      return true;
    }
  }
  return false;
}

function toIntervalsSameDay(comp, day) {
  const vevents = comp.getAllSubcomponents('vevent');
  const out = [];
  for (const ve of vevents) {
    const ev = new ICAL.Event(ve);
    const ds = ev.startDate; const de = ev.endDate;
    if (!ds || !de) continue;
    if (ev.isRecurring()) continue;
    const allDay = ds.isDate || de.isDate;
    if (allDay) continue;
    const s = ds.toJSDate();
    if (dayjs(s).format('YYYY-MM-DD') === day) {
      out.push([ds.toJSDate(), de.toJSDate(), ev, ve]);
    }
  }
  out.sort((a, b) => a[0] - b[0]);
  return out;
}

function overlaps(s1, e1, s2, e2) { return s1 < e2 && s2 < e1; }

async function updateEventWithConflictResolution(title, date, time, newDate, newTime) {
  const comp = loadOrCreate();
  const ev = findMatchingEvent(comp, title, date, time);
  if (!ev) return false;
  const durationMs = Math.max(0, (ev.endDate?.toJSDate() - ev.startDate?.toJSDate()) || 60 * 60 * 1000);
  let targetStart = jsDateFrom(newDate, newTime);
  const day = dayjs(targetStart).format('YYYY-MM-DD');
  const sameDay = toIntervalsSameDay(comp, day);
  for (let attempts = 0; attempts < 48; attempts++) {
    const targetEnd = new Date(targetStart.getTime() + durationMs);
    let conflict = false;
    for (const [s, e, other, _ve] of sameDay) {
      if (other === ev) continue;
      if (overlaps(targetStart, targetEnd, s, e)) { conflict = true; break; }
    }
    if (!conflict) {
      ev.startDate = timeToICAL(targetStart);
      ev.endDate = timeToICAL(targetEnd);
      save(comp);
      return true;
    }
    targetStart = new Date(targetStart.getTime() + 30 * 60 * 1000);
    if (dayjs(targetStart).format('YYYY-MM-DD') !== day) break;
  }
  return false;
}

async function autoSpaceEvents(minGapMinutes = 60) {
  const comp = loadOrCreate();
  const content = readCalendarContent();
  // find all unique days
  const vevents = comp.getAllSubcomponents('vevent');
  const days = new Set();
  for (const ve of vevents) {
    const ev = new ICAL.Event(ve);
    if (!ev.startDate) continue;
    days.add(dayjs(ev.startDate.toJSDate()).format('YYYY-MM-DD'));
  }
  const today = dayjs().format('YYYY-MM-DD');
  let moved = 0;
  for (const d of days) {
    if (d < today) continue; // don't alter past
    const intervals = toIntervalsSameDay(comp, d);
    let prevEndWithGap = null;
    for (const [s, e, ev, _ve] of intervals) {
      const ds = ev.startDate; const de = ev.endDate;
      if (ds.isDate || de.isDate) continue; // all day
      if (ev.isRecurring()) continue;
      if (!prevEndWithGap) { prevEndWithGap = new Date(e.getTime() + minGapMinutes * 60 * 1000); continue; }
      if (s < prevEndWithGap) {
        const newStart = new Date(prevEndWithGap.getTime());
        if (dayjs(newStart).format('YYYY-MM-DD') !== d) { prevEndWithGap = new Date(e.getTime() + minGapMinutes * 60 * 1000); continue; }
        const duration = e.getTime() - s.getTime();
        const newEnd = new Date(newStart.getTime() + duration);
        ev.startDate = timeToICAL(newStart);
        ev.endDate = timeToICAL(newEnd);
        moved++;
        prevEndWithGap = new Date(newEnd.getTime() + minGapMinutes * 60 * 1000);
      } else {
        prevEndWithGap = new Date(e.getTime() + minGapMinutes * 60 * 1000);
      }
    }
  }
  if (moved > 0) save(comp);
  return moved;
}

async function rebalanceWeek() {
  const comp = loadOrCreate();
  const today = dayjs();
  const weekStart = today.startOf('week').add(1, 'day'); // Monday
  const weekEnd = weekStart.add(6, 'day'); // Sunday
  const days = [];
  for (let i = 0; i < 7; i++) days.push(weekStart.add(i, 'day').format('YYYY-MM-DD'));

  const byDay = new Map(days.map(d => [d, []]));
  const vevents = comp.getAllSubcomponents('vevent');
  for (const ve of vevents) {
    const ev = new ICAL.Event(ve);
    const ds = ev.startDate; const de = ev.endDate;
    if (!ds || !de) continue;
    if (ds.isDate || de.isDate) continue; // skip all-day
    if (ev.isRecurring()) continue;
    const d = dayjs(ds.toJSDate()).format('YYYY-MM-DD');
    if (days.includes(d)) byDay.get(d).push(ev);
  }

  function freeSlotOn(day, durationMs) {
    if (day < today.format('YYYY-MM-DD')) return null;
    const intervals = toIntervalsSameDay(comp, day);
    let cursor = dayjs(day + ' 10:00');
    for (let attempts = 0; attempts < 48; attempts++) {
      const end = cursor.add(durationMs, 'millisecond');
      if (end.format('YYYY-MM-DD') !== day) return null;
      const conflict = intervals.some(([s, e]) => overlaps(cursor.toDate(), end.toDate(), s, e));
      if (!conflict) return [cursor.toDate(), end.toDate()];
      cursor = cursor.add(30, 'minute');
    }
    return null;
  }

  let moved = 0;
  for (let iter = 0; iter < 30; iter++) {
    days.sort((a, b) => byDay.get(a).length - byDay.get(b).length);
    let light = days.find(d => d >= today.format('YYYY-MM-DD'));
    if (!light) break;
    const heavy = days[days.length - 1];
    const lightCount = byDay.get(light).length;
    const heavyCount = byDay.get(heavy).length;
    if (heavyCount - lightCount <= 0) break;
    const heavyEvents = byDay.get(heavy);
    if (!heavyEvents.length) break;
    heavyEvents.sort((a, b) => a.startDate.toJSDate() - b.startDate.toJSDate());
    const ev = heavyEvents[heavyEvents.length - 1];
    const s = ev.startDate.toJSDate();
    const e = ev.endDate.toJSDate();
    const duration = Math.max(0, e - s);
    let slot = freeSlotOn(light, duration);
    if (!slot) {
      for (let i = 1; i < days.length; i++) {
        const d = days[i];
        if (d < today.format('YYYY-MM-DD')) continue;
        slot = freeSlotOn(d, duration);
        if (slot) { light = d; break; }
      }
      if (!slot) break;
    }
    ev.startDate = timeToICAL(slot[0]);
    ev.endDate = timeToICAL(slot[1]);
    heavyEvents.pop();
    byDay.get(light).push(ev);
    moved++;
  }

  if (moved > 0) save(comp);
  return moved;
}

function summarizeCalendar() {
  try {
    const comp = loadOrCreate();
    const vevents = comp.getAllSubcomponents('vevent');
    const list = [];
    for (const ve of vevents) {
      const ev = new ICAL.Event(ve);
      list.push(ev);
    }
    list.sort((a, b) => (a.startDate?.toJSDate() || 0) - (b.startDate?.toJSDate() || 0));

    let sb = 'Calendar summary:\n';
    let currentDay = null;
    let count = 0;
    for (const ev of list) {
      const ds = ev.startDate; const de = ev.endDate;
      const sDate = ds ? ds.toJSDate() : null;
      const eDate = de ? de.toJSDate() : null;
      const day = sDate ? dayjs(sDate).format('YYYY-MM-DD') : '(unknown day)';
      if (day !== currentDay) { currentDay = day; sb += `\n${day}\n`; }
      const title = ev.summary || '(untitled)';
      const allDay = (ds && ds.isDate) || (de && de.isDate);
      if (allDay) {
        sb += `  (all-day) ${title}\n`;
      } else {
        const sTime = sDate ? dayjs(sDate).format('HH:mm') : '??';
        const eTime = eDate ? dayjs(eDate).format('HH:mm') : '??';
        sb += `  ${sTime}-${eTime} ${title}\n`;
      }
      count++;
    }
    sb += `\nTotal events: ${count}\n`;
    return sb;
  } catch (e) {
    return 'Failed to summarize calendar: ' + e.message;
  }
}

function cryptoRandomUid() {
  // Simple unique uid; not cryptographically strong but fine for demo
  return 'uid-' + Math.random().toString(36).slice(2) + '-' + Date.now();
}

export const CalendarTool = {
  getCalendarFile: resolveCalendarFile,
  readCalendarContent,
  replaceCalendarContent,
  createCalendarEvent,
  updateEventDuration,
  updateEventByTitleAndStart,
  deleteEventByTitleAndStart,
  updateEventWithConflictResolution,
  autoSpaceEvents,
  rebalanceWeek,
  summarizeCalendar,
  listEvents: () => {
    const comp = loadOrCreate();
    const vevents = comp.getAllSubcomponents('vevent') || [];
    const out = [];
    for (const ve of vevents) {
      try {
        const ev = new ICAL.Event(ve);
        const ds = ev.startDate; const de = ev.endDate;
        if (!ds || !de) continue;
        if (ds.isDate || de.isDate) continue; // skip all-day for now
        const s = ds.toJSDate();
        const e = de.toJSDate();
        out.push({
          title: ev.summary || '(untitled)',
          date: dayjs(s).format('YYYY-MM-DD'),
          start: dayjs(s).format('HH:mm'),
          end: dayjs(e).format('HH:mm'),
          durationMinutes: Math.max(0, Math.round((e - s) / 60000))
        });
      } catch (_) { /* ignore broken event */ }
    }
    // sort by start
    out.sort((a, b) => (a.date + ' ' + a.start).localeCompare(b.date + ' ' + b.start));
    return out;
  }
};
