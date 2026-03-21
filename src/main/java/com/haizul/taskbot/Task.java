package com.haizul.taskbot;

import java.time.LocalDateTime;

public class Task {
    public enum Priority {
        LOW,
        MEDIUM,
        HIGH;

        public static Priority fromText(String value) {
            if (value == null || value.isBlank()) {
                return MEDIUM;
            }
            return Priority.valueOf(value.trim().toUpperCase());
        }
    }

    public enum Status {
        ACTIVE,
        DONE,
        DELETED;

        public static Status fromText(String value) {
            return Status.valueOf(value.trim().toUpperCase());
        }
    }

    public enum Recurrence {
        NONE,
        DAILY,
        WEEKLY,
        MONTHLY;

        public static Recurrence fromText(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            return Recurrence.valueOf(value.trim().toUpperCase());
        }
    }

    private String id;
    private long userId;
    private long chatId;
    private String title;
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

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public long getChatId() {
        return chatId;
    }

    public void setChatId(long chatId) {
        this.chatId = chatId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public LocalDateTime getDueAt() {
        return dueAt;
    }

    public void setDueAt(LocalDateTime dueAt) {
        this.dueAt = dueAt;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Recurrence getRecurrence() {
        return recurrence;
    }

    public void setRecurrence(Recurrence recurrence) {
        this.recurrence = recurrence;
    }

    public int getStaleAfterDays() {
        return staleAfterDays;
    }

    public void setStaleAfterDays(int staleAfterDays) {
        this.staleAfterDays = staleAfterDays;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getReminderStage() {
        return reminderStage;
    }

    public void setReminderStage(Integer reminderStage) {
        this.reminderStage = reminderStage;
    }

    public LocalDateTime getLastReminderAt() {
        return lastReminderAt;
    }

    public void setLastReminderAt(LocalDateTime lastReminderAt) {
        this.lastReminderAt = lastReminderAt;
    }

    public LocalDateTime getStaleNotifiedAt() {
        return staleNotifiedAt;
    }

    public void setStaleNotifiedAt(LocalDateTime staleNotifiedAt) {
        this.staleNotifiedAt = staleNotifiedAt;
    }

    public String shortId() {
        if (id == null) {
            return "";
        }
        return id.length() <= 8 ? id : id.substring(0, 8);
    }
}
