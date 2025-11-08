package com.gemini.backend.service;

import net.fortuna.ical4j.data.CalendarOutputter;
import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.*;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.*;
import net.fortuna.ical4j.model.parameter.Value;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public class CalendarTool {

    /**
     * Creates a calendar event and updates ../resources/sample-calendar.ics
     *
     * @param date        - format "yyyy-MM-dd"
     * @param time        - format "HH:mm"
     * @param recurring   - one of: "non-recurring", "daily", "weekly", "monthly", "yearly"
     * @throws Exception if there’s an error updating the .ics file
     */
    public static void createCalendarEvent(String date, String time, String recurring) throws Exception {
        createCalendarEvent(date, time, recurring, "AI-Created Event", null);
    }

    public static void createCalendarEvent(String date, String time, String recurring, String title) throws Exception {
        createCalendarEvent(date, time, recurring, title, null);
    }

    /**
     * Create an event with an optional custom duration.
     * @param durationMinutes Optional; if null or <=0 defaults to 60 minutes
     */
    public static void createCalendarEvent(String date, String time, String recurring, String title, Integer durationMinutes) throws Exception {
        File file = resolveCalendarFile();
        Calendar calendar;

        if (file.exists()) {
            try (FileInputStream fin = new FileInputStream(file)) {
                CalendarBuilder builder = new CalendarBuilder();
                calendar = builder.build(fin);
            }
        } else {
            file.getParentFile().mkdirs();
            calendar = new Calendar();
            calendar.getProperties().add(new ProdId("-//AI Calendar//Gemini Tool//EN"));
            calendar.getProperties().add(Version.VERSION_2_0);
            calendar.getProperties().add(CalScale.GREGORIAN);
        }

        // Parse date and time
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date eventDate = formatter.parse(date + " " + time);

    // Start and end time
    DateTime start = new DateTime(eventDate);
    int dur = (durationMinutes == null || durationMinutes <= 0) ? 60 : durationMinutes;
    DateTime end = new DateTime(eventDate.getTime() + (long)dur * 60 * 1000); // default or custom duration

    // Create unique event
    VEvent event = new VEvent(start, end, title == null || title.isBlank() ? "AI-Created Event" : title);
        event.getProperties().add(new Uid(UUID.randomUUID().toString()));

        // Add recurrence if applicable
        switch (recurring.toLowerCase()) {
            case "daily":
                event.getProperties().add(new RRule("FREQ=DAILY"));
                break;
            case "weekly":
                event.getProperties().add(new RRule("FREQ=WEEKLY"));
                break;
            case "monthly":
                event.getProperties().add(new RRule("FREQ=MONTHLY"));
                break;
            case "yearly":
                event.getProperties().add(new RRule("FREQ=YEARLY"));
                break;
            default:
                // non-recurring = no RRule
                break;
        }

        // Add event to calendar
        calendar.getComponents().add(event);

        // Write back to file
        try (FileOutputStream fout = new FileOutputStream(file)) {
            CalendarOutputter outputter = new CalendarOutputter();
            outputter.output(calendar, fout);
        }
        System.out.println("✅ Event added to " + file.getAbsolutePath());
    }

    /**
     * Update an event's duration by title and exact start time.
     * Keeps the same DTSTART and sets DTEND = DTSTART + new duration.
     * @return true if updated, false if not found
     */
    public static boolean updateEventDuration(String title, String date, String time, int durationMinutes) throws Exception {
        if (durationMinutes <= 0) durationMinutes = 60;
        Calendar calendar = loadOrCreate();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date startMatch = formatter.parse(date + " " + time);

        for (Component comp : calendar.getComponents(Component.VEVENT)) {
            if (!(comp instanceof VEvent)) continue;
            VEvent ev = (VEvent) comp;
            Summary sum = ev.getSummary();
            if (sum == null || sum.getValue() == null || !sum.getValue().equalsIgnoreCase(title)) continue;
            DtStart ds = ev.getStartDate();
            DtEnd de = ev.getEndDate(true);
            if (ds == null || de == null) continue;
            Date dsJava = new Date(ds.getDate().getTime());
            if (dsJava.getTime() != startMatch.getTime()) continue;

            // compute new end
            Date newEnd = new Date(dsJava.getTime() + (long)durationMinutes * 60 * 1000);
            // update properties
            ev.getProperties().remove(ev.getProperty(Property.DTEND));
            ev.getProperties().add(new DtEnd(new DateTime(newEnd)));
            save(calendar);
            return true;
        }
        return false;
    }

    private static File resolveCalendarFile() {
        // Prefer src/main/resources path so it remains part of project source
        Path primary = Paths.get("src", "main", "resources", "sample-calendar.ics");
        if (Files.exists(primary)) {
            return primary.toFile();
        }
        // Fallback to target/classes if running from packaged context
        Path target = Paths.get("target", "classes", "sample-calendar.ics");
        if (Files.exists(target)) {
            return target.toFile();
        }
        // Otherwise create in src/main/resources
        return primary.toFile();
    }

    public static File getCalendarFile() {
        return resolveCalendarFile();
    }

    public static String readCalendarContent() {
        File file = resolveCalendarFile();
        try {
            if (file.exists()) {
                return Files.readString(file.toPath());
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static Calendar loadOrCreate() throws Exception {
        File file = resolveCalendarFile();
        if (file.exists()) {
            try (FileInputStream fin = new FileInputStream(file)) {
                CalendarBuilder builder = new CalendarBuilder();
                return builder.build(fin);
            }
        }
        file.getParentFile().mkdirs();
        Calendar calendar = new Calendar();
        calendar.getProperties().add(new ProdId("-//AI Calendar//Gemini Tool//EN"));
        calendar.getProperties().add(Version.VERSION_2_0);
        calendar.getProperties().add(CalScale.GREGORIAN);
        return calendar;
    }

    private static void save(Calendar calendar) throws Exception {
        File file = resolveCalendarFile();
        try (FileOutputStream fout = new FileOutputStream(file)) {
            CalendarOutputter outputter = new CalendarOutputter();
            outputter.output(calendar, fout);
        }
    }

    public static boolean updateEventByTitleAndStart(String title, String date, String time, String newDate, String newTime) throws Exception {
        Calendar calendar = loadOrCreate();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date oldStartDate = formatter.parse(date + " " + time);
        Date newStartDate = formatter.parse(newDate + " " + newTime);

        boolean updated = false;
        for (Component comp : calendar.getComponents(Component.VEVENT)) {
            if (comp instanceof VEvent) {
                VEvent ev = (VEvent) comp;
                Summary sum = ev.getSummary();
                if (sum != null && sum.getValue() != null && sum.getValue().equalsIgnoreCase(title)) {
                    DtStart dtStart = ev.getStartDate();
                    if (dtStart != null) {
                        DateTime start = (DateTime) dtStart.getDate();
                        if (start != null && start.getTime() == oldStartDate.getTime()) {
                            // Keep same duration
                            DateTime oldEnd = (DateTime) ev.getEndDate(true).getDate();
                            long duration = oldEnd.getTime() - start.getTime();
                            DateTime newStart = new DateTime(newStartDate);
                            DateTime newEnd = new DateTime(newStartDate.getTime() + duration);
                            ev.getProperties().remove(ev.getProperty(Property.DTSTART));
                            ev.getProperties().remove(ev.getProperty(Property.DTEND));
                            ev.getProperties().add(new DtStart(newStart));
                            ev.getProperties().add(new DtEnd(newEnd));
                            updated = true;
                            break;
                        }
                    }
                }
            }
        }
        if (updated) save(calendar);
        return updated;
    }

    /**
     * Update an event with basic conflict resolution. If the desired new slot overlaps another non-all-day
     * event, shift forward by 30 minutes until free or until day boundary is reached.
     * @return true if updated (possibly after shifting), false otherwise.
     */
    public static boolean updateEventWithConflictResolution(String title, String date, String time, String newDate, String newTime) throws Exception {
        Calendar calendar = loadOrCreate();
        SimpleDateFormat dtFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date oldStart = dtFmt.parse(date + " " + time);
        Date targetStart = dtFmt.parse(newDate + " " + newTime);

        VEvent targetEvent = null;
        long durationMs = 60 * 60 * 1000; // fallback
        for (Component comp : calendar.getComponents(Component.VEVENT)) {
            if (comp instanceof VEvent) {
                VEvent ev = (VEvent) comp;
                Summary sum = ev.getSummary();
                if (sum != null && sum.getValue() != null && sum.getValue().equalsIgnoreCase(title)) {
                    DtStart ds = ev.getStartDate();
                    if (ds != null && ds.getDate().getTime() == oldStart.getTime()) {
                        targetEvent = ev;
                        Date start = new Date(ds.getDate().getTime());
                        Date end = new Date(ev.getEndDate(true).getDate().getTime());
                        durationMs = Math.max(0, end.getTime() - start.getTime());
                        break;
                    }
                }
            }
        }
        if (targetEvent == null) return false;

        // Build list of other event intervals on the new date
        String newDay = newDate;
        List<VEvent> sameDay = new ArrayList<>();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");
        for (Component comp : calendar.getComponents(Component.VEVENT)) {
            if (comp instanceof VEvent) {
                VEvent ev = (VEvent) comp;
                if (ev == targetEvent) continue;
                DtStart ds = ev.getStartDate();
                DtEnd de = ev.getEndDate(true);
                if (ds == null || de == null) continue;
                boolean allDay = (Value.DATE.equals(ds.getParameter(Value.VALUE))) || (Value.DATE.equals(de.getParameter(Value.VALUE)));
                if (allDay) continue;
                String day = dayFmt.format(new Date(ds.getDate().getTime()));
                if (day.equals(newDay)) sameDay.add(ev);
            }
        }

        // Try shifting forward in 30m increments until no overlap
        long increment = 30 * 60 * 1000L;
        int attempts = 0;
        while (attempts < 48) { // up to 24 hours of shifts
            Date candidateEnd = new Date(targetStart.getTime() + durationMs);
            if (!overlaps(candidateEnd, targetStart, sameDay)) {
                // apply update
                targetEvent.getProperties().remove(targetEvent.getProperty(Property.DTSTART));
                targetEvent.getProperties().remove(targetEvent.getProperty(Property.DTEND));
                targetEvent.getProperties().add(new DtStart(new DateTime(targetStart)));
                targetEvent.getProperties().add(new DtEnd(new DateTime(candidateEnd)));
                save(calendar);
                return true;
            }
            targetStart = new Date(targetStart.getTime() + increment);
            // ensure still same day
            if (!dayFmt.format(targetStart).equals(newDay)) break;
            attempts++;
        }
        return false;
    }

    private static boolean overlaps(Date candidateEnd, Date candidateStart, List<VEvent> events) {
        for (VEvent ev : events) {
            DtStart ds = ev.getStartDate();
            DtEnd de = ev.getEndDate(true);
            if (ds == null || de == null) continue;
            Date s = new Date(ds.getDate().getTime());
            Date e = new Date(de.getDate().getTime());
            boolean conflict = candidateStart.before(e) && s.before(candidateEnd);
            if (conflict) return true;
        }
        return false;
    }

    public static boolean deleteEventByTitleAndStart(String title, String date, String time) throws Exception {
        Calendar calendar = loadOrCreate();
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        Date oldStartDate = formatter.parse(date + " " + time);
        Component toRemove = null;
        for (Component comp : calendar.getComponents(Component.VEVENT)) {
            if (comp instanceof VEvent) {
                VEvent ev = (VEvent) comp;
                Summary sum = ev.getSummary();
                if (sum != null && sum.getValue() != null && sum.getValue().equalsIgnoreCase(title)) {
                    DtStart dtStart = ev.getStartDate();
                    if (dtStart != null) {
                        DateTime start = (DateTime) dtStart.getDate();
                        if (start != null && start.getTime() == oldStartDate.getTime()) {
                            toRemove = ev;
                            break;
                        }
                    }
                }
            }
        }
        if (toRemove != null) {
            calendar.getComponents().remove(toRemove);
            save(calendar);
            return true;
        }
        return false;
    }

    public static String summarizeCalendar() {
        try {
            Calendar calendar = loadOrCreate();
            List<VEvent> events = new ArrayList<>();
            for (Component comp : calendar.getComponents(Component.VEVENT)) {
                if (comp instanceof VEvent) events.add((VEvent) comp);
            }
            // Sort using java.util.Date to handle both Date and DateTime values
            Collections.sort(events, Comparator.comparing(ev -> {
                Property p = ev.getProperty(Property.DTSTART);
                if (p instanceof DtStart) {
                    net.fortuna.ical4j.model.Date d = ((DtStart) p).getDate();
                    return new Date(d.getTime());
                }
                return new Date(0);
            }));

            SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");
            SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm");
            StringBuilder sb = new StringBuilder();
            sb.append("Calendar summary:\n");
            String currentDay = null;
            int count = 0;
            for (VEvent ev : events) {
                DtStart dtStart = ev.getStartDate();
                DtEnd dtEnd = ev.getEndDate(true);
                net.fortuna.ical4j.model.Date sDate = dtStart != null ? dtStart.getDate() : null;
                net.fortuna.ical4j.model.Date eDate = dtEnd != null ? dtEnd.getDate() : null;

                Date sJava = sDate != null ? new Date(sDate.getTime()) : null;
                Date eJava = eDate != null ? new Date(eDate.getTime()) : null;

                String day = sJava != null ? dayFmt.format(sJava) : "(unknown day)";
                if (!day.equals(currentDay)) {
                    currentDay = day;
                    sb.append("\n").append(day).append("\n");
                }
                String title = ev.getSummary() != null ? ev.getSummary().getValue() : "(untitled)";

                boolean allDay = (dtStart != null && Value.DATE.equals(dtStart.getParameter(Value.VALUE)))
                               || (dtEnd != null && Value.DATE.equals(dtEnd.getParameter(Value.VALUE)));
                if (allDay) {
                    sb.append("  (all-day) ").append(title).append("\n");
                } else {
                    String sTime = sJava != null ? timeFmt.format(sJava) : "??";
                    String eTime = eJava != null ? timeFmt.format(eJava) : "??";
                    sb.append("  ").append(sTime).append("-").append(eTime).append(" ").append(title).append("\n");
                }
                count++;
            }
            sb.append("\nTotal events: ").append(count).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "Failed to summarize calendar: " + e.getMessage();
        }
    }

    /**
     * Rebalance events within the current week to distribute them more evenly across days.
     * Skips all-day and recurring events. Keeps duration. Moves events to target days if they are overloaded.
     * Strategy: compute per-day counts, then move from heaviest days to lightest days, placing events at the
     * first available 10:00+ slot with at least 60m gaps. This is a heuristic, not optimal.
     *
     * @return number of events moved
     */
    public static int rebalanceWeek() throws Exception {
        Calendar calendar = loadOrCreate();
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");
        // Determine the week range (Monday-Sunday) using today's date
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate weekStart = today.with(java.time.DayOfWeek.MONDAY);
        java.time.LocalDate weekEnd = today.with(java.time.DayOfWeek.SUNDAY);
        List<String> weekDays = new ArrayList<>();
        for (java.time.LocalDate d = weekStart; !d.isAfter(weekEnd); d = d.plusDays(1)) {
            weekDays.add(d.toString());
        }

        // Build per-day event lists including empty days
        java.util.Map<String, List<VEvent>> byDay = new java.util.HashMap<>();
        for (String wd : weekDays) byDay.put(wd, new ArrayList<>());

        List<VEvent> candidates = new ArrayList<>();
        for (Component comp : calendar.getComponents(Component.VEVENT)) {
            if (!(comp instanceof VEvent)) continue;
            VEvent ev = (VEvent) comp;
            DtStart ds = ev.getStartDate();
            DtEnd de = ev.getEndDate(true);
            if (ds == null || de == null) continue;
            boolean allDay = (Value.DATE.equals(ds.getParameter(Value.VALUE))) || (Value.DATE.equals(de.getParameter(Value.VALUE)));
            if (allDay) continue;
            if (ev.getProperty(Property.RRULE) != null) continue; // skip recurring
            String day = dayFmt.format(new Date(ds.getDate().getTime()));
            // only consider events inside this week range
            if (day.compareTo(weekStart.toString()) >= 0 && day.compareTo(weekEnd.toString()) <= 0) {
                byDay.get(day).add(ev);
                candidates.add(ev);
            }
        }

        // Helper to find a free slot on target day (start search at 10:00)
        java.util.function.BiFunction<String, Long, Date[]> placeOnDay = (day, durationMs) -> {
            try {
                // don't place on days in the past
                if (day.compareTo(today.toString()) < 0) return null;
                List<Date[]> intervals = new ArrayList<>();
                for (VEvent ev : byDay.getOrDefault(day, new ArrayList<>())) {
                    intervals.add(new Date[]{ new Date(ev.getStartDate().getDate().getTime()), new Date(ev.getEndDate(true).getDate().getTime()) });
                }
                intervals.sort(Comparator.comparing(a -> a[0]));
                SimpleDateFormat dtFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                Date cursor = dtFmt.parse(day + " 10:00");
                int attempts = 0;
                while (attempts < 48) { // up to 24 hours search
                    Date end = new Date(cursor.getTime() + durationMs);
                    if (!dayFmt.format(end).equals(day)) return null; // overflow day
                    boolean conflict = false;
                    for (Date[] iv : intervals) {
                        if (cursor.before(iv[1]) && iv[0].before(end)) { conflict = true; break; }
                    }
                    if (!conflict) return new Date[]{cursor, end};
                    cursor = new Date(cursor.getTime() + 30 * 60 * 1000L);
                    attempts++;
                }
                return null;
            } catch (Exception e) { return null; }
        };

        int moved = 0;
        // Iterate redistribution attempts
        for (int iter = 0; iter < 30; iter++) {
            // sort days by load ascending; prefer days today or later
            weekDays.sort(Comparator.comparingInt(d -> byDay.getOrDefault(d, new ArrayList<>()).size()));
            String light = null;
            for (String d : weekDays) {
                if (d.compareTo(today.toString()) >= 0) { light = d; break; }
            }
            if (light == null) break; // no future/ today days available
            String heavy = weekDays.get(weekDays.size() - 1);
            int lightCount = byDay.get(light).size();
            int heavyCount = byDay.get(heavy).size();
            // Allow moves even when difference is exactly 1 so we can utilize empty future days
            if (heavyCount - lightCount <= 0) break; // fully balanced (no strictly heavier day)
            List<VEvent> heavyEvents = byDay.get(heavy);
            if (heavyEvents.isEmpty()) break;
            // pick an event to move: choose one with latest start to free evening first
            heavyEvents.sort(Comparator.comparing(ev -> new Date(ev.getStartDate().getDate().getTime())));
            VEvent ev = heavyEvents.get(heavyEvents.size() - 1);
            Date start = new Date(ev.getStartDate().getDate().getTime());
            Date end = new Date(ev.getEndDate(true).getDate().getTime());
            long duration = Math.max(0, end.getTime() - start.getTime());
            Date[] slot = placeOnDay.apply(light, duration);
            if (slot == null) {
                // can't place on light day; try next lightest if available
                for (int i = 1; i < weekDays.size(); i++) {
                    String nextLight = weekDays.get(i);
                    if (nextLight.compareTo(today.toString()) < 0) continue;
                    slot = placeOnDay.apply(nextLight, duration);
                    if (slot != null) { light = nextLight; break; }
                }
                if (slot == null) break;
            }
            // Move event
            ev.getProperties().remove(ev.getProperty(Property.DTSTART));
            ev.getProperties().remove(ev.getProperty(Property.DTEND));
            ev.getProperties().add(new DtStart(new DateTime(slot[0])));
            ev.getProperties().add(new DtEnd(new DateTime(slot[1])));
            heavyEvents.remove(ev);
            byDay.get(light).add(ev);
            moved++;
        }

        if (moved > 0) save(calendar);
        return moved;
    }
    /**
     * Auto-space events to ensure at least minGapMinutes between consecutive events per day.
     * Skips all-day and recurring events. Keeps event durations intact. Does not move events across days;
     * if a move would push an event past the day end, that event is skipped.
     *
     * @param minGapMinutes Minimum gap between events
     * @return number of events moved
     */
    public static int autoSpaceEvents(int minGapMinutes) throws Exception {
        if (minGapMinutes < 0) minGapMinutes = 0;
        Calendar calendar = loadOrCreate();

        // Group events by yyyy-MM-dd
        SimpleDateFormat dayFmt = new SimpleDateFormat("yyyy-MM-dd");
        String todayStr = java.time.LocalDate.now().toString();
        List<VEvent> events = new ArrayList<>();
        for (Component comp : calendar.getComponents(Component.VEVENT)) {
            if (comp instanceof VEvent) events.add((VEvent) comp);
        }

        // Map day -> list
        java.util.Map<String, List<VEvent>> byDay = new java.util.HashMap<>();
        for (VEvent ev : events) {
            DtStart dtStart = ev.getStartDate();
            if (dtStart == null) continue;
            net.fortuna.ical4j.model.Date sDate = dtStart.getDate();
            String day = dayFmt.format(new Date(sDate.getTime()));
            byDay.computeIfAbsent(day, k -> new ArrayList<>()).add(ev);
        }

        int moved = 0;
        for (java.util.Map.Entry<String, List<VEvent>> entry : byDay.entrySet()) {
            String day = entry.getKey();
            // don't alter days in the past
            if (day.compareTo(todayStr) < 0) continue;
            List<VEvent> dayEvents = entry.getValue();
            // Sort by start time
            Collections.sort(dayEvents, Comparator.comparing(ev -> {
                DtStart ds = ev.getStartDate();
                net.fortuna.ical4j.model.Date d = ds != null ? ds.getDate() : null;
                return d != null ? new Date(d.getTime()) : new Date(0);
            }));

            Date prevEndWithGap = null;
            for (VEvent ev : dayEvents) {
                // Skip all-day
                DtStart ds = ev.getStartDate();
                DtEnd de = ev.getEndDate(true);
                if (ds == null || de == null) continue;
                boolean allDay = (Value.DATE.equals(ds.getParameter(Value.VALUE)))
                              || (Value.DATE.equals(de.getParameter(Value.VALUE)));
                if (allDay) continue;

                // Skip recurring events
                if (ev.getProperty(Property.RRULE) != null) continue;

                Date start = new Date(ds.getDate().getTime());
                Date end = new Date(de.getDate().getTime());
                long durationMs = Math.max(0, end.getTime() - start.getTime());

                if (prevEndWithGap == null) {
                    // First event of the day: set prevEndWithGap to its end + gap
                    prevEndWithGap = new Date(end.getTime() + minGapMinutes * 60L * 1000L);
                    continue;
                }

                // If start is before prevEndWithGap, move it forward
                if (start.before(prevEndWithGap)) {
                    Date newStart = new Date(prevEndWithGap.getTime());
                    // Ensure still same day
                    String newStartDay = dayFmt.format(newStart);
                    if (!newStartDay.equals(day)) {
                        // Would spill to next day, skip moving this event
                        prevEndWithGap = new Date(end.getTime() + minGapMinutes * 60L * 1000L);
                        continue;
                    }
                    Date newEnd = new Date(newStart.getTime() + durationMs);
                    // Update DTSTART/DTEND
                    ev.getProperties().remove(ev.getProperty(Property.DTSTART));
                    ev.getProperties().remove(ev.getProperty(Property.DTEND));
                    ev.getProperties().add(new DtStart(new DateTime(newStart)));
                    ev.getProperties().add(new DtEnd(new DateTime(newEnd)));
                    moved++;
                    start = newStart;
                    end = newEnd;
                }
                prevEndWithGap = new Date(end.getTime() + minGapMinutes * 60L * 1000L);
            }
        }

        if (moved > 0) save(calendar);
        return moved;
    }
}
