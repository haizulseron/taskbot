package com.haizul.taskbot;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class UserSettings {
    private final long userId;
    private final String quietStart;
    private final String quietEnd;
    private final boolean weeklyDigest;
    private final String pomodoroState; // "POMODORO:rounds:workMins:breakMins:currentRound:phase" or null

    public UserSettings(long userId, String quietStart, String quietEnd,
                        boolean weeklyDigest, String pomodoroState) {
        this.userId        = userId;
        this.quietStart    = quietStart;
        this.quietEnd      = quietEnd;
        this.weeklyDigest  = weeklyDigest;
        this.pomodoroState = pomodoroState;
    }

    public long getUserId()          { return userId; }
    public String getQuietStart()    { return quietStart; }
    public String getQuietEnd()      { return quietEnd; }
    public boolean isWeeklyDigest()  { return weeklyDigest; }
    public String getPomodoroState() { return pomodoroState; }

    public boolean isQuietHour(LocalTime now) {
        if (quietStart == null || quietEnd == null) return false;
        try {
            LocalTime start = LocalTime.parse(quietStart, DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime end   = LocalTime.parse(quietEnd,   DateTimeFormatter.ofPattern("HH:mm"));
            if (start.isBefore(end)) return !now.isBefore(start) && now.isBefore(end);
            else return !now.isBefore(start) || now.isBefore(end);
        } catch (Exception e) { return false; }
    }
}
