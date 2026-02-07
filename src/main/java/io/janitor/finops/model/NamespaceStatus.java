package io.janitor.finops.model;

/**
 * Every state a namespace can travel through in the Janitor's lifecycle.
 *
 *   ACTIVE      – pods are running and using CPU/Memory.  No action taken.
 *   IDLE        – metrics are below threshold for > thresholdHours.  Candidate for hibernation.
 *   HIBERNATED  – all Deployments scaled to 0.  Original replica counts saved as annotations.
 *   BROKEN      – AI flagged "Fatal" errors in logs.  Slack alert sent; namespace left alone.
 *
 * State transitions:
 *   ACTIVE  ──► IDLE  ──► HIBERNATED
 *                  └──► BROKEN        (AI says "don't touch this")
 *   HIBERNATED ──► ACTIVE             (developer manually wakes it up)
 */
public enum NamespaceStatus {
    ACTIVE,
    IDLE,
    HIBERNATED,
    BROKEN
}
