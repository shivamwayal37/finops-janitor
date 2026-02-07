package io.janitor.finops.service;

import io.janitor.finops.config.HttpConfig.SlackProperties;
import io.janitor.finops.model.AIDecision;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  SLACK SERVICE  –  "Human-in-the-loop notifications"
 * ═══════════════════════════════════════════════════════════════════
 *
 * Why Slack matters for this project:
 *   Total automation is scary.  Real platform teams NEVER auto-delete
 *   without telling someone first.  This service is the "trust layer"
 *   that makes the Janitor enterprise-ready.
 *
 * Message types:
 *   1. PRE-HIBERNATE alert: "Namespace X is idle. Hibernating in 30 min."
 *   2. BROKEN alert:        "Namespace Y has fatal errors. Needs review."
 *   3. WAKE-UP confirm:     "Namespace Z has been woken up by <user>."
 *
 * Slack Block Kit is used for rich formatting with emoji and code blocks.
 * ═══════════════════════════════════════════════════════════════════
 */
@Service
public class SlackService {

    private static final Logger LOG    = LoggerFactory.getLogger(SlackService.class);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient   http;
    private final SlackProperties slack;

    public SlackService(OkHttpClient http, SlackProperties slack) {
        this.http  = http;
        this.slack = slack;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PUBLIC: NOTIFICATION METHODS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Posts a "namespace will be hibernated" message.
     *
     * @param namespace  the namespace about to be hibernated
     * @param decision   the AI's analysis
     */
    public void notifyPreHibernation(String namespace, AIDecision decision) {
        if (!slack.isEnabled()) {
            LOG.info("[SLACK] Disabled. Would have posted pre-hibernation alert for '{}'.", namespace);
            return;
        }

        String message = """
                🧹 *FinOps Janitor — Hibernation Alert*

                *Namespace:* `%s`
                *Status:* Idle (0%% CPU for 2+ hours)
                *AI Risk Score:* %d / 10
                *AI Reason:* %s

                ⏳ This namespace will be hibernated in *30 minutes*.
                To prevent this, scale your deployment manually or remove the `janitor.io/policy` label.
                """.formatted(namespace, decision.riskScore(), decision.reason());

        postToSlack(message);
    }

    /**
     * Posts a "something is broken, don't hibernate" alert.
     *
     * @param namespace  the namespace with errors
     * @param decision   the AI's analysis (safe=false)
     */
    public void notifyBrokenNamespace(String namespace, AIDecision decision) {
        if (!slack.isEnabled()) {
            LOG.info("[SLACK] Disabled. Would have posted BROKEN alert for '{}'.", namespace);
            return;
        }

        String message = """
                🚨 *FinOps Janitor — Broken Namespace Alert*

                *Namespace:* `%s`
                *AI Risk Score:* %d / 10
                *AI Reason:* %s

                ⚠️ The Janitor detected errors. This namespace has *NOT* been hibernated.
                Please investigate and fix the issues.
                """.formatted(namespace, decision.riskScore(), decision.reason());

        postToSlack(message);
    }

    /**
     * Posts a "namespace woken up" confirmation.
     *
     * @param namespace       the namespace that was restored
     * @param restoredReplicas how many replicas came back
     */
    public void notifyWakeUp(String namespace, int restoredReplicas) {
        if (!slack.isEnabled()) {
            LOG.info("[SLACK] Disabled. Would have posted wake-up confirmation for '{}'.", namespace);
            return;
        }

        String message = """
                ✅ *FinOps Janitor — Namespace Woken Up*

                *Namespace:* `%s`
                *Replicas Restored:* %d

                A developer manually triggered a wake-up. The namespace is back online.
                """.formatted(namespace, restoredReplicas);

        postToSlack(message);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PRIVATE: HTTP CALL TO SLACK WEBHOOK
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Sends a message to the configured Slack webhook URL.
     *
     * Uses Slack's simple {"text": "..."} payload.
     * Slack auto-renders *bold* and `code` in this format.
     *
     * @param text the message body (Slack mrkdwn format)
     */
    private void postToSlack(String text) {
        // Slack webhook expects: {"text": "..."}
        // We use Jackson-free string formatting to avoid an extra dependency call.
        String jsonBody = """
                {"text": %s}
                """.formatted(escapeJson(text));

        Request request = new Request.Builder()
                .url(slack.getWebhookUrl())
                .post(RequestBody.create(jsonBody, JSON))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                LOG.error("[SLACK] Webhook returned HTTP {}: {}", response.code(), response.body().string());
            } else {
                LOG.info("[SLACK] Message posted successfully.");
            }
        } catch (IOException e) {
            // Never crash the controller because of a Slack failure
            LOG.error("[SLACK] Failed to post message: {}", e.getMessage());
        }
    }

    /**
     * Minimal JSON string escaper.
     * Wraps the text in quotes and escapes inner quotes + newlines.
     */
    private static String escapeJson(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"",  "\\\"")
                .replace("\n",  "\\n")
                .replace("\r",  "\\r")
                .replace("\t",  "\\t")
                + "\"";
    }
}
