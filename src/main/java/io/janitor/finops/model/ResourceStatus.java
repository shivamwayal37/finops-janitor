package io.janitor.finops.model;

import java.time.Instant;

/**
 * Snapshot of a single namespace's health at scan time.
 *
 * Immutable record — safe to share across threads with zero copying.
 *
 * Fields:
 *   namespace        – the K8s namespace name (e.g. "engineering-dev")
 *   cpuUsagePercent  – average CPU over the last {@code thresholdHours} (0-100)
 *   memUsagePercent  – average Memory over the same window
 *   podCount         – number of running Pods right now
 *   age              – how long (seconds) since the namespace was created
 *   status           – one of ACTIVE | IDLE | HIBERNATED | BROKEN
 *   scannedAt        – wall-clock instant when this snapshot was taken
 */
public record ResourceStatus(
        String          namespace,
        double          cpuUsagePercent,
        double          memUsagePercent,
        int             podCount,
        long            ageSeconds,
        NamespaceStatus status,
        Instant         scannedAt
) {
    // ── Convenience factory ──────────────────────────────────────────────────
    public static ResourceStatus of(String namespace, double cpu, double mem,
                                    int pods, long ageSec, NamespaceStatus status) {
        return new ResourceStatus(namespace, cpu, mem, pods, ageSec, status, Instant.now());
    }
}
