package com.haizul.taskbot;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class CalendarService {

    private final Calendar calendar;
    private final ZoneId zoneId;

    public CalendarService(GoogleAuthService auth, ZoneId zoneId) throws Exception {
        this.calendar = new Calendar.Builder(
                auth.getHttpTransport(),
                auth.getJsonFactory(),
                auth.getHttpCredentials())
                .setApplicationName("TaskBot")
                .build();
        this.zoneId = zoneId;
    }

    public record EventSummary(String id, String title, String start, String end, String location, String description) {}

    public List<EventSummary> getUpcomingEvents(int days) throws Exception {
        ZonedDateTime now    = ZonedDateTime.now(zoneId);
        ZonedDateTime cutoff = now.plusDays(days);

        Events events = calendar.events().list("primary")
                .setMaxResults(20)
                .setTimeMin(new DateTime(now.toInstant().toEpochMilli()))
                .setTimeMax(new DateTime(cutoff.toInstant().toEpochMilli()))
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute();

        List<EventSummary> summaries = new ArrayList<>();
        if (events.getItems() == null) return summaries;
        for (Event e : events.getItems()) {
            summaries.add(new EventSummary(
                    e.getId(), e.getSummary(),
                    formatEventTime(e.getStart()),
                    formatEventTime(e.getEnd()),
                    e.getLocation(),
                    e.getDescription()));
        }
        return summaries;
    }

    public EventSummary addEvent(String title, String startDatetime, String endDatetime, String description) throws Exception {
        Event event = new Event().setSummary(title);
        if (description != null && !description.isBlank()) event.setDescription(description);

        DateTime start = parseDateTime(startDatetime);
        DateTime end   = endDatetime != null
                ? parseDateTime(endDatetime)
                : new DateTime(start.getValue() + 3_600_000L);  // default 1h

        event.setStart(new EventDateTime().setDateTime(start).setTimeZone(zoneId.getId()));
        event.setEnd(new EventDateTime().setDateTime(end).setTimeZone(zoneId.getId()));

        Event created = calendar.events().insert("primary", event).execute();
        return new EventSummary(created.getId(), created.getSummary(),
                formatEventTime(created.getStart()), formatEventTime(created.getEnd()), null, description);
    }

    public String rescheduleEvent(String eventHint, String newStartDatetime) throws Exception {
        List<EventSummary> upcoming = getUpcomingEvents(60);
        EventSummary match = upcoming.stream()
                .filter(e -> e.title() != null
                        && e.title().toLowerCase().contains(eventHint.toLowerCase()))
                .findFirst().orElse(null);

        if (match == null) return "Event not found matching: " + eventHint;

        Event event     = calendar.events().get("primary", match.id()).execute();
        DateTime newStart = parseDateTime(newStartDatetime);

        // Preserve original duration
        long durationMs = 3_600_000L;
        if (event.getStart() != null && event.getStart().getDateTime() != null
                && event.getEnd() != null && event.getEnd().getDateTime() != null) {
            durationMs = event.getEnd().getDateTime().getValue()
                    - event.getStart().getDateTime().getValue();
        }
        DateTime newEnd = new DateTime(newStart.getValue() + durationMs);

        event.setStart(new EventDateTime().setDateTime(newStart).setTimeZone(zoneId.getId()));
        event.setEnd(new EventDateTime().setDateTime(newEnd).setTimeZone(zoneId.getId()));
        calendar.events().update("primary", event.getId(), event).execute();

        return "Rescheduled \"" + match.title() + "\" to " + newStartDatetime;
    }

    private String formatEventTime(EventDateTime edt) {
        if (edt == null) return "";
        if (edt.getDateTime() != null) {
            ZonedDateTime zdt = Instant.ofEpochMilli(edt.getDateTime().getValue()).atZone(zoneId);
            return zdt.format(DateTimeFormatter.ofPattern("EEE d MMM  HH:mm"));
        }
        if (edt.getDate() != null) return edt.getDate().toString();
        return "";
    }

    private DateTime parseDateTime(String s) {
        try {
            LocalDateTime ldt = LocalDateTime.parse(s, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return new DateTime(ldt.atZone(zoneId).toInstant().toEpochMilli());
        } catch (Exception ignored) {}
        try {
            LocalDateTime ldt = LocalDateTime.parse(s, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
            return new DateTime(ldt.atZone(zoneId).toInstant().toEpochMilli());
        } catch (Exception ignored) {}
        throw new IllegalArgumentException("Cannot parse datetime: " + s);
    }
}
