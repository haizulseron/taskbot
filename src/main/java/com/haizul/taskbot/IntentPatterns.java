package com.haizul.taskbot;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure regex-based intent recognition that runs in Java *before* a message reaches Claude.
 * Catching common task-completion patterns here is dramatically more reliable than
 * delegating to the LLM — instant, deterministic, and never hallucinated.
 *
 * Covered:
 *   • {@link #extractMarkDoneHint(String)}   — "mark X done", "complete X", "done with X", "X is done"
 *   • {@link #extractBulkDoneFilter(String)} — "complete all daily", "done daily tasks", "mark everything done"
 *
 * Both are pure functions (no I/O, no state). Locked down by {@code TaskBotPatternsTest}.
 */
public final class IntentPatterns {

    private IntentPatterns() {}

    private static final Pattern MARK_X_DONE = Pattern.compile(
            "^mark\\s+(.+?)\\s+(?:as\\s+)?done\\s*$");
    // "done with X" MUST be tried before generic "done X" — otherwise the latter
    // captures "with X" instead of "X". (Caught by HonestErrorReplyTest peer.)
    private static final Pattern DONE_WITH_X = Pattern.compile(
            "^(?:i'?m\\s+|i\\s+am\\s+)?done\\s+with\\s+(.+?)\\s*$");
    private static final Pattern VERB_X = Pattern.compile(
            "^(?:complete|done|finish|finished|completed)\\s+(.+?)\\s*$");
    private static final Pattern X_IS_DONE = Pattern.compile(
            "^(.+?)\\s+(?:is\\s+)?done\\s*$");

    private static final Pattern BULK_PRIORITY_FILTERED_VERB_FIRST = Pattern.compile(
            "^(?:complete|done|finish|mark)\\s+(?:(?:all|both|every|my|the)\\s+)*(daily|high|medium|low)"
          + "(?:\\s+tasks?)?(?:\\s+(?:as\\s+)?done)?\\s*$");
    private static final Pattern BULK_PRIORITY_FILTERED_MARK_TRAILING = Pattern.compile(
            "^mark\\s+(?:(?:all|both|every|my|the)\\s+)*(daily|high|medium|low)(?:\\s+tasks?)?"
          + "\\s+(?:as\\s+)?done\\s*$");
    private static final Pattern BULK_TOTAL = Pattern.compile(
            "^(?:complete|done|finish|mark)\\s+(?:all(?:\\s+(?:my|the))?(?:\\s+tasks?)?|everything)"
          + "(?:\\s+(?:as\\s+)?done)?\\s*$");

    /**
     * Extract a task hint from "mark X done" / "complete X" / "done with X" / "X is done".
     * Returns null if no pattern matches or the hint is too generic ("it", "that", etc.).
     */
    public static String extractMarkDoneHint(String lower) {
        Matcher m;
        if ((m = MARK_X_DONE.matcher(lower)).find())   return clean(m.group(1));
        if ((m = DONE_WITH_X.matcher(lower)).find())   return clean(m.group(1));
        if ((m = VERB_X.matcher(lower)).find())        return clean(m.group(1));
        if ((m = X_IS_DONE.matcher(lower)).find()) {
            String hint = m.group(1).trim();
            if (!hint.matches("(?i)it|that|this|what|everything|all|nothing")) return clean(hint);
        }
        return null;
    }

    /**
     * Detect a bulk-completion intent. Returns the priority filter ("daily", "high",
     * "medium", "low"), "all" for total bulk, or null if not a bulk pattern.
     */
    public static String extractBulkDoneFilter(String lower) {
        Matcher m;
        if ((m = BULK_PRIORITY_FILTERED_VERB_FIRST.matcher(lower)).find())     return m.group(1);
        if ((m = BULK_PRIORITY_FILTERED_MARK_TRAILING.matcher(lower)).find())  return m.group(1);
        if ((m = BULK_TOTAL.matcher(lower)).find())                            return "all";
        return null;
    }

    static String clean(String hint) {
        return hint.replaceAll("(?i)^(my|the|a|task|tasks)\\s+", "")
                   .replaceAll("(?i)\\s+(task|tasks)$", "")
                   .trim();
    }
}
