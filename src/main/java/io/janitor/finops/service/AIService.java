package io.janitor.finops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.janitor.finops.config.HttpConfig.GroqProperties;
import io.janitor.finops.exception.GroqApiException;
import io.janitor.finops.model.AIDecision;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  AI SERVICE  –  "Ask Llama-3: is this namespace idle or broken?"
 * ═══════════════════════════════════════════════════════════════════
 *
 * Responsibilities:
 *   1. Build a structured prompt with the namespace name + last 50 lines of logs.
 *   2. POST it to Groq's /chat/completions endpoint.
 *   3. Parse the JSON response into an {@link AIDecision} record.
 *   4. If anything goes wrong (timeout, bad JSON, API error), return a safe
 *      fallback so the rest of the pipeline never crashes.
 *
 * Why Groq and not a local LLM?
 *   Running Ollama/Llama locally needs 6 GB+ RAM.  Groq's cloud API is
 *   sub-second and the free tier gives us 30 req/min — more than enough
 *   for a 15-minute scan cycle.  RAM cost on our machine: ~0 MB.
 *
 * The Prompt Design:
 *   We give the LLM a STRICT output format (JSON only) so parsing is
 *   deterministic.  We also give it two example outputs so it doesn't
 *   "hallucinate" a different schema.
 * ═══════════════════════════════════════════════════════════════════
 */
@Service
public class AIService {

    private static final Logger LOG = LoggerFactory.getLogger(AIService.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient  http;
    private final GroqProperties groq;
    private final ObjectMapper  mapper;

    public AIService(OkHttpClient http, GroqProperties groq) {
        this.http   = http;
        this.groq   = groq;
        this.mapper = new ObjectMapper();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PUBLIC ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Sends the namespace context to Groq and returns the AI's verdict.
     *
     * @param namespace  the namespace name
     * @param logs       the last ~50 lines of pod logs (raw text)
     * @return an {@link AIDecision} — never null; returns a fallback on failure
     */
    public AIDecision analyzeNamespace(String namespace, String logs) {

        if (groq.getApiKey() == null || groq.getApiKey().isBlank()) {
            LOG.warn("[AI] GROQ_API_KEY not set. Returning safe fallback.");
            return AIDecision.fallbackSafe();
        }

        try {
            String prompt   = buildPrompt(namespace, logs);
            String rawJson  = callGroqApi(prompt);
            return parseDecision(rawJson);

        } catch (GroqApiException e) {
            LOG.error("[AI] Groq API error for '{}': {} (HTTP {})", namespace, e.getMessage(), e.getHttpStatus());
            return AIDecision.fallbackSafe();

        } catch (Exception e) {
            LOG.error("[AI] Unexpected error for '{}': {}", namespace, e.getMessage());
            return AIDecision.fallbackSafe();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PROMPT BUILDER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Constructs the prompt we send to Llama-3.
     *
     * Key design decisions:
     *   • System message defines the role and STRICT output format.
     *   • Two few-shot examples so the model learns the exact JSON schema.
     *   • User message contains the actual logs — kept separate for clarity.
     *
     * @return the user-turn message string (the system prompt is sent separately)
     */
    private String buildPrompt(String namespace, String logs) {
        // Truncate logs to ~2000 chars to stay well under Groq's token limit
        String truncatedLogs = logs.length() > 2000
                ? logs.substring(logs.length() - 2000)
                : logs;

        return """
                Namespace: %s

                Pod Logs (last 50 lines):
                ---
                %s
                ---

                Based on these logs, is this namespace truly IDLE (safe to hibernate)
                or is it BROKEN (experiencing errors that need human attention)?

                Respond with ONLY valid JSON. No explanation outside the JSON.
                """.formatted(namespace, truncatedLogs);
    }

    /** The system prompt that every request carries. Defined once, reused always. */
    private static final String SYSTEM_PROMPT = """
            You are a Kubernetes FinOps analyst. Your job is to look at pod logs
            and decide whether a namespace is truly idle or is experiencing errors.

            You MUST respond with ONLY this JSON structure — nothing else:
            {
              "safe": <true or false>,
              "riskScore": <integer 1-10>,
              "reason": "<one sentence>"
            }

            Rules:
            - safe = true  means the namespace is genuinely idle. OK to hibernate.
            - safe = false means there are errors or active work. Do NOT hibernate.
            - riskScore 1-3  = very safe to hibernate.
            - riskScore 4-6  = borderline, lean toward alert.
            - riskScore 7-10 = definitely broken or active. Do NOT hibernate.

            Examples:
            Input logs: "INFO: Server started. INFO: Waiting for connections."
            Output: {"safe": true, "riskScore": 2, "reason": "No errors or activity detected."}

            Input logs: "FATAL: OOMKilled. ERROR: ImagePullBackOff. ERROR: CrashLoopBackOff."
            Output: {"safe": false, "riskScore": 9, "reason": "Pod is crash-looping due to OOM — needs investigation."}

            Input logs: "INFO: Processing batch 1042. INFO: Queue depth: 3."
            Output: {"safe": false, "riskScore": 5, "reason": "Active batch processing detected."}
            """;

    // ═══════════════════════════════════════════════════════════════════════════
    //  GROQ HTTP CALL
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * POSTs to Groq's /chat/completions endpoint.
     *
     * @param userMessage the formatted prompt
     * @return the raw text content of the model's first choice
     * @throws GroqApiException if the HTTP call fails or returns non-2xx
     */
    private String callGroqApi(String userMessage) {

        // ── Build request body ─────────────────────────────────────────────
        Map<String, Object> body = Map.of(
                "model", groq.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user",   "content", userMessage)
                ),
                "temperature", 0.1,     // low temp = deterministic JSON output
                "max_tokens",  200      // we only need a tiny JSON blob
        );

        String jsonBody;
        try {
            jsonBody = mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new GroqApiException("Failed to serialize request body", e);
        }

        // ── Fire the request ───────────────────────────────────────────────
        Request request = new Request.Builder()
                .url(groq.getUrl())
                .post(RequestBody.create(jsonBody, JSON))
                .header("Authorization", "Bearer " + groq.getApiKey())
                .header("Content-Type", "application/json")
                .build();

        try (Response response = http.newCall(request).execute()) {

            if (!response.isSuccessful()) {
                throw new GroqApiException(
                        "Groq returned HTTP " + response.code() + ": " + response.body().string(),
                        response.code()
                );
            }

            // ── Parse the OpenAI-compatible response ───────────────────────
            JsonNode root      = mapper.readTree(response.body().string());
            JsonNode choices   = root.path("choices");

            if (choices.isEmpty()) {
                throw new GroqApiException("Groq response has no 'choices' array.", response.code());
            }

            return choices.get(0).path("message").path("content").asText();

        } catch (IOException e) {
            throw new GroqApiException("HTTP call to Groq failed", e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  RESPONSE PARSER
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Extracts our AIDecision from the LLM's raw text.
     *
     * The LLM SHOULD return pure JSON (we asked nicely in the prompt).
     * But LLMs are unpredictable, so we strip markdown fences and whitespace
     * before parsing.
     *
     * @param raw the model's content string
     * @return parsed {@link AIDecision}
     */
    private AIDecision parseDecision(String raw) {
        try {
            // Strip common markdown code-fence wrappers: ```json ... ```
            String cleaned = raw.strip()
                    .replaceAll("^```(json)?\\s*", "")
                    .replaceAll("\\s*```$", "")
                    .strip();

            JsonNode node = mapper.readTree(cleaned);

            boolean safe      = node.path("safe").asBoolean(true);
            int     riskScore = node.path("riskScore").asInt(5);
            String  reason    = node.path("reason").asText("No reason provided.");

            LOG.info("[AI] Decision parsed — safe={}, risk={}, reason=\"{}\"", safe, riskScore, reason);
            return new AIDecision(safe, riskScore, reason);

        } catch (Exception e) {
            LOG.error("[AI] Failed to parse Groq response: '{}'. Error: {}", raw, e.getMessage());
            // Conservative fallback: if we can't trust the AI, don't hibernate
            return AIDecision.fallbackUnsafe();
        }
    }
}
