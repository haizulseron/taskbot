package com.haizul.taskbot;

public class Template {
    private final int id;
    private final long userId;
    private final String name;
    private final String title;
    private final Task.Priority priority;
    private final String category;
    private final Task.Recurrence recurrence;
    private final String notes;

    public Template(int id, long userId, String name, String title,
                    Task.Priority priority, String category,
                    Task.Recurrence recurrence, String notes) {
        this.id         = id;
        this.userId     = userId;
        this.name       = name;
        this.title      = title;
        this.priority   = priority;
        this.category   = category;
        this.recurrence = recurrence;
        this.notes      = notes;
    }

    public int getId()                   { return id; }
    public long getUserId()              { return userId; }
    public String getName()              { return name; }
    public String getTitle()             { return title; }
    public Task.Priority getPriority()   { return priority; }
    public String getCategory()          { return category; }
    public Task.Recurrence getRecurrence(){ return recurrence; }
    public String getNotes()             { return notes; }
}
