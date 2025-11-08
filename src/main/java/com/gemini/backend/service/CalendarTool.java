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
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;
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
        // Locate or create the .ics file
        File file = new File("../resources/sample-calendar.ics");
        Calendar calendar;

        if (file.exists()) {
            try (FileInputStream fin = new FileInputStream(file)) {
                CalendarBuilder builder = new CalendarBuilder();
                calendar = builder.build(fin);
            }
        } else {
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
        DateTime end = new DateTime(eventDate.getTime() + (60 * 60 * 1000)); // 1-hour default duration

        // Create unique event
        VEvent event = new VEvent(start, end, "AI-Created Event");
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
}
