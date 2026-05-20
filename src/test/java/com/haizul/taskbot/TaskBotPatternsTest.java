package com.haizul.taskbot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for the Java pre-process intent regexes that catch task completion before
 *  the message reaches Claude. These are critical reliability code — when they break,
 *  every "done X" / "complete all daily" silently falls through to Claude, who
 *  hallucinates success. Lock the behavior down. */
class TaskBotPatternsTest {

    // ── extractBulkDoneFilter ──────────────────────────────────────────────

    @Test void bulk_complete_all_daily() {
        assertEquals("daily", IntentPatterns.extractBulkDoneFilter("complete all daily tasks"));
    }
    @Test void bulk_done_both_daily() {
        assertEquals("daily", IntentPatterns.extractBulkDoneFilter("done both daily"));
    }
    @Test void bulk_finish_all_high() {
        assertEquals("high", IntentPatterns.extractBulkDoneFilter("finish all high tasks"));
    }
    @Test void bulk_mark_all_medium_done() {
        assertEquals("medium", IntentPatterns.extractBulkDoneFilter("mark all medium done"));
    }

    // Item 5: quantifier is now optional — these used to silently fall through
    @Test void bulk_done_daily_tasks_no_quantifier() {
        assertEquals("daily", IntentPatterns.extractBulkDoneFilter("done daily tasks"));
    }
    @Test void bulk_complete_daily_no_quantifier() {
        assertEquals("daily", IntentPatterns.extractBulkDoneFilter("complete daily"));
    }
    @Test void bulk_finish_high_tasks() {
        assertEquals("high", IntentPatterns.extractBulkDoneFilter("finish high tasks"));
    }
    @Test void bulk_mark_medium_done_no_quantifier() {
        assertEquals("medium", IntentPatterns.extractBulkDoneFilter("mark medium done"));
    }
    @Test void bulk_done_my_daily() {
        assertEquals("daily", IntentPatterns.extractBulkDoneFilter("done my daily"));
    }

    // Total bulk
    @Test void bulk_complete_all_tasks() {
        assertEquals("all", IntentPatterns.extractBulkDoneFilter("complete all tasks"));
    }
    @Test void bulk_done_everything() {
        assertEquals("all", IntentPatterns.extractBulkDoneFilter("done everything"));
    }
    @Test void bulk_mark_all_done() {
        assertEquals("all", IntentPatterns.extractBulkDoneFilter("mark all done"));
    }

    // Negative cases — must NOT match (would otherwise nuke active tasks)
    @Test void bulk_no_match_done_x() {
        assertNull(IntentPatterns.extractBulkDoneFilter("done gym"));
    }
    @Test void bulk_no_match_arbitrary() {
        assertNull(IntentPatterns.extractBulkDoneFilter("can you list my tasks"));
    }
    @Test void bulk_no_match_just_word() {
        assertNull(IntentPatterns.extractBulkDoneFilter("done"));
    }

    // ── extractMarkDoneHint ────────────────────────────────────────────────

    @Test void done_mark_x_done() {
        assertEquals("gym", IntentPatterns.extractMarkDoneHint("mark gym done"));
    }
    @Test void done_mark_x_as_done() {
        assertEquals("gym", IntentPatterns.extractMarkDoneHint("mark gym as done"));
    }
    @Test void done_complete_x() {
        assertEquals("gym", IntentPatterns.extractMarkDoneHint("complete gym"));
    }
    @Test void done_finish_x() {
        assertEquals("gym workout", IntentPatterns.extractMarkDoneHint("finish gym workout"));
    }
    @Test void done_done_with_x() {
        assertEquals("gym", IntentPatterns.extractMarkDoneHint("done with gym"));
    }
    @Test void done_im_done_with_x() {
        assertEquals("gym", IntentPatterns.extractMarkDoneHint("i'm done with gym"));
    }
    @Test void done_x_is_done() {
        assertEquals("gym", IntentPatterns.extractMarkDoneHint("gym is done"));
    }

    // Negative cases — these would falsely match common phrases
    @Test void done_no_match_it_is_done() {
        assertNull(IntentPatterns.extractMarkDoneHint("it is done"));
    }
    @Test void done_no_match_done_alone() {
        assertNull(IntentPatterns.extractMarkDoneHint("done"));
    }
}
