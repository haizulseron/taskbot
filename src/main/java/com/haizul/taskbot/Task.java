package com.haizul.taskbot;

import java.time.LocalDateTime;

public class Task {
    public enum Priority {
        LOW, MEDIUM, HIGH;
        public static Priority fromText(String value) {
            if (value == null || value.isBlank()) return MEDIUM;
            try { return Priority.valueOf(value.trim().toUpperCase()); } catch (Exception e) { return MEDIUM; }
        }
    }

    public enum Status {
        ACTIVE, DONE, DELETED;
        public static Status fromText(String value) { return Status.valueOf(value.trim().toUpperCase()); }
    }

    public enum Recurrence {
        NONE, DAILY, WEEKLY, MONTHLY;
        public static Recurrence fromText(String value) {
            if (value == null || value.isBlank()) return NONE;
            try { return Recurrence.valueOf(value.trim().toUpperCase()); } catch (Exception e) { return NONE; }
        }
    }

    private String id;
    private long userId;
    private long chatId;
    private String title;
    private String notes;
    private Priority priority;
    private String category;
    private LocalDateTime dueAt;
    private Status status;
    private Recurrence recurrence;
    private int staleAfterDays;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer reminderStage;
    private LocalDateTime lastReminderAt;
    private LocalDateTime staleNotifiedAt;
    private Integer reminderIntervalMinutes;
    private boolean repeatReminder;
    private boolean habit;
    private int reminderIgnoredCount;
    private Double reminderLat;
    private Double reminderLng;
    private Integer reminderRadiusMeters;

    public String getId()                              { return id; }
    public void setId(String id)                       { this.id = id; }
    public long getUserId()                            { return userId; }
    public void setUserId(long userId)                 { this.userId = userId; }
    public long getChatId()                            { return chatId; }
    public void setChatId(long chatId)                 { this.chatId = chatId; }
    public String getTitle()                           { return title; }
    public void setTitle(String title)                 { this.title = title; }
    public String getNotes()                           { return notes; }
    public void setNotes(String notes)                 { this.notes = notes; }
    public Priority getPriority()                      { return priority; }
    public void setPriority(Priority priority)         { this.priority = priority; }
    public String getCategory()                        { return category; }
    public void setCategory(String category)           { this.category = category; }
    public LocalDateTime getDueAt()                    { return dueAt; }
    public void setDueAt(LocalDateTime dueAt)          { this.dueAt = dueAt; }
    public Status getStatus()                          { return status; }
    public void setStatus(Status status)               { this.status = status; }
    public Recurrence getRecurrence()                  { return recurrence; }
    public void setRecurrence(Recurrence r)            { this.recurrence = r; }
    public int getStaleAfterDays()                     { return staleAfterDays; }
    public void setStaleAfterDays(int d)               { this.staleAfterDays = d; }
    public LocalDateTime getCreatedAt()                { return createdAt; }
    public void setCreatedAt(LocalDateTime t)          { this.createdAt = t; }
    public LocalDateTime getUpdatedAt()                { return updatedAt; }
    public void setUpdatedAt(LocalDateTime t)          { this.updatedAt = t; }
    public Integer getReminderStage()                  { return reminderStage; }
    public void setReminderStage(Integer s)            { this.reminderStage = s; }
    public LocalDateTime getLastReminderAt()           { return lastReminderAt; }
    public void setLastReminderAt(LocalDateTime t)     { this.lastReminderAt = t; }
    public LocalDateTime getStaleNotifiedAt()          { return staleNotifiedAt; }
    public void setStaleNotifiedAt(LocalDateTime t)    { this.staleNotifiedAt = t; }
    public Integer getReminderIntervalMinutes()        { return reminderIntervalMinutes; }
    public void setReminderIntervalMinutes(Integer m)  { this.reminderIntervalMinutes = m; }
    public boolean isRepeatReminder()                  { return repeatReminder; }
    public void setRepeatReminder(boolean b)           { this.repeatReminder = b; }
    public boolean isHabit()                           { return habit; }
    public void setHabit(boolean h)                    { this.habit = h; }
    public int getReminderIgnoredCount()               { return reminderIgnoredCount; }
    public void setReminderIgnoredCount(int c)         { this.reminderIgnoredCount = c; }
    public Double getReminderLat()                     { return reminderLat; }
    public void setReminderLat(Double lat)             { this.reminderLat = lat; }
    public Double getReminderLng()                     { return reminderLng; }
    public void setReminderLng(Double lng)             { this.reminderLng = lng; }
    public Integer getReminderRadiusMeters()           { return reminderRadiusMeters; }
    public void setReminderRadiusMeters(Integer r)     { this.reminderRadiusMeters = r; }

    public String shortId() {
        if (id == null) return "";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    public int effectiveReminderIntervalMinutes() {
        if (reminderIntervalMinutes != null && reminderIntervalMinutes > 0) return reminderIntervalMinutes;
        return switch (priority) { case HIGH -> 60; case MEDIUM -> 120; case LOW -> 360; };
    }

    public boolean hasLocationReminder() {
        return reminderLat != null && reminderLng != null;
    }

    /** Haversine distance in metres between this task's location and a given point */
    public double distanceMetersTo(double lat, double lng) {
        double R = 6371000;
        double dLat = Math.toRadians(lat - reminderLat);
        double dLng = Math.toRadians(lng - reminderLng);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(reminderLat)) * Math.cos(Math.toRadians(lat))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
