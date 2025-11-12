import { GoogleGenerativeAI } from '@google/generative-ai';
import { CalendarTool } from './calendarTool.mjs';
import dayjs from 'dayjs';

function sanitizeJson(raw) {
  let s = (raw || '').trim();
  if (s.startsWith('```')) {
    const i = s.indexOf('\n');
    if (i !== -1) s = s.slice(i + 1);
    if (s.endsWith('```')) s = s.slice(0, -3);
  }
  return s.trim();
}

export class CalendarAssistantService {
  #client;
  #convo = [];
  #pendingChoices = [];

  constructor(apiKey = process.env.GEMINI_API_KEY || '') {
    this.#client = new GoogleGenerativeAI(apiKey);
  }

  async handlePrompt(userRequest, applyChanges = true) {
    // FAST PATH: user is selecting from previously suggested time slots (e.g., "monday", "option 2")
    const rawReq = (userRequest || '').trim();
    const reqLower = rawReq.toLowerCase();
    if (this.#pendingChoices.length) {
      const dayNames = ['monday','tuesday','wednesday','thursday','friday','saturday','sunday'];
      const cleaned = reqLower.replace(/[.!?,]/g,'');
      let chosen = null;
      // Option index selection: "option 2", "second", "2", "choice 1"
      const idxMatch = cleaned.match(/(?:option|choice)\s*(\d+)/) || cleaned.match(/^(\d+)$/);
      if (idxMatch) {
        const idx = parseInt(idxMatch[1],10); // 1-based
        if (idx >=1 && idx <= this.#pendingChoices.length) chosen = this.#pendingChoices[idx-1];
      }
      // Ordinals words (first, second, third)
      if (!chosen) {
        const ordinalMap = { first:1, second:2, third:3, fourth:4, fifth:5 };
        for (const w of Object.keys(ordinalMap)) {
          if (cleaned.includes(w)) {
            const oi = ordinalMap[w]-1;
            if (oi < this.#pendingChoices.length) { chosen = this.#pendingChoices[oi]; break; }
          }
        }
      }
      // Day-of-week only selection (e.g., "monday")
      if (!chosen && dayNames.includes(cleaned)) {
        const matches = this.#pendingChoices.filter(c => dayjs(c.date).format('dddd').toLowerCase() === cleaned);
        if (matches.length) {
          // Prefer earliest time among matches
            chosen = matches.sort((a,b) => (a.date+a.time).localeCompare(b.date+b.time))[0];
        }
      }
      if (chosen) {
        // Construct a direct plan and apply without calling the model
        let applied = 0;
        if (applyChanges) {
          try {
            await CalendarTool.createCalendarEvent(chosen.date, chosen.time, 'non-recurring', chosen.title, chosen.durationMinutes || 60);
            applied = 1;
          } catch (_) {}
        }
        // Clear choices after selection
        this.#pendingChoices = [];
        this.#convo.push('User: ' + userRequest);
        this.#convo.push('Assistant: applied ' + applied + ' action(s) via fast selection');
        this.#trimConvo();
        return {
          responseText: '',
          plan: { actions: [ { type:'create_event', title: chosen.title, date: chosen.date, time: chosen.time, durationMinutes: chosen.durationMinutes || 60, recurring:'non-recurring' } ], suggestions: [] },
          applied,
          summaryAfter: undefined,
          suggestions: ''
        };
      }
    }
    const today = dayjs().format('YYYY-MM-DD');
    const weekStart = dayjs().startOf('week').add(1, 'day').format('YYYY-MM-DD');
    const weekEnd = dayjs(weekStart).add(6, 'day').format('YYYY-MM-DD');
    const existingCalendar = CalendarTool.readCalendarContent();

    let ctx = '';
    if (this.#convo.length) {
      ctx += 'Conversation context (most recent first):\n';
      for (let i = this.#convo.length - 1, c = 0; i >= 0 && c < 6; i--, c++) ctx += this.#convo[i] + '\n';
    }

    const instruction = [
      "You are a calendar assistant. Convert the user's request into JSON only.",
      `Today's date: ${today}. Current week (ISO Monday-Sunday) range: ${weekStart} to ${weekEnd}.`,
      'Existing calendar ICS content is provided below. Use it to avoid duplicates and allow updates.',
      "Use the conversation context below to resolve pronouns and confirmations like 'yes', 'do that', 'move it', 'the previous one'.",
      "If prior suggestions proposed a concrete time slot and the user confirms (e.g., 'yeah do that'), convert that suggestion into a concrete create_event action with a meaningful title.",
      "If user says 'this week', choose a date within that range not in the past relative to today (prefer first available weekday).",
      "Never schedule events before the current moment unless the user explicitly asks to backdate (keywords: 'past', 'backdate', 'retroactive', 'yesterday', 'last week'). If a requested time is earlier than now, shift to the next reasonable future slot on the same day or the next day.",
      'Schema strictly:',
      '{',
      '  "actions": [',
      '    { "type": "create_event", "title": "...", "date": "yyyy-MM-dd", "time": "HH:mm", "durationMinutes": 60, "recurring": "non-recurring|daily|weekly|monthly|yearly" },',
      '    { "type": "update_event", "title": "...", "date": "oldDate", "time": "oldTime", "newDate": "yyyy-MM-dd", "newTime": "HH:mm" },',
      '    { "type": "resize_event", "title": "...", "date": "yyyy-MM-dd", "time": "HH:mm", "newDurationMinutes": 240 },',
      '    { "type": "delete_event", "title": "...", "date": "yyyy-MM-dd", "time": "HH:mm" },',
      '    { "type": "auto_space", "minGapMinutes": 60 },',
      '    { "type": "bulk_update", "moves": [ { "title": "...", "date": "yyyy-MM-dd", "time": "HH:mm", "newDate": "yyyy-MM-dd", "newTime": "HH:mm" } ] }',
      '    { "type": "rebalance_week" },',
      '    { "type": "ask_clarification", "question": "..." }',
      '    { "type": "respond", "message": "...", "includeSummary": false }',
      '  ],',
      '  "suggestions": [ { "note": "Human readable optional suggestion..." } ]',
      '}',
      'Rules:',
      "- Use title fields meaningfully (e.g., 'Dentist Appointment').",
      '- For unspecified time choose 10:00 local time.',
      '- make sure when scheduling events, give more priority to later time slots in the day and to days with less stuff scheduled',
      '- Avoid creating duplicates (same title + start). Prefer update if user implies reschedule.',
      "- When the user asks to change how long an event lasts, use resize_event with newDurationMinutes (e.g., 240 for 4 hours).",
      '- For new events with specified duration, set durationMinutes on create_event (defaults to 60 if omitted).',
      "- If the user asks to move multiple events, prefer a single 'bulk_update' action with a 'moves' array. Each move may change both date and time (cross-day moves allowed).",
      "- If the user says phrases like 'free up <date>' or 'clear <date>' prefer rescheduling events off that date using bulk_update moves to future days instead of delete_event unless user explicitly says 'delete' or 'remove'.",
      "- If the user's instruction is ambiguous (missing which event or target), use ask_clarification with a question instead of guessing.",
      "- For direct questions (e.g., 'what does my week look like?', 'summarize', 'what's on <date>?' ), include a 'respond' action with a friendly concise natural-language answer and set includeSummary=true if a full calendar summary should follow.",
      "- For daily recurring bedtime requests (e.g., 'add a daily bed time of 8pm', 'add bedtime at 8pm every day'), create a create_event with title 'Bed Time', time '20:00', date = today if 20:00 is in the future else tomorrow, recurring='daily', durationMinutes=60.",
      "- Recognize synonyms: daily|every day|each day|nightly; bedtime|bed time|bed-time.",
      "- If the user asks to spread events across the week, use 'rebalance_week'.",
      "- When the user confirms a prior suggestion (e.g., 'yeah do that'), turn that suggestion into concrete actions (e.g., create_event).",
      "- Do NOT include a calendar summary or list events inside respond.message unless includeSummary=true. If includeSummary=false, respond.message should be short (<= 2 sentences) and may ask for missing details or offer next steps, NOT enumerate events.",
      "- Favor ask_clarification over guessing when user intent around timing, duration, or number of days (e.g. 'I need a few recovery days') is unclear. Example: {\"type\":\"ask_clarification\",\"question\":\"How many recovery days would you like after the surgery (e.g. 2, 3, 5)?\"}.",
      "- If the user is in an ongoing scheduling discussion (responding to earlier suggestions) continue the conversation with ask_clarification or respond (no summary) until they explicitly request a 'summary' or scheduling action.",
      "- Only produce includeSummary=true when user uses words like 'summary', 'summarize', 'what does my week look like', or explicitly asks for an overview.",
      "- NEVER invent medical advice; if the user asks health-related questions, respond with a clarification question or neutral guidance and suggest consulting a professional (still in JSON via respond or ask_clarification).",
      '- Return JSON only. No markdown, no prose.',
      '- If no actions, still return {"actions":[],"suggestions":[]}.',
    ].join('\n');

    let allowPast = false;
    if (userRequest) {
      const ur = userRequest.toLowerCase();
      const pastKeys = [
        ' backdate', 'retroactive', 'in the past', 'past', 'yesterday', 'last week',
        'last monday', 'last tuesday', 'last wednesday', 'last thursday', 'last friday',
        'last saturday', 'last sunday'
      ];
      allowPast = pastKeys.some(k => ur.includes(k));
    }

    const prompt = `${instruction}\n\nExisting calendar ICS:\n${existingCalendar}\n\n${ctx}User request:\n${userRequest}`;

    const model = this.#client.getGenerativeModel({ model: 'gemini-2.5-flash' });
    const resp = await model.generateContent(prompt);
    const raw = (await resp.response.text()) || '';
    const json = sanitizeJson(raw);

    let plan;
    try { plan = JSON.parse(json); }
    catch (e) {
      this.#convo.push('User: ' + userRequest);
      this.#convo.push('Assistant: parse error on model output');
      this.#trimConvo();
      return { responseText: raw, parseError: true, applied: 0 };
    }

  let applied = 0;
    if (applyChanges && plan && Array.isArray(plan.actions)) {
      for (const a of plan.actions) {
        if (!a || !a.type) continue;
        try {
          switch (a.type) {
            case 'create_event': {
              if (a.date && a.time) {
                const now = dayjs();
                if (!allowPast && dayjs(`${a.date} ${a.time}`).isBefore(now)) break;
                const recurring = a.recurring && a.recurring.trim() ? a.recurring : 'non-recurring';
                const title = a.title && a.title.trim() ? a.title : 'Meeting';
                const dur = a.durationMinutes && a.durationMinutes > 0 ? a.durationMinutes : 60;
                await CalendarTool.createCalendarEvent(a.date, a.time, recurring, title, dur);
                applied++;
              }
              break;
            }
            case 'update_event': {
              if (a.title && a.date && a.time && a.newDate && a.newTime) {
                if (!allowPast && dayjs(`${a.newDate} ${a.newTime}`).isBefore(dayjs())) break;
                const ok = await CalendarTool.updateEventByTitleAndStart(a.title, a.date, a.time, a.newDate, a.newTime);
                if (ok) applied++;
              }
              break;
            }
            case 'resize_event': {
              if (a.title && a.date && a.time && a.newDurationMinutes) {
                const ok = await CalendarTool.updateEventDuration(a.title, a.date, a.time, a.newDurationMinutes);
                if (ok) applied++;
              }
              break;
            }
            case 'delete_event': {
              if (a.title && a.date && a.time) {
                const ok = await CalendarTool.deleteEventByTitleAndStart(a.title, a.date, a.time);
                if (ok) applied++;
              }
              break;
            }
            case 'auto_space': {
              const gap = a.minGapMinutes && a.minGapMinutes > 0 ? a.minGapMinutes : 60;
              const moved = await CalendarTool.autoSpaceEvents(gap);
              if (moved > 0) applied += moved;
              break;
            }
            case 'bulk_update': {
              if (Array.isArray(a.moves)) {
                for (const m of a.moves) {
                  if (!m || !m.title || !m.date || !m.time || !m.newDate || !m.newTime) continue;
                  if (!allowPast && dayjs(`${m.newDate} ${m.newTime}`).isBefore(dayjs())) continue;
                  let ok = false;
                  try { ok = await CalendarTool.updateEventWithConflictResolution(m.title, m.date, m.time, m.newDate, m.newTime); }
                  catch (_) { /* fallback below */ }
                  if (!ok) ok = await CalendarTool.updateEventByTitleAndStart(m.title, m.date, m.time, m.newDate, m.newTime);
                  if (ok) applied++;
                }
              }
              break;
            }
            case 'rebalance_week': {
              const moved = await CalendarTool.rebalanceWeek();
              if (moved > 0) applied += moved;
              break;
            }
            case 'ask_clarification':
            case 'respond':
              // no mutation
              break;
            default:
              // ignore
          }
        } catch (_) { /* ignore per action */ }
      }
    }

    const suggestions = Array.isArray(plan?.suggestions) && plan.suggestions.length
      ? plan.suggestions.map(s => s?.note).filter(Boolean).map(n => '- ' + n).join('\n')
      : '';

    // Extract potential time-slot choices for next-turn fast selection
    this.#pendingChoices = [];
    if (Array.isArray(plan?.suggestions)) {
      const createRegex = /Create\s+'([^']+)'\s+on\s+(\d{4}-\d{2}-\d{2})\s+at\s+(\d{2}:\d{2})/i;
      for (const s of plan.suggestions) {
        if (!s || !s.note) continue;
        const m = s.note.match(createRegex);
        if (m) {
          this.#pendingChoices.push({ title: m[1], date: m[2], time: m[3], durationMinutes: 60 });
        }
      }
    }
    // If model already created events, discard pending choices to avoid duplicates
    if (Array.isArray(plan?.actions) && plan.actions.some(a => a?.type === 'create_event')) {
      this.#pendingChoices = [];
    }

    this.#convo.push('User: ' + userRequest);
    this.#convo.push('Assistant: applied ' + applied + ' action(s)');
    this.#trimConvo();

    // Only include a textual calendar summary if the model explicitly asked for it
    // via a respond action with includeSummary=true.
    let includeSummary = false;
    if (plan && Array.isArray(plan.actions)) {
      for (const a of plan.actions) {
        if (a && a.type === 'respond' && a.includeSummary) { includeSummary = true; break; }
      }
    }

    return {
      responseText: raw,
      plan,
      applied,
      summaryAfter: includeSummary ? CalendarTool.summarizeCalendar() : undefined,
      suggestions
    };
  }

  #trimConvo() {
    while (this.#convo.length > 12) this.#convo.shift();
  }
}
