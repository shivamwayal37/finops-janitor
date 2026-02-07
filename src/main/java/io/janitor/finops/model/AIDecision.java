package io.janitor.finops.model;

/**
 * Structured response from the Groq / Llama-3 AI check.
 *
 * The AIService parses the LLM's raw text into this record so the rest of
 * the app never has to touch raw JSON or free-form strings.
 *
 * Fields:
 *   safe         – true  → namespace is genuinely idle, ok to hibernate.
 *                  false → something is wrong; alert the team instead.
 *   riskScore    – 1 (totally safe) … 10 (critical — do NOT touch).
 *   reason       – one-sentence human-readable explanation from the AI.
 *
 * Example (safe):
 *   { safe: true,  riskScore: 2, reason: "No traffic or errors for 4 h." }
 *
 * Example (broken):
 *   { safe: false, riskScore: 8, reason: "Repeated ImagePullBackOff detected." }
 */
public record AIDecision(
        boolean safe,
        int     riskScore,
        String  reason
) {

    // ── Guard: riskScore must be 1-10 ────────────────────────────────────────
    public AIDecision {
        if (riskScore < 1 || riskScore > 10) {
            riskScore = safe ? 1 : 10;   // clamp to valid range
        }
    }

    /** Fallback when the AI API is unreachable or returns garbage. */
    public static AIDecision fallbackSafe() {
        return new AIDecision(true, 3,
                "AI check unavailable — defaulting to safe (idle) based on metrics alone.");
    }

    /** Fallback when we want to be conservative and NOT hibernate. */
    public static AIDecision fallbackUnsafe() {
        return new AIDecision(false, 7,
                "AI check unavailable — defaulting to unsafe. Manual review required.");
    }
}
