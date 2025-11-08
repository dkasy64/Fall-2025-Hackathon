package com.gemini.cli;

import com.gemini.backend.service.CalendarTool;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Scanner;

public class GenerateTextFromTextInput {
    @SuppressWarnings("resource")
    private static String promptFromStdin(String message) {
        System.out.println(message);
        Scanner input = new Scanner(System.in);
        return input.nextLine();
    }

    // POJOs for parsing Gemini's JSON output
    static class ActionPlan {
        List<Action> actions;
        List<Suggestion> suggestions; // model may propose optional improvements
        List<Clarification> clarifications; // model may ask for user input before applying changes
    }
    static class Action {
        String type;      // e.g., "create_event"
        String date;      // yyyy-MM-dd
        String time;      // HH:mm
        String recurring; // non-recurring|daily|weekly|monthly|yearly
        String title;     // optional title for create/update/delete
        Integer durationMinutes; // optional for create_event
        // for update
        String newDate;
        String newTime;
        Integer newDurationMinutes; // for resize_event
        Integer minGapMinutes; // for auto spacing
        List<Move> moves; // for bulk updates
    String question; // for ask_clarification
        String message; // for respond
        Boolean includeSummary; // for respond
    }
    static class Move {
        String title;
        String date;
        String time;
        String newDate;
        String newTime;
    }
    static class Suggestion {
        String note;
    }
    static class Clarification {
        String question;
    }
    // (Removed stray closing brace)

    private static String sanitizeJson(String raw) {
        String s = raw.trim();
        // Strip code fences if present
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline != -1) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }

    public static void main(String[] args) {
        // The client gets the API key from the environment variable `GEMINI_API_KEY`.
        String apiKey = System.getenv("GEMINI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("WARNING: GEMINI_API_KEY env var not set; model call will fail unless key is provided.");
            apiKey = ""; // fallback blank
        }
        Client client = Client.builder().apiKey(apiKey).build();

        System.out.println("Type 'help' for commands. The assistant will keep running until you type 'exit' or 'quit'.\n");

    Gson gson = new Gson();
    java.util.Deque<String> convo = new java.util.ArrayDeque<>(); // simple rolling conversation context
        while (true) {
            // Ask the user how we can help organize the calendar
            String userRequest = promptFromStdin(
                    "How can I help organize your calendar?\n" +
                    "Examples:\n" +
                    "  - Add a meeting on 2025-11-10 at 09:30 weekly\n" +
                    "  - Add a dentist appointment sometime this week\n" +
                    "  - Schedule daily focus time at 14:00\n> ");

            if (userRequest == null) continue;
            String cmd = userRequest.trim().toLowerCase();
            if (cmd.equals("exit") || cmd.equals("quit")) {
                System.out.println("Bye!");
                break;
            }
            if (cmd.equals("help")) {
                System.out.println("Commands: summary | help | exit\n" +
                        "Or describe changes like: 'Move the kickoff meeting to tomorrow 10:30'\n");
                continue;
            }
            if (cmd.equals("summary") || cmd.contains("summarize") || cmd.contains("summurize") || cmd.contains("what does my week") || cmd.contains("week look like") || cmd.contains("what is my week")) {
                System.out.println(CalendarTool.summarizeCalendar());
                continue;
            }

            LocalDate today = LocalDate.now();
            LocalDate weekStart = today.with(DayOfWeek.MONDAY);
            LocalDate weekEnd = today.with(DayOfWeek.SUNDAY);

        // Instruct Gemini to translate the user's request into a strict JSON action plan
    String instruction = String.join("\n",
                    "You are a calendar assistant. Convert the user's request into JSON only.",
                    "Today's date: " + today + ". Current week (ISO Monday-Sunday) range: " + weekStart + " to " + weekEnd + ".",
                    "Existing calendar ICS content is provided below. Use it to avoid duplicates and allow updates.",
            "Use the conversation context below to resolve pronouns and confirmations like 'yes', 'do that', 'move it', 'the previous one'.",
            "If prior suggestions proposed a concrete time slot and the user confirms (e.g., 'yeah do that'), convert that suggestion into a concrete create_event action with a meaningful title.",
                    "If user says 'this week', choose a date within that range not in the past relative to today (prefer first available weekday).",
            "Never schedule events before the current moment unless the user explicitly asks to backdate (keywords: 'past', 'backdate', 'retroactive', 'yesterday', 'last week'). If a requested time is earlier than now, shift to the next reasonable future slot on the same day or the next day.",
                    "Schema strictly:",
                    "{",
                    "  \"actions\": [",
            "    { \"type\": \"create_event\", \"title\": \"...\", \"date\": \"yyyy-MM-dd\", \"time\": \"HH:mm\", \"durationMinutes\": 60, \"recurring\": \"non-recurring|daily|weekly|monthly|yearly\" },",
                    "    { \"type\": \"update_event\", \"title\": \"...\", \"date\": \"oldDate\", \"time\": \"oldTime\", \"newDate\": \"yyyy-MM-dd\", \"newTime\": \"HH:mm\" },",
            "    { \"type\": \"resize_event\", \"title\": \"...\", \"date\": \"yyyy-MM-dd\", \"time\": \"HH:mm\", \"newDurationMinutes\": 240 },",
                    "    { \"type\": \"delete_event\", \"title\": \"...\", \"date\": \"yyyy-MM-dd\", \"time\": \"HH:mm\" },",
                    "    { \"type\": \"auto_space\", \"minGapMinutes\": 60 },",
                    "    { \"type\": \"bulk_update\", \"moves\": [ { \"title\": \"...\", \"date\": \"yyyy-MM-dd\", \"time\": \"HH:mm\", \"newDate\": \"yyyy-MM-dd\", \"newTime\": \"HH:mm\" } ] }",
            "    { \"type\": \"rebalance_week\" },",
                    "    { \"type\": \"ask_clarification\", \"question\": \"...\" }",
        "    { \"type\": \"respond\", \"message\": \"...\", \"includeSummary\": false }",
                    "  ],",
                    "  \"suggestions\": [ { \"note\": \"Human readable optional suggestion...\" } ]",
                    "}",
                    "Rules:",
                    "- Use title fields meaningfully (e.g., 'Dentist Appointment').",
                    "- For unspecified time choose 10:00 local time.",
                    "- make sure when scheduling events, give more priority to later time slots in the day and to days with less stuff scheduled",
                    "- Avoid creating duplicates (same title + start). Prefer update if user implies reschedule.",
            "- When the user asks to change how long an event lasts, use resize_event with newDurationMinutes (e.g., 240 for 4 hours).",
            "- For new events with specified duration, set durationMinutes on create_event (defaults to 60 if omitted).",
        "- If the user asks to move multiple events, prefer a single 'bulk_update' action with a 'moves' array. Each move may change both date and time (cross-day moves allowed).",
        "- If the user says phrases like 'free up <date>' or 'clear <date>' prefer rescheduling events off that date using bulk_update moves to future days instead of delete_event unless user explicitly says 'delete' or 'remove'.",
        "- If the user's instruction is ambiguous (missing which event or target), use ask_clarification with a question instead of guessing.",
        "- For direct questions (e.g., 'what does my week look like?', 'summarize', 'what's on <date>?'), include a 'respond' action with a friendly concise natural-language answer and set includeSummary=true if a full calendar summary should follow.",
        "- For daily recurring bedtime requests (e.g., 'add a daily bed time of 8pm', 'add bedtime at 8pm every day'), create a create_event with title 'Bed Time', time '20:00', date = today if 20:00 is in the future else tomorrow, recurring='daily', durationMinutes=60.",
        "- Recognize synonyms: daily|every day|each day|nightly; bedtime|bed time|bed-time.",
                    "- If the user asks to spread events across the week, use 'rebalance_week'.",
            "- When the user confirms a prior suggestion (e.g., 'yeah do that'), turn that suggestion into concrete actions (e.g., create_event).",
                    "- Return JSON only. No markdown, no prose.",
                    "- If no actions, still return {\"actions\":[],\"suggestions\":[]}.");

            String existingCalendar = CalendarTool.readCalendarContent();
            // Determine if user explicitly allows past scheduling
            boolean allowPast = false;
            if (userRequest != null) {
                String ur = userRequest.toLowerCase();
                String[] pastKeys = new String[]{" backdate", "retroactive", "in the past", "past", "yesterday", "last week", "last monday", "last tuesday", "last wednesday", "last thursday", "last friday", "last saturday", "last sunday"};
                for (String k : pastKeys) { if (ur.contains(k)) { allowPast = true; break; } }
            }
        // Build recent conversation context (last ~6 lines)
        StringBuilder ctx = new StringBuilder();
        if (!convo.isEmpty()) {
        ctx.append("Conversation context (most recent first):\n");
        java.util.Iterator<String> it = convo.descendingIterator();
        int c = 0;
        while (it.hasNext() && c < 6) { ctx.append(it.next()).append('\n'); c++; }
        }

        String prompt = instruction + "\n\nExisting calendar ICS:\n" + existingCalendar +
            "\n\n" + ctx.toString() +
            "User request:\n" + userRequest;

            GenerateContentResponse response = client.models.generateContent(
                    "gemini-2.5-flash",
                    prompt,
                    null);

            String raw = response.text();
            String json = sanitizeJson(raw);

            try {
                ActionPlan plan = gson.fromJson(json, ActionPlan.class);
                int applied = 0;

                // Show plan and confirm before applying
                boolean hasActions = plan != null && plan.actions != null && !plan.actions.isEmpty();
                boolean requiresConfirm = false;
                if (hasActions) {
                    // Determine if any action mutates state; 'respond' and 'ask_clarification' do not
                    for (Action a : plan.actions) {
                        if (a == null || a.type == null) continue;
                        switch (a.type) {
                            case "create_event":
                            case "update_event":
                            case "resize_event":
                            case "delete_event":
                            case "auto_space":
                            case "bulk_update":
                            case "rebalance_week":
                                requiresConfirm = true;
                                break;
                            default:
                                // non-mutating, no confirm required
                        }
                        if (requiresConfirm) break;
                    }
                    System.out.println("Planned changes:");
                    int idx = 1;
                    for (Action a : plan.actions) {
                        if (a == null || a.type == null) continue;
                        switch (a.type) {
                            case "create_event":
                                System.out.println(" " + (idx++) + ". CREATE  title='" + (a.title == null ? "(none)" : a.title) +
                                        "' date=" + a.date + " time=" + a.time +
                                        (a.durationMinutes != null ? (" duration=" + a.durationMinutes + "m") : "") +
                                        " recurring=" + (a.recurring == null ? "non-recurring" : a.recurring));
                                break;
                            case "update_event":
                                System.out.println(" " + (idx++) + ". UPDATE  title='" + a.title + "' " + a.date + " " + a.time +
                                        " -> " + a.newDate + " " + a.newTime);
                                break;
                            case "resize_event":
                                System.out.println(" " + (idx++) + ". RESIZE  title='" + a.title + "' " + a.date + " " + a.time +
                                        " -> duration=" + (a.newDurationMinutes == null ? "(missing)" : a.newDurationMinutes + "m"));
                                break;
                            case "delete_event":
                                System.out.println(" " + (idx++) + ". DELETE  title='" + a.title + "' " + a.date + " " + a.time);
                                break;
                            case "auto_space":
                                System.out.println(" " + (idx++) + ". AUTO_SPACE  minGapMinutes=" + (a.minGapMinutes == null ? "60" : a.minGapMinutes));
                                break;
                            case "bulk_update":
                                int count = (a.moves == null ? 0 : a.moves.size());
                                System.out.println(" " + (idx++) + ". BULK_UPDATE  moves=" + count);
                                if (count > 0) {
                                    int j = 1;
                                    for (Move m : a.moves) {
                                        if (m == null) continue;
                                        System.out.println("     - [" + (j++) + "] '" + m.title + "' " + m.date + " " + m.time + " -> " + m.newDate + " " + m.newTime);
                                    }
                                }
                                break;
                            case "rebalance_week":
                                System.out.println(" " + (idx++) + ". REBALANCE_WEEK distribute events across days");
                                break;
                            case "ask_clarification":
                                System.out.println(" " + (idx++) + ". ASK_CLARIFICATION question='" + (a.question == null ? "(none)" : a.question) + "'");
                                break;
                            case "respond":
                                System.out.println(" " + (idx++) + ". RESPOND message='" + (a.message == null ? "" : a.message) + "'" + (Boolean.TRUE.equals(a.includeSummary) ? " includeSummary" : ""));
                                break;
                            default:
                                System.out.println(" " + (idx++) + ". (unsupported) type='" + a.type + "'");
                        }
                    }
                    if (requiresConfirm) {
                        String confirm = promptFromStdin("Apply these changes? [y/N]: ");
                        if (confirm == null || !(confirm.equalsIgnoreCase("y") || confirm.equalsIgnoreCase("yes"))) {
                            System.out.println("Cancelled. No changes applied.\n");
                            hasActions = false; // prevent applying
                        }
                    }
                }

                if (hasActions) {
                    for (Action a : plan.actions) {
                        if (a == null || a.type == null) continue;
                        try {
                            switch (a.type) {
                                case "create_event":
                                    if (a.date != null && a.time != null) {
                                        // past guard
                                        try {
                                            LocalDateTime start = LocalDateTime.of(LocalDate.parse(a.date), LocalTime.parse(a.time));
                                            if (!allowPast && start.isBefore(LocalDateTime.now())) {
                                                System.out.println("Skipped create_event in the past (say 'backdate' to allow): " + a.title + " " + a.date + " " + a.time);
                                                break;
                                            }
                                        } catch (Exception ignore) { /* if parsing fails, proceed */ }
                                        String recurring = (a.recurring == null || a.recurring.isBlank()) ? "non-recurring" : a.recurring;
                                        String title = (a.title == null || a.title.isBlank()) ? "Meeting" : a.title;
                                        if (a.durationMinutes != null && a.durationMinutes > 0) {
                                            CalendarTool.createCalendarEvent(a.date, a.time, recurring, title, a.durationMinutes);
                                        } else {
                                            CalendarTool.createCalendarEvent(a.date, a.time, recurring, title);
                                        }
                                        applied++;
                                    } else {
                                        System.out.println("Skipped create_event missing date/time.");
                                    }
                                    break;
                                case "update_event":
                                    if (a.title != null && a.date != null && a.time != null && a.newDate != null && a.newTime != null) {
                                        // past guard for new time
                                        try {
                                            LocalDateTime target = LocalDateTime.of(LocalDate.parse(a.newDate), LocalTime.parse(a.newTime));
                                            if (!allowPast && target.isBefore(LocalDateTime.now())) {
                                                System.out.println("Skipped update_event to past (say 'backdate' to allow): " + a.title + " -> " + a.newDate + " " + a.newTime);
                                                break;
                                            }
                                        } catch (Exception ignore) { }
                                        boolean ok = CalendarTool.updateEventByTitleAndStart(a.title, a.date, a.time, a.newDate, a.newTime);
                                        if (ok) applied++; else System.out.println("Update failed to match event: " + a.title);
                                    } else {
                                        System.out.println("Skipped update_event missing fields.");
                                    }
                                    break;
                                case "resize_event":
                                    if (a.title != null && a.date != null && a.time != null && a.newDurationMinutes != null) {
                                        boolean ok = CalendarTool.updateEventDuration(a.title, a.date, a.time, a.newDurationMinutes);
                                        if (ok) applied++; else System.out.println("Resize failed to match event: " + a.title);
                                    } else {
                                        System.out.println("Skipped resize_event missing fields.");
                                    }
                                    break;
                                case "delete_event":
                                    if (a.title != null && a.date != null && a.time != null) {
                                        boolean ok = CalendarTool.deleteEventByTitleAndStart(a.title, a.date, a.time);
                                        if (ok) applied++; else System.out.println("Delete failed to match event: " + a.title);
                                    } else {
                                        System.out.println("Skipped delete_event missing fields.");
                                    }
                                    break;
                                case "auto_space":
                                    int gap = (a.minGapMinutes == null || a.minGapMinutes <= 0) ? 60 : a.minGapMinutes;
                                    int moved = CalendarTool.autoSpaceEvents(gap);
                                    System.out.println("Auto-space moved events: " + moved);
                                    if (moved > 0) applied += moved;
                                    break;
                                case "bulk_update":
                                    if (a.moves != null && !a.moves.isEmpty()) {
                                        for (Move m : a.moves) {
                                            if (m == null) continue;
                                            if (m.title != null && m.date != null && m.time != null && m.newDate != null && m.newTime != null) {
                                                // past guard for move target
                                                try {
                                                    LocalDateTime target = LocalDateTime.of(LocalDate.parse(m.newDate), LocalTime.parse(m.newTime));
                                                    if (!allowPast && target.isBefore(LocalDateTime.now())) {
                                                        System.out.println("Skipped bulk move to past (say 'backdate' to allow): " + m.title + " -> " + m.newDate + " " + m.newTime);
                                                        continue;
                                                    }
                                                } catch (Exception ignore) { }
                                                boolean ok = false;
                                                try {
                                                    // try conflict-aware move first (supports cross-day)
                                                    ok = CalendarTool.updateEventWithConflictResolution(m.title, m.date, m.time, m.newDate, m.newTime);
                                                } catch (Exception ex) {
                                                    // fall back below
                                                }
                                                if (!ok) {
                                                    ok = CalendarTool.updateEventByTitleAndStart(m.title, m.date, m.time, m.newDate, m.newTime);
                                                }
                                                if (ok) applied++; else System.out.println("Bulk update failed for: " + m.title + " from " + m.date + " " + m.time + " -> " + m.newDate + " " + m.newTime);
                                            } else {
                                                System.out.println("Skipped bulk move due to missing fields.");
                                            }
                                        }
                                    } else {
                                        System.out.println("No moves provided for bulk_update.");
                                    }
                                    break;
                                case "rebalance_week":
                                    try {
                                        int shifted = CalendarTool.rebalanceWeek();
                                        System.out.println("Rebalance moved events: " + shifted);
                                        if (shifted > 0) applied += shifted;
                                    } catch (Exception ex) {
                                        System.out.println("Rebalance failed: " + ex.getMessage());
                                    }
                                    break;
                                case "ask_clarification":
                                    System.out.println("Clarification needed: " + (a.question != null ? a.question : "(no question provided)"));
                                    break;
                                case "respond":
                                    if (a.message != null && !a.message.isBlank()) {
                                        System.out.println(a.message);
                                    }
                                    if (Boolean.TRUE.equals(a.includeSummary)) {
                                        System.out.println();
                                        System.out.println(CalendarTool.summarizeCalendar());
                                    }
                                    break;
                                default:
                                    System.out.println("Unsupported action type: " + a.type + " (skipped)");
                            }
                        } catch (Exception ex) {
                            System.out.println("Action failed (" + a.type + "): " + ex.getMessage());
                        }
                    }
                }

                System.out.println("Applied actions: " + applied);
                if (applied == 0 && (plan == null || plan.actions == null || plan.actions.isEmpty())) {
                    System.out.println("Model output (for debugging):\n" + raw);
                }
                // Show summary only if something changed
                if (applied > 0) {
                    System.out.print("\u001b[2J\u001b[H"); // clear
                    System.out.println(CalendarTool.summarizeCalendar());
                }
                if (plan != null && plan.suggestions != null && !plan.suggestions.isEmpty()) {
                    System.out.println("Suggestions:");
                    for (Suggestion s : plan.suggestions) {
                        if (s != null && s.note != null) System.out.println(" - " + s.note);
                    }
                }
                System.out.println();

                // Update conversation context with a concise assistant note for future turns
                StringBuilder assistantNote = new StringBuilder();
                if (hasActions) {
                    assistantNote.append("Assistant: applied ").append(applied).append(" action(s)");
                } else {
                    assistantNote.append("Assistant: ");
                    if (plan != null && plan.actions != null && !plan.actions.isEmpty()) {
                        assistantNote.append("planned actions pending confirmation");
                    } else {
                        assistantNote.append("no actions");
                    }
                }
                if (plan != null && plan.suggestions != null && !plan.suggestions.isEmpty()) {
                    assistantNote.append("; suggestions: ");
                    int i = 0;
                    for (Suggestion s : plan.suggestions) {
                        if (s != null && s.note != null) {
                            if (i++ > 0) assistantNote.append(" | ");
                            // keep it short
                            assistantNote.append(s.note);
                            if (assistantNote.length() > 400) { assistantNote.append("..."); break; }
                        }
                    }
                }
                // push user and assistant lines into rolling context
                convo.addLast("User: " + userRequest);
                convo.addLast(assistantNote.toString());
                while (convo.size() > 12) convo.removeFirst();
            } catch (JsonSyntaxException jse) {
                System.out.println("Couldn't parse model output as JSON. Raw output:\n" + raw);
                convo.addLast("User: " + userRequest);
                convo.addLast("Assistant: parse error on model output");
                while (convo.size() > 12) convo.removeFirst();
            }
        }
    }
}