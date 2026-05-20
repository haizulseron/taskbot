package com.haizul.taskbot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests for honestErrorReply — the helper that converts an ERROR_* tool result
 *  into a user-facing message when the phantom detector catches Claude lying. */
class HonestErrorReplyTest {

    @Test void authFailedTellsUserToReauth() {
        String reply = ClaudeService.honestErrorReply("draft_email",
                "ERROR_AUTH_FAILED: Google access token is revoked or expired.");
        assertTrue(reply.contains("/authorize"), "should mention /authorize");
        assertTrue(reply.toLowerCase().contains("expired") || reply.toLowerCase().contains("revoked"),
                "should reference token state");
    }

    @Test void notFoundSuggestsTasksList() {
        String reply = ClaudeService.honestErrorReply("mark_done",
                "ERROR_NOT_FOUND: mark_done couldn't find what was requested.");
        assertTrue(reply.contains("/tasks") || reply.toLowerCase().contains("specific"),
                "should suggest /tasks or be more specific");
    }

    @Test void badInputAsksForClarification() {
        String reply = ClaudeService.honestErrorReply("create_task",
                "ERROR_BAD_INPUT: create_task rejected the inputs.");
        assertTrue(reply.toLowerCase().contains("rephrase") || reply.toLowerCase().contains("invalid"),
                "should ask user to rephrase or note invalid input");
    }

    @Test void transientSuggestsRetry() {
        String reply = ClaudeService.honestErrorReply("send_drive_file",
                "ERROR_TRANSIENT: send_drive_file hit a temporary network/service issue.");
        assertTrue(reply.toLowerCase().contains("again") || reply.toLowerCase().contains("moment"),
                "should suggest retry");
    }

    @Test void unknownStillHonest() {
        String reply = ClaudeService.honestErrorReply("some_tool", "ERROR_UNKNOWN: ...");
        assertTrue(reply.contains("did not"), "should clearly state action did not go through");
    }

    /** All replies must apologise/note the false claim — that's the whole point of
     *  the override. Without this, phantom-detected text is too soft. */
    @Test void allRepliesAcknowledgeMistake() {
        for (String cat : new String[]{"ERROR_AUTH_FAILED", "ERROR_NOT_FOUND",
                "ERROR_BAD_INPUT", "ERROR_TRANSIENT", "ERROR_UNKNOWN"}) {
            String reply = ClaudeService.honestErrorReply("test", cat + ": x");
            assertTrue(reply.toLowerCase().contains("sorry") || reply.toLowerCase().contains("did not"),
                    "reply for " + cat + " should acknowledge mistake. got: " + reply);
        }
    }
}
