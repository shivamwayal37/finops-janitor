package io.janitor.finops.scheduler;

import io.janitor.finops.model.AIDecision;
import io.janitor.finops.model.NamespaceStatus;
import io.janitor.finops.model.ResourceStatus;
import io.janitor.finops.service.AIService;
import io.janitor.finops.service.HibernationService;
import io.janitor.finops.service.ScannerService;
import io.janitor.finops.service.SlackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  CLEANUP SCHEDULER  –  "The heartbeat of the Janitor"
 * ═══════════════════════════════════════════════════════════════════
 *
 * This is the ONLY class that owns the full lifecycle flow:
 *
 *   @Scheduled (every 30 min)
 *       │
 *       ▼
 *   ScannerService.scanAll()          ← find IDLE namespaces
 *       │
 *       ▼  (for each IDLE namespace)
 *   ScannerService.fetchPodLogs()     ← grab log context
 *       │
 *       ▼
 *   AIService.analyzeNamespace()      ← ask Groq: idle or broken?
 *       │
 *       ├── AI says BROKEN ──► SlackService.notifyBrokenNamespace()   → STOP
 *       │
 *       └── AI says IDLE   ──► SlackService.notifyPreHibernation()
 *                              HibernationService.hibernate()         → DONE
 *
 * Design decisions:
 *   • This class is a @Component, not a @Service, because it doesn't expose
 *     reusable logic — it orchestrates one specific workflow.
 *   • The cron expression is externalized to application.yml so we can
 *     change the interval without recompiling.
 *   • A simple AtomicInteger counter tracks how many namespaces were
 *     hibernated per cycle — useful for the Actuator health endpoint.
 * ═══════════════════════════════════════════════════════════════════
 */
@Component
public class CleanupScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(CleanupScheduler.class);

    // ── Services (injected) ──────────────────────────────────────────────────
    private final ScannerService     scanner;
    private final AIService          ai;
    private final HibernationService hibernation;
    private final SlackService       slack;
    private final JdbcTemplate       jdbc;

    // ── Config ───────────────────────────────────────────────────────────────
    @Value("${janitor.dry-run:false}")
    private boolean dryRun;

    @Value("${janitor.pod-log-lines:50}")
    private int podLogLines;

    // ── Runtime counters (for /actuator/janitor-stats) ──────────────────────
    private final AtomicInteger totalHibernatedThisCycle = new AtomicInteger(0);
    private final AtomicInteger totalAlertsThisCycle     = new AtomicInteger(0);

    public CleanupScheduler(ScannerService scanner, AIService ai,
                            HibernationService hibernation, SlackService slack,
                            JdbcTemplate jdbc) {
        this.scanner    = scanner;
        this.ai         = ai;
        this.hibernation = hibernation;
        this.slack      = slack;
        this.jdbc       = jdbc;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  THE SCHEDULED JOB
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Runs every N minutes (default 30, configurable via application.yml).
     *
     * The cron format: "0 0/{interval} * * * ?"
     *   second  minute  hour  day-of-month  month  day-of-week
     *     0     every30   *        *          *        ?
     *
     * {@code fixedRate} is NOT used because each cycle's duration varies
     * (API calls take different amounts of time).  {@code @Scheduled(cron=...)}
     * fires at fixed wall-clock intervals regardless of how long the last
     * cycle took.
     */
    @Scheduled(cron = "${janitor.cron:0 0/30 * * * ?}")
    public void runCleanupCycle() {

        LOG.info("╔══════════════════════════════════════════════════════╗");
        LOG.info("║   JANITOR CLEANUP CYCLE STARTED  [dryRun={}]       ║", dryRun);
        LOG.info("╚══════════════════════════════════════════════════════╝");

        // Reset per-cycle counters
        totalHibernatedThisCycle.set(0);
        totalAlertsThisCycle.set(0);

        // ── Step 1: Scan ───────────────────────────────────────────────────
        List<ResourceStatus> snapshots = scanner.scanAll();

        // ── Step 2: Filter to IDLE only ─────────────────────────────────────
        List<ResourceStatus> idleNamespaces = snapshots.stream()
                .filter(s -> s.status() == NamespaceStatus.IDLE)
                .toList();

        LOG.info("[CYCLE] Scan complete. Total={}, Idle={}, Active={}",
                snapshots.size(), idleNamespaces.size(),
                snapshots.size() - idleNamespaces.size());

        if (idleNamespaces.isEmpty()) {
            LOG.info("[CYCLE] Nothing to do this cycle.");
            return;
        }

        // ── Step 3: Process each idle namespace ─────────────────────────────
        for (ResourceStatus idle : idleNamespaces) {
            processIdleNamespace(idle);
        }

        // ── Step 4: Cycle summary ───────────────────────────────────────────
        LOG.info("─────────────────────────────────────────────────────");
        LOG.info("[CYCLE] DONE. Hibernated={}, Alerts={}, DryRun={}",
                totalHibernatedThisCycle.get(), totalAlertsThisCycle.get(), dryRun);
        LOG.info("─────────────────────────────────────────────────────");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PER-NAMESPACE LOGIC
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * The full decision pipeline for ONE namespace:
     *   logs → AI → Slack → Hibernate (or Alert)
     *
     * Wrapped in try/catch so one bad namespace doesn't kill the whole cycle.
     */
    private void processIdleNamespace(ResourceStatus idle) {
        String ns = idle.namespace();

        try {
            LOG.info("[CYCLE] Processing idle namespace: '{}'", ns);

            // ── Fetch logs ───────────────────────────────────────────────────
            String logs = scanner.fetchPodLogs(ns, podLogLines);
            LOG.debug("[CYCLE] Fetched {} chars of logs for '{}'", logs.length(), ns);

            // ── Ask the AI ───────────────────────────────────────────────────
            AIDecision decision = ai.analyzeNamespace(ns, logs);

            // ── Branch on AI verdict ─────────────────────────────────────────
            if (!decision.safe()) {
                // ── BROKEN path: alert, do NOT hibernate ─────────────────────
                LOG.warn("[CYCLE] AI flagged '{}' as BROKEN (risk={}). Sending alert.",
                        ns, decision.riskScore());

                slack.notifyBrokenNamespace(ns, decision);
                persistAlertRecord(ns, decision);
                totalAlertsThisCycle.incrementAndGet();

            } else {
                // ── IDLE path: notify then hibernate ─────────────────────────
                LOG.info("[CYCLE] AI confirmed '{}' is IDLE (risk={}). Proceeding.",
                        ns, decision.riskScore());

                slack.notifyPreHibernation(ns, decision);
                hibernation.hibernate(ns, decision, dryRun);
                totalHibernatedThisCycle.incrementAndGet();
            }

        } catch (Exception e) {
            // Never let one namespace crash the whole cycle
            LOG.error("[CYCLE] Error processing '{}': {}", ns, e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Writes an ALERT_SENT row to SQLite (audit trail for broken namespaces). */
    private void persistAlertRecord(String namespace, AIDecision decision) {
        try {
            jdbc.update(
                    """
                    INSERT INTO hibernate_log
                        (namespace, action, original_replicas, ai_safe, ai_risk_score, ai_reason, actioned_at, dry_run)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    namespace,
                    "ALERT_SENT",
                    0,
                    decision.safe(),
                    decision.riskScore(),
                    decision.reason(),
                    java.time.Instant.now().toString(),
                    dryRun
            );
        } catch (Exception e) {
            LOG.error("[DB] Failed to persist alert record for '{}': {}", namespace, e.getMessage());
        }
    }

    // ── Getters for the health/stats endpoint ───────────────────────────────
    public int getLastCycleHibernatedCount() { return totalHibernatedThisCycle.get(); }
    public int getLastCycleAlertCount()      { return totalAlertsThisCycle.get(); }
}
