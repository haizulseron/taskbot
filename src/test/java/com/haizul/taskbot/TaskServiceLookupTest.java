package com.haizul.taskbot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Tests for findTaskByTitleHint — the fuzzy match used by every Claude tool that
 *  references a task by name. The Apr 30 chat log shows "done graduation gown
 *  matters" → not found because filler words dragged the match score below
 *  threshold. Item 5 fix dropped stop words and lowered the bar; this guards it. */
class TaskServiceLookupTest {

    private Database db;
    private TaskService svc;
    private static final long USER_ID = 42L;

    @BeforeEach void setup(@TempDir Path tmp) {
        db = new Database(tmp.resolve("test.db").toString());
        db.initialize();
        svc = new TaskService(db, ZoneId.of("Asia/Singapore"), 5);
    }

    @AfterEach void teardown() { /* tmp dir auto-cleans */ }

    private void add(String title) {
        svc.createTask(USER_ID, USER_ID,
                new TaskService.AddTaskRequest(title, Task.Priority.MEDIUM, "test", null,
                        Task.Recurrence.NONE, null));
    }

    @Test void exactSubstring() {
        add("Gym workout");
        Optional<Task> t = svc.findTaskByTitleHint(USER_ID, "gym");
        assertTrue(t.isPresent());
        assertEquals("Gym workout", t.get().getTitle());
    }

    @Test void wordOrderInsensitive() {
        add("CS2030DE Mock Paper");
        Optional<Task> t = svc.findTaskByTitleHint(USER_ID, "mock cs2030de paper");
        assertTrue(t.isPresent());
    }

    /** The exact regression from the 30 Apr conversation log. */
    @Test void fillerWordTolerated_graduationGownMatters() {
        add("Graduation Gown Collection");
        Optional<Task> t = svc.findTaskByTitleHint(USER_ID, "graduation gown matters");
        assertTrue(t.isPresent(), "should match despite trailing filler word 'matters'");
    }

    @Test void shortHintSingleMatchEnough() {
        add("Submit dissertation paper");
        Optional<Task> t = svc.findTaskByTitleHint(USER_ID, "dissertation");
        assertTrue(t.isPresent());
    }

    @Test void noMatchReturnsEmpty() {
        add("Buy groceries");
        Optional<Task> t = svc.findTaskByTitleHint(USER_ID, "completely unrelated");
        assertTrue(t.isEmpty());
    }

    @Test void blankHintReturnsEmpty() {
        add("Anything");
        assertTrue(svc.findTaskByTitleHint(USER_ID, "").isEmpty());
        assertTrue(svc.findTaskByTitleHint(USER_ID, null).isEmpty());
    }

    @Test void picksBestMatchAcrossCandidates() {
        add("Plan MHM 2nd Apr");
        add("MHM 1st Apr Planning");
        add("Buy MHM tickets");
        // Hint "plan mhm 2nd apr" should pick the first one
        Optional<Task> t = svc.findTaskByTitleHint(USER_ID, "plan mhm 2nd apr");
        assertTrue(t.isPresent());
        assertEquals("Plan MHM 2nd Apr", t.get().getTitle());
    }
}
