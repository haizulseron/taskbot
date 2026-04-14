package com.haizul.taskbot;

import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.*;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class GoogleCalendarService {

    private static final String CALENDAR_ID = "primary";
    private static final String SOURCE_TAG = "Source: Taskbot";
    private static final List<String> FOCUS_KEYWORDS = List.of(
            "focus", "meeting", "interview", "exam", "block"
    );

    private final Calendar calendar;
    private final ZoneId zoneId;

    public record CalendarEvent(
            String id,
            String title,
            String startDate,
            String endDate,
            String description,
            boolean isAllDay
    ) {}

    public GoogleCalendarService(GoogleAuthService auth, ZoneId zoneId) throws Exception {
        this.calendar = new Calendar.Builder(
                auth.getHttpTransport(),
                auth.getJsonFactory(),
                auth.getHttpCredentials())
                .setApplicationName("TaskBot")
                .build();
        this.zoneId = zoneId;
    }

    /**
     * Creates an all-day event on the due date for a task.
     * Returns the created event ID.
     */
    public String createEventForTask(String title, String dueDate, String description, String priority) throws Exception {
        Event event = new Event().setSummary(title);

        String body = (description != null && !description.isBlank() ? description : "")
                + "\nPriority: " + priority
                + "\n" + SOURCE_TAG;
        event.setDescription(body.strip());

        // All-day event: start = dueDate, end = dueDate + 1
        String startDate = dueDate.length() >= 10 ? dueDate.substring(0, 10) : dueDate;
        String endDate = LocalDate.parse(startDate).plusDays(1).toString();
        event.setStart(new EventDateTime().setDate(new DateTime(startDate)));
        event.setEnd(new EventDateTime().setDate(new DateTime(endDate)));

        Event created = calendar.events().insert(CALENDAR_ID, event).execute();
        return created.getId();
    }

    /**
     * Updates an existing calendar event with new task info.
     */
    public void updateEventForTask(String eventId, String title, String dueDate, String description, String priority) throws Exception {
        Event event = calendar.events().get(CALENDAR_ID, eventId).execute();

        event.setSummary(title);

        String body = (description != null && !description.isBlank() ? description : "")
                + "\nPriority: " + priority
                + "\n" + SOURCE_TAG;
        event.setDescription(body.strip());

        String startDate = dueDate.length() >= 10 ? dueDate.substring(0, 10) : dueDate;
        String endDate = LocalDate.parse(startDate).plusDays(1).toString();
        event.setStart(new EventDateTime().setDate(new DateTime(startDate)));
        event.setEnd(new EventDateTime().setDate(new DateTime(endDate)));

        calendar.events().update(CALENDAR_ID, eventId, event).execute();
    }

    /**
     * Deletes an event by ID. Swallows NOT_FOUND errors gracefully.
     */
    public void deleteEvent(String eventId) {
        try {
            calendar.events().delete(CALENDAR_ID, eventId).execute();
        } catch (com.google.api.client.googleapis.json.GoogleJsonResponseException e) {
            if (e.getStatusCode() != 404 && e.getStatusCode() != 410) {
                throw new RuntimeException("Failed to delete calendar event: " + eventId, e);
            }
            // 404 Not Found or 410 Gone — event already deleted, swallow silently
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete calendar event: " + eventId, e);
        }
    }

    /**
     * Fetches upcoming events for N days from primary calendar.
     * maxResults capped at 100 for sync.
     */
    public List<CalendarEvent> getEventsForSync(int days) throws Exception {
        ZonedDateTime now = ZonedDateTime.now(zoneId);
        ZonedDateTime cutoff = now.plusDays(days);

        Events events = calendar.events().list(CALENDAR_ID)
                .setMaxResults(100)
                .setTimeMin(new DateTime(now.toInstant().toEpochMilli()))
                .setTimeMax(new DateTime(cutoff.toInstant().toEpochMilli()))
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute();

        return toCalendarEvents(events);
    }

    /**
     * Finds events created externally (not by Taskbot and not already known).
     * Filters out events with "Source: Taskbot" in description and events whose ID is in knownEventIds.
     */
    public List<CalendarEvent> findNewExternalEvents(int days, Set<String> knownEventIds) throws Exception {
        List<CalendarEvent> all = getEventsForSync(days);
        return all.stream()
                .filter(e -> !knownEventIds.contains(e.id()))
                .filter(e -> e.description() == null || !e.description().contains(SOURCE_TAG))
                .collect(Collectors.toList());
    }

    /**
     * Checks if right now there is an ongoing event whose title contains
     * any of: "focus", "meeting", "interview", "exam", "block" (case-insensitive).
     */
    public boolean isInFocusBlock() throws Exception {
        Instant now = Instant.now();
        DateTime nowDt = new DateTime(now.toEpochMilli());

        Events events = calendar.events().list(CALENDAR_ID)
                .setTimeMin(nowDt)
                .setTimeMax(nowDt)
                .setSingleEvents(true)
                .execute();

        if (events.getItems() == null || events.getItems().isEmpty()) {
            return false;
        }

        for (Event event : events.getItems()) {
            String title = event.getSummary();
            if (title == null) continue;
            String lower = title.toLowerCase();
            for (String keyword : FOCUS_KEYWORDS) {
                if (lower.contains(keyword)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns events for today only (start of day to end of day in the configured timezone).
     */
    public List<CalendarEvent> getTodayEvents() throws Exception {
        LocalDate today = LocalDate.now(zoneId);
        ZonedDateTime startOfDay = today.atStartOfDay(zoneId);
        ZonedDateTime endOfDay = today.plusDays(1).atStartOfDay(zoneId);

        Events events = calendar.events().list(CALENDAR_ID)
                .setMaxResults(100)
                .setTimeMin(new DateTime(startOfDay.toInstant().toEpochMilli()))
                .setTimeMax(new DateTime(endOfDay.toInstant().toEpochMilli()))
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute();

        return toCalendarEvents(events);
    }

    /**
     * Returns events for N days ahead (for time blocking suggestions).
     */
    public List<CalendarEvent> getEventsForTimeBlocking(int days) throws Exception {
        return getEventsForSync(days);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private List<CalendarEvent> toCalendarEvents(Events events) {
        List<CalendarEvent> result = new ArrayList<>();
        if (events.getItems() == null) return result;

        for (Event e : events.getItems()) {
            boolean allDay = e.getStart() != null && e.getStart().getDate() != null;
            result.add(new CalendarEvent(
                    e.getId(),
                    e.getSummary(),
                    formatEventTime(e.getStart(), allDay),
                    formatEventTime(e.getEnd(), allDay),
                    e.getDescription(),
                    allDay
            ));
        }
        return result;
    }

    private String formatEventTime(EventDateTime edt, boolean allDay) {
        if (edt == null) return "";
        if (allDay && edt.getDate() != null) {
            return edt.getDate().toString();
        }
        if (edt.getDateTime() != null) {
            ZonedDateTime zdt = Instant.ofEpochMilli(edt.getDateTime().getValue()).atZone(zoneId);
            return zdt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        if (edt.getDate() != null) {
            return edt.getDate().toString();
        }
        return "";
    }
}
