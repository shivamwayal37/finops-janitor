package io.janitor.finops.controller;

import io.janitor.finops.model.AIDecision;
import io.janitor.finops.model.ResourceStatus;
import io.janitor.finops.scheduler.CleanupScheduler;
import io.janitor.finops.service.HibernationService;
import io.janitor.finops.service.ScannerService;
import io.janitor.finops.service.SlackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  JANITOR CONTROLLER  –  REST API (manual triggers + status)
 * ═══════════════════════════════════════════════════════════════════
 *
 * Endpoints:
 *   GET  /janitor/status          – current cluster scan snapshot
 *   GET  /janitor/history         – last N rows from hibernate_log (SQLite)
 *   POST /janitor/scan            – trigger an immediate scan (don't wait for cron)
 *   POST /janitor/hibernate/{ns}  – manually hibernate a specific namespace
 *   POST /janitor/wakeup/{ns}     – manually wake up a hibernated namespace
 *   GET  /janitor/stats           – cycle-level counters (hibernated, alerts)
 *
 * Why a REST API on top of the scheduler?
 *   The cron job runs automatically.  But during demos and interviews you
 *   want to trigger things NOW.  Also, the wake-up endpoint is what a
 *   developer calls when they need their dev env back immediately.
 * ═══════════════════════════════════════════════════════════════════
 */
@RestController
@RequestMapping("/janitor")
public class JanitorController {

    private static final Logger LOG = LoggerFactory.getLogger(JanitorController.class);

    private final ScannerService     scanner;
    private final HibernationService hibernation;
    private final SlackService       slack;
    private final CleanupScheduler   scheduler;
    private final JdbcTemplate       jdbc;

    public JanitorController(ScannerService scanner, HibernationService hibernation,
                             SlackService slack, CleanupScheduler scheduler,
                             JdbcTemplate jdbc) {
        this.scanner     = scanner;
        this.hibernation = hibernation;
        this.slack       = slack;
        this.scheduler   = scheduler;
        this.jdbc        = jdbc;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  GET /janitor/status  –  "What does the cluster look like RIGHT NOW?"
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Runs a fresh scan and returns the snapshot immediately.
     * No caching — every call hits the K8s API.
     *
     * Example response:
     * [
     *   { "namespace": "engineering-dev", "status": "IDLE", "cpuUsagePercent": 0.0, ... },
     *   { "namespace": "marketing-beta",  "status": "ACTIVE", "cpuUsagePercent": 12.5, ... }
     * ]
     */
    @GetMapping("/status")
    public ResponseEntity<List<ResourceStatus>> getStatus() {
        LOG.info("[API] GET /janitor/status — running live scan");
        List<ResourceStatus> results = scanner.scanAll();
        return ResponseEntity.ok(results);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  GET /janitor/history  –  "What has the Janitor done recently?"
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns the last N rows from the hibernate_log SQLite table.
     * Query param: ?limit=20  (default 20)
     *
     * This powers the "audit trail" section of any future dashboard.
     */
    @GetMapping("/history")
    public ResponseEntity<List<Map<String, Object>>> getHistory(@RequestParam(value = "limit", defaultValue = "20") int limit) {

        LOG.info("[API] GET /janitor/history (limit={})", limit);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM hibernate_log ORDER BY actioned_at DESC LIMIT ?", limit);

        return ResponseEntity.ok(rows);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  POST /janitor/scan  –  "Scan NOW, don't wait for cron"
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Manually triggers the full cleanup cycle (scan → AI → hibernate).
     * Useful for demos: "Watch what happens when I hit this button."
     *
     * Returns a summary of what happened.
     */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> triggerScan() {
        LOG.info("[API] POST /janitor/scan — manual trigger");

        // Delegate to the scheduler's main method (same logic the cron runs)
        scheduler.runCleanupCycle();

        Map<String, Object> summary = Map.of(
                "message",    "Cleanup cycle completed.",
                "hibernated", scheduler.getLastCycleHibernatedCount(),
                "alerts",     scheduler.getLastCycleAlertCount()
        );

        return ResponseEntity.ok(summary);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  POST /janitor/hibernate/{namespace}  –  "Force hibernate this one"
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Manually hibernates a specific namespace, skipping the scan + AI steps.
     * Accepts a query param: ?dryRun=true  (default false)
     *
     * Use case: "I know this namespace is idle. Just kill it now."
     *
     * Example:
     *   POST /janitor/hibernate/engineering-dev?dryRun=true
     */
    @PostMapping("/hibernate/{namespace}")
    public ResponseEntity<Map<String, Object>> manualHibernate(
            @PathVariable("namespace") String namespace,
            @RequestParam(value = "dryRun", defaultValue = "false") boolean dryRun) {

        LOG.info("[API] POST /janitor/hibernate/{} (dryRun={})", namespace, dryRun);

        // Manual decision (bypasses AI call)
        AIDecision manualDecision = new AIDecision(true, 1, "Manual hibernation triggered via API.");

        int replicasDown = hibernation.hibernate(namespace, manualDecision, dryRun);

        if (!dryRun && replicasDown > 0) {
            slack.notifyPreHibernation(namespace, manualDecision);
        }

        Map<String, Object> result = Map.of(
                "namespace", namespace,
                "replicasScaledDown", replicasDown,
                "dryRun", dryRun,
                "message", dryRun
                        ? "Dry run complete. Nothing was changed."
                        : "Namespace '" + namespace + "' has been hibernated.");

        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  POST /janitor/wakeup/{namespace}  –  "Wake it up!"
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Wakes up a hibernated namespace by reading the saved annotation
     * and restoring original replica counts.
     *
     * Example:
     *   POST /janitor/wakeup/engineering-dev
     */
    @PostMapping("/wakeup/{namespace}")
    public ResponseEntity<Map<String, Object>> wakeUp(
            @PathVariable("namespace") String namespace) {

        LOG.info("[API] POST /janitor/wakeup/{}", namespace);

        int replicasRestored = hibernation.wakeUp(namespace);

        // Only notify Slack if something was actually restored
        if (replicasRestored > 0) {
            slack.notifyWakeUp(namespace, replicasRestored);
        } else {
            LOG.warn("[API] No deployments restored for namespace {}", namespace);
        }

        Map<String, Object> result = Map.of(
                "namespace", namespace,
                "replicasRestored", replicasRestored,
                "message", replicasRestored > 0
                        ? "Namespace '" + namespace + "' is back online."
                        : "No hibernated deployments found in namespace '" + namespace + "'.");

        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  GET /janitor/stats  –  "Last cycle numbers"
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Returns counters from the most recent cleanup cycle.
     * Lightweight — no DB or K8s calls.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = Map.of(
                "lastCycleHibernated", scheduler.getLastCycleHibernatedCount(),
                "lastCycleAlerts",     scheduler.getLastCycleAlertCount()
        );
        return ResponseEntity.ok(stats);
    }
}
