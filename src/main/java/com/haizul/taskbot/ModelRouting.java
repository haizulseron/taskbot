package com.haizul.taskbot;

/**
 * Per-workload model routing. Single source of truth — every LLM call site in
 * taskbot picks its model from one of these constants, not a hardcoded literal.
 *
 * Mirrors {@code routing_architecture.md} (2026-05-20). Foreground stays in
 * Anthropic (Sonnet for chat/reasoning/agentic, Opus for coding); background
 * uses cheap models (Haiku for Anthropic-family consistency, Kimi K2.6 for
 * highest-volume slots).
 *
 * Slots without a backing feature in taskbot are still defined here so the
 * routing config is complete — wiring those features (Heartbeat loop,
 * Subconscious scoring, vector embeddings, code-gen tool) is separate work.
 */
public final class ModelRouting {

    private ModelRouting() {}

    // ── Foreground (user-facing, quality matters) ──────────────────────────

    /** Direct conversational back-and-forth. */
    public static final String CHAT      = "claude-sonnet-4-6";

    /** Main chat agent, meeting summarizer, multi-turn reasoning. */
    public static final String REASONING = "claude-sonnet-4-6";

    /** Sub-agent runners, tool loops, decisions. */
    public static final String AGENTIC   = "claude-sonnet-4-6";

    /** Code generation + refactor passes. No backing feature yet in taskbot. */
    public static final String CODING    = "claude-opus-4-7";

    // ── Background (high-frequency, cost-sensitive) ────────────────────────

    /** Tree-extracts and profile consolidations. Goes through {@link KimiService}. */
    public static final String MEMORY_SUMMARIZATION = "kimi-k2.6";

    /** Vector encoding for memory retrieval. No backing feature yet. */
    public static final String EMBEDDINGS           = "text-embedding-3-small";

    /** Background proactive loop between user turns. No backing feature yet (and per
     *  openhuman-lessons.md, defaults OFF until value is proven). */
    public static final String HEARTBEAT            = "claude-haiku-4-5-20251001";

    /** Periodic reflection over recent history — reminder gen, classification,
     *  audit summaries. Anthropic-family for consistency with what reads it. */
    public static final String REFLECTIONS          = "claude-haiku-4-5-20251001";

    /** Eventfulness scoring + drift checks. No backing feature yet. Goes through
     *  {@link KimiService} when wired. */
    public static final String SUBCONSCIOUS         = "kimi-k2.6";

    /** True iff this model name refers to a Moonshot/Kimi endpoint, so callers can
     *  decide between Anthropic and Moonshot HTTP backends. */
    public static boolean isKimi(String model) {
        return model != null && model.startsWith("kimi");
    }
}
