package com.haizul.taskbot;

/**
 * In-memory file the user has queued (via Telegram) for the next email draft.
 * Lives outside any specific email backend so the queue survives integration
 * swaps (e.g. moving Gmail behind Composio).
 */
public record Attachment(String filename, byte[] data) {}
