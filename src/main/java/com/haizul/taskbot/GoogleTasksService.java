package com.haizul.taskbot;

import com.google.api.client.util.DateTime;
import com.google.api.services.tasks.Tasks;
import com.google.api.services.tasks.model.Task;
import com.google.api.services.tasks.model.TaskList;
import com.google.api.services.tasks.model.TaskLists;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GoogleTasksService {

    public record GoogleTaskItem(String taskId, String taskListId, String listName,
                                 String title, String notes, String due) {}

    public record CreatedGoogleTask(String taskId, String taskListId) {}

    private static final String[] PRIORITY_LIST_NAMES = {
            "High Priority", "Medium Priority", "Low Priority", "Daily"
    };

    private final Tasks tasksClient;
    private final Map<String, String> listIdCache = new HashMap<>();

    public GoogleTasksService(GoogleAuthService auth) {
        this.tasksClient = new Tasks.Builder(
                auth.getHttpTransport(),
                auth.getJsonFactory(),
                auth.getHttpCredentials())
                .setApplicationName("TaskBot")
                .build();
    }

    /**
     * Finds an existing task list by title, or creates one. Caches list IDs.
     */
    public TaskList getOrCreateTaskList(String listName) {
        try {
            // Check cache first
            if (listIdCache.containsKey(listName)) {
                TaskList cached = new TaskList();
                cached.setId(listIdCache.get(listName));
                cached.setTitle(listName);
                return cached;
            }

            // Search existing lists
            TaskLists result = tasksClient.tasklists().list().setMaxResults(100).execute();
            if (result.getItems() != null) {
                for (TaskList tl : result.getItems()) {
                    if (listName.equals(tl.getTitle())) {
                        listIdCache.put(listName, tl.getId());
                        return tl;
                    }
                }
            }

            // Not found — create it
            TaskList newList = new TaskList();
            newList.setTitle(listName);
            TaskList created = tasksClient.tasklists().insert(newList).execute();
            listIdCache.put(listName, created.getId());
            return created;
        } catch (Exception e) {
            System.err.println("Failed to get/create task list '" + listName + "': " + e.getMessage());
            throw new RuntimeException("Failed to get/create task list: " + listName, e);
        }
    }

    /**
     * Creates a task in the named list. Returns the created Task's ID.
     *
     * @param title    task title
     * @param notes    optional notes (may be null)
     * @param dueDate  ISO date "yyyy-MM-dd" or null
     * @param listName the task list name (e.g. "High Priority")
     * @return the Google Tasks ID of the created task
     */
    public CreatedGoogleTask createGoogleTask(String title, String notes, String dueDate, String listName) {
        try {
            TaskList taskList = getOrCreateTaskList(listName);

            Task task = new Task();
            task.setTitle(title);
            if (notes != null && !notes.isBlank()) {
                task.setNotes(notes);
            }
            if (dueDate != null && !dueDate.isBlank()) {
                task.setDue(new DateTime(dueDate + "T00:00:00.000Z").toStringRfc3339());
            }

            Task created = tasksClient.tasks().insert(taskList.getId(), task).execute();
            return new CreatedGoogleTask(created.getId(), taskList.getId());
        } catch (Exception e) {
            System.err.println("Failed to create Google Task '" + title + "': " + e.getMessage());
            throw new RuntimeException("Failed to create Google Task: " + title, e);
        }
    }

    /**
     * Updates a task's title, notes, and/or due date in Google Tasks.
     */
    public void updateGoogleTask(String taskId, String taskListId,
                                  String newTitle, String newNotes, String newDueDate) {
        try {
            Task task = tasksClient.tasks().get(taskListId, taskId).execute();
            if (newTitle != null && !newTitle.isBlank()) task.setTitle(newTitle);
            if (newNotes != null) task.setNotes(newNotes.isBlank() ? null : newNotes);
            if (newDueDate != null && !newDueDate.isBlank()) {
                task.setDue(new DateTime(newDueDate + "T00:00:00.000Z").toStringRfc3339());
            }
            tasksClient.tasks().update(taskListId, taskId, task).execute();
        } catch (Exception e) {
            System.err.println("Failed to update Google Task " + taskId + ": " + e.getMessage());
        }
    }

    /**
     * Marks a task as completed.
     */
    public void completeGoogleTask(String taskId, String taskListId) {
        try {
            Task task = tasksClient.tasks().get(taskListId, taskId).execute();
            task.setStatus("completed");
            tasksClient.tasks().update(taskListId, taskId, task).execute();
        } catch (Exception e) {
            System.err.println("Failed to complete Google Task " + taskId + ": " + e.getMessage());
            throw new RuntimeException("Failed to complete Google Task: " + taskId, e);
        }
    }

    /**
     * Deletes a task.
     */
    public void deleteGoogleTask(String taskId, String taskListId) {
        try {
            tasksClient.tasks().delete(taskListId, taskId).execute();
        } catch (Exception e) {
            System.err.println("Failed to delete Google Task " + taskId + ": " + e.getMessage());
            throw new RuntimeException("Failed to delete Google Task: " + taskId, e);
        }
    }

    /**
     * Maps a priority string to the corresponding Google Tasks list name.
     */
    public String listNameForPriority(String priority) {
        if (priority == null) return "Medium Priority";
        return switch (priority.toUpperCase()) {
            case "HIGH"   -> "High Priority";
            case "MEDIUM" -> "Medium Priority";
            case "LOW"    -> "Low Priority";
            case "DAILY"  -> "Daily";
            default       -> "Medium Priority";
        };
    }

    /**
     * Fetches recently completed tasks across all priority lists.
     * Returns task IDs that have status "completed".
     */
    public List<GoogleTaskItem> fetchRecentlyCompleted() {
        List<GoogleTaskItem> completed = new ArrayList<>();
        for (String listName : PRIORITY_LIST_NAMES) {
            try {
                TaskList taskList = getOrCreateTaskList(listName);
                String listId = taskList.getId();

                com.google.api.services.tasks.model.Tasks result = tasksClient.tasks()
                        .list(listId)
                        .setShowCompleted(true)
                        .setShowHidden(true)
                        .setMaxResults(100)
                        .execute();

                if (result.getItems() != null) {
                    for (Task t : result.getItems()) {
                        if ("completed".equals(t.getStatus())) {
                            completed.add(new GoogleTaskItem(
                                    t.getId(), listId, listName,
                                    t.getTitle(), t.getNotes(), t.getDue()));
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch completed tasks from '" + listName + "': " + e.getMessage());
            }
        }
        return completed;
    }

    /**
     * Fetches all incomplete tasks across all 4 priority lists.
     */
    public List<GoogleTaskItem> fetchAllIncompleteTasks() {
        List<GoogleTaskItem> allTasks = new ArrayList<>();
        for (String listName : PRIORITY_LIST_NAMES) {
            try {
                TaskList taskList = getOrCreateTaskList(listName);
                String listId = taskList.getId();

                com.google.api.services.tasks.model.Tasks result = tasksClient.tasks()
                        .list(listId)
                        .setShowCompleted(false)
                        .setMaxResults(100)
                        .execute();

                if (result.getItems() != null) {
                    for (Task t : result.getItems()) {
                        allTasks.add(new GoogleTaskItem(
                                t.getId(),
                                listId,
                                listName,
                                t.getTitle(),
                                t.getNotes(),
                                t.getDue()));
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to fetch tasks from '" + listName + "': " + e.getMessage());
                throw new RuntimeException("Failed to fetch tasks from list: " + listName, e);
            }
        }
        return allTasks;
    }

    /**
     * Fetches tasks grouped by list name for the /gtasks command display.
     * Each list's tasks are sorted by due date (nulls last).
     */
    public Map<String, List<GoogleTaskItem>> fetchTasksForDisplay() {
        Map<String, List<GoogleTaskItem>> grouped = new LinkedHashMap<>();
        for (String listName : PRIORITY_LIST_NAMES) {
            try {
                TaskList taskList = getOrCreateTaskList(listName);
                String listId = taskList.getId();

                com.google.api.services.tasks.model.Tasks result = tasksClient.tasks()
                        .list(listId)
                        .setShowCompleted(false)
                        .setMaxResults(100)
                        .execute();

                List<GoogleTaskItem> items = new ArrayList<>();
                if (result.getItems() != null) {
                    for (Task t : result.getItems()) {
                        items.add(new GoogleTaskItem(
                                t.getId(),
                                listId,
                                listName,
                                t.getTitle(),
                                t.getNotes(),
                                t.getDue()));
                    }
                }

                // Sort by due date, nulls last
                items.sort((a, b) -> {
                    if (a.due() == null && b.due() == null) return 0;
                    if (a.due() == null) return 1;
                    if (b.due() == null) return -1;
                    return a.due().compareTo(b.due());
                });

                grouped.put(listName, items);
            } catch (Exception e) {
                System.err.println("Failed to fetch tasks for display from '" + listName + "': " + e.getMessage());
                throw new RuntimeException("Failed to fetch tasks for display: " + listName, e);
            }
        }
        return grouped;
    }
}
