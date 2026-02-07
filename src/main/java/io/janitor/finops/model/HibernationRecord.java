package io.janitor.finops.model;

import java.time.Instant;

/**
 * Persistence DTO — one row in the {@code hibernate_log} SQLite table.
 *
 * Every time the Janitor hibernates (or alerts on) a namespace this record
 * is written.  It is also the source of truth for:
 *     • the cost-savings dashboard (sum up saved hours × hourly rate)
 *     • the "95 % accuracy" metric (compare AI prediction vs actual status)
 *     • the audit trail that hiring managers love to see
 *
 * Fields:
 *   id               – auto-increment PK (set by DB, null on insert)
 *   namespace        – target namespace name
 *   action           – HIBERNATED | WOKEN_UP | ALERT_SENT | DRY_RUN
 *   originalReplicas – replica count BEFORE scale-to-zero (so we can restore)
 *   aiSafe           – what the AI said: true = idle, false = broken
 *   aiRiskScore      – 1-10
 *   aiReason         – AI's one-sentence explanation
 *   actionedAt       – when the action actually happened
 *   dryRun           – true if --dry-run flag was set (nothing actually changed)
 */
public record HibernationRecord(
        Long    id,
        String  namespace,
        String  action,
        int     originalReplicas,
        boolean aiSafe,
        int     aiRiskScore,
        String  aiReason,
        Instant actionedAt,
        boolean dryRun
) {
    /** Factory for a fresh HIBERNATED record (id = null, DB will assign). */
    public static HibernationRecord hibernated(String namespace, int origReplicas,
                                               AIDecision ai, boolean dryRun) {
        return new HibernationRecord(
                null, namespace, "HIBERNATED", origReplicas,
                ai.safe(), ai.riskScore(), ai.reason(),
                Instant.now(), dryRun
        );
    }

    /** Factory for a WOKEN_UP record. */
    public static HibernationRecord wokenUp(String namespace, int restoredReplicas) {
        return new HibernationRecord(
                null, namespace, "WOKEN_UP", restoredReplicas,
                true, 1, "Manual wake-up triggered by developer.",
                Instant.now(), false
        );
    }

    /** Factory for an ALERT_SENT record (AI said "broken"). */
    public static HibernationRecord alertSent(String namespace, AIDecision ai) {
        return new HibernationRecord(
                null, namespace, "ALERT_SENT", 0,
                ai.safe(), ai.riskScore(), ai.reason(),
                Instant.now(), false
        );
    }
}
