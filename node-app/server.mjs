import express from 'express';
import multer from 'multer';
import path from 'path';
import { fileURLToPath } from 'url';
import fs from 'fs';
import { CalendarTool } from './calendarTool.mjs';
import { CalendarAssistantService } from './assistantService.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const app = express();
const upload = multer();

const PORT = process.env.PORT || 8080;

// Parse JSON bodies for chat endpoint
app.use(express.json());

// Serve existing static UI from Java resources directory so we don't duplicate files
const staticDir = path.resolve(__dirname, '../src/main/resources/static');
if (fs.existsSync(staticDir)) {
  app.use(express.static(staticDir));
  console.log('Serving static UI from', staticDir);
} else {
  app.get('/', (_req, res) => res.send('Static UI not found. Place index.html under src/main/resources/static/.'));
}

const GEMINI_KEY = process.env.GEMINI_API_KEY || '';
const assistant = new CalendarAssistantService(GEMINI_KEY);

app.post('/generate', upload.any(), async (req, res) => {
  try {
    if (!GEMINI_KEY) {
      return res.status(500).type('text/plain').send('Server is missing GEMINI_API_KEY environment variable.');
    }
    let prompt;
    let icsFileBuf;
    for (const f of req.files || []) {
      if (f.fieldname === 'icsFile') { icsFileBuf = f.buffer; break; }
    }
    if (req.body && typeof req.body.prompt === 'string') prompt = req.body.prompt.trim();

    const out = [];
    if (icsFileBuf && icsFileBuf.length) {
      const ics = icsFileBuf.toString('utf8');
      if (ics.trim()) {
        CalendarTool.replaceCalendarContent(ics);
        out.push('Uploaded ICS loaded.');
      }
    }

    if (!prompt) {
      out.push(CalendarTool.summarizeCalendar());
      return res.type('text/plain').send(out.join('\n\n'));
    }

    const result = await assistant.handlePrompt(prompt, true);
    if (result.parseError) {
      out.push("Model response couldn't be parsed as JSON. Raw output follows:\n\n" + (result.responseText || '(empty)'));
      return res.type('text/plain').send(out.join('\n'));
    }

    out.push('Applied actions: ' + (result.applied || 0));
    if (result.summaryAfter) out.push('\n' + result.summaryAfter);
    if (result.suggestions) out.push('\nSuggestions:\n' + result.suggestions);

    return res.type('text/plain').send(out.join('\n'));
  } catch (e) {
    console.error(e);
    res.status(500).type('text/plain').send('Server error: ' + (e.message || e));
  }
});

// Simple chat endpoint used by the floating widget in index.html
app.post('/chat', async (req, res) => {
  try {
    if (!GEMINI_KEY) {
      return res.status(500).type('text/plain').send('AI is unavailable: missing GEMINI_API_KEY on server.');
    }
    const message = (req.body && typeof req.body.message === 'string') ? req.body.message.trim() : '';
    if (!message) return res.status(400).type('text/plain').send('Please provide a message.');

    const result = await assistant.handlePrompt(message, true);
    if (result.parseError) {
      return res
        .status(200)
        .type('text/plain')
        .send("I couldn't parse the model output. Try rephrasing your request.\n\n" + (result.responseText || ''));
    }

    // Prefer explicit respond or ask_clarification actions if present
    let respondMsgs = [];
    let clarifyQs = [];
    let includeSummary = false;
    if (result.plan && Array.isArray(result.plan.actions)) {
      for (const a of result.plan.actions) {
        if (!a || !a.type) continue;
        if (a.type === 'respond' && a.message) {
          respondMsgs.push(a.message);
          if (a.includeSummary) includeSummary = true;
        } else if (a.type === 'ask_clarification' && a.question) {
          clarifyQs.push(a.question);
        }
      }
    }

    let out = '';
    if (respondMsgs.length) {
      out += respondMsgs.join('\n\n');
      if (includeSummary) {
        out += '\n\n' + (result.summaryAfter || '');
      }
    } else if ((result.applied || 0) > 0) {
      out += 'Applied actions: ' + result.applied;
    } else if (clarifyQs.length) {
      out += clarifyQs[0];
    } else {
      out += 'What would you like me to do next? I can schedule, move, resize, or clear events. Say "summarize" if you want an overview.';
    }

    if (result.suggestions) {
      out += '\n\nSuggestions:\n' + result.suggestions;
    }

    return res.type('text/plain').send(out);
  } catch (e) {
    console.error(e);
    res.status(500).type('text/plain').send('Server error: ' + (e.message || e));
  }
});

// Allow downloading the current calendar as an .ics file
app.get('/calendar.ics', (_req, res) => {
  try {
    const ics = CalendarTool.readCalendarContent();
    res.setHeader('Content-Type', 'text/calendar; charset=utf-8');
    res.setHeader('Content-Disposition', 'attachment; filename="calendar.ics"');
    res.status(200).send(ics || '');
  } catch (e) {
    res.status(500).type('text/plain').send('Failed to read calendar: ' + (e.message || e));
  }
});

// JSON list of events for frontend rendering
app.get('/events', (_req, res) => {
  try {
    const events = CalendarTool.listEvents();
    res.status(200).json({ events });
  } catch (e) {
    res.status(500).json({ error: e.message || String(e) });
  }
});

app.listen(PORT, () => {
  console.log(`Calendar Assistant server listening on http://localhost:${PORT}`);
});
