package com.haizul.taskbot;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

public class UserSettings {
    private final long userId;
    private final String quietStart;  // "HH:mm" or null
    private final String quietEnd;    // "HH:mm" or null
    private final boolean weeklyDigest;

    public UserSettings(long userId, String quietStart, String quietEnd, boolean weeklyDigest) {
        this.userId       = userId;
        this.quietStart   = quietStart;
        this.quietEnd     = quietEnd;
        this.weeklyDigest = weeklyDigest;
    }

    public long getUserId()        { return userId; }
    public String getQuietStart()  { return quietStart; }
    public String getQuietEnd()    { return quietEnd; }
    public boolean isWeeklyDigest(){ return weeklyDigest; }

    /** Returns true if the given time falls within the quiet window. */
    public boolean isQuietHour(LocalTime now) {
        if (quietStart == null || quietEnd == null) return false;
        try {
            LocalTime start = LocalTime.parse(quietStart, DateTimeFormatter.ofPattern("HH:mm"));
            LocalTime end   = LocalTime.parse(quietEnd,   DateTimeFormatter.ofPattern("HH:mm"));
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            } else {
                // Spans midnight e.g. 22:00 – 07:00
                return !now.isBefore(start) || now.isBefore(end);
            }
        } catch (Exception e) {
            return false;
        }
    }
}
