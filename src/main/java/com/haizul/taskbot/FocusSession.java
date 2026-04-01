package com.haizul.taskbot;

import java.time.LocalDateTime;

public class FocusSession {
    private final int id;
    private final long userId;
    private final long chatId;
    private final String taskTitle;
    private final int durationMinutes;
    private final LocalDateTime startedAt;
    private final LocalDateTime endsAt;
    private final boolean completed;
    private final boolean notified;

    public FocusSession(int id, long userId, long chatId, String taskTitle,
                        int durationMinutes, LocalDateTime startedAt, LocalDateTime endsAt,
                        boolean completed, boolean notified) {
        this.id              = id;
        this.userId          = userId;
        this.chatId          = chatId;
        this.taskTitle       = taskTitle;
        this.durationMinutes = durationMinutes;
        this.startedAt       = startedAt;
        this.endsAt          = endsAt;
        this.completed       = completed;
        this.notified        = notified;
    }

    public int getId()                   { return id; }
    public long getUserId()              { return userId; }
    public long getChatId()              { return chatId; }
    public String getTaskTitle()         { return taskTitle; }
    public int getDurationMinutes()      { return durationMinutes; }
    public LocalDateTime getStartedAt()  { return startedAt; }
    public LocalDateTime getEndsAt()     { return endsAt; }
    public boolean isCompleted()         { return completed; }
    public boolean isNotified()          { return notified; }
}
