package io.janitor.finops.service;

import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.AppsV1Api;
import io.kubernetes.client.openapi.models.*;
import io.janitor.finops.exception.KubernetesOperationException;
import io.janitor.finops.model.AIDecision;
import io.janitor.finops.model.HibernationRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ═══════════════════════════════════════════════════════════════════
 *  HIBERNATION SERVICE  –  "Scale to zero — safely"
 * ═══════════════════════════════════════════════════════════════════
 *
 * This is the service that actually DOES something to the cluster.
 * Everything else is read-only scanning; this one writes.
 *
 * Two operations:
 *   hibernate(namespace, aiDecision, dryRun)
 *       → Saves original replica count as a K8s annotation FIRST.
 *       → Then patches every Deployment in the namespace to replicas: 0.
 *       → Logs the action to SQLite.
 *
 *   wakeUp(namespace)
 *       → Reads the saved annotation.
 *       → Patches every Deployment back to its original replica count.
 *       → Logs the wake-up to SQLite.
 *
 * Why annotations instead of a database?
 *   K8s stores annotations in etcd — the cluster's own durable store.
 *   If our Spring Boot app crashes, restarts, or is deleted, the
 *   original replica counts are still there.  This is the "stateless
 *   controller" pattern that real platform teams use.
 *
 * Annotation keys used:
 *   janitor.io/original-replicas   – the int replica count before hibernation
 *   janitor.io/hibernated-at       – ISO-8601 timestamp when it was hibernated
 *   janitor.io/ai-reason           – the AI's one-sentence explanation
 * ═══════════════════════════════════════════════════════════════════
 */
@Service
public class HibernationService {

    private static final Logger LOG = LoggerFactory.getLogger(HibernationService.class);

    // ── Annotation keys (single source of truth) ────────────────────────────
    static final String ANN_ORIGINAL_REPLICAS = "janitor.io/original-replicas";
    static final String ANN_HIBERNATED_AT     = "janitor.io/hibernated-at";
    static final String ANN_AI_REASON         = "janitor.io/ai-reason";

    // ── K8s API ──────────────────────────────────────────────────────────────
    private final AppsV1Api appsApi;

    // ── Persistence ──────────────────────────────────────────────────────────
    private final JdbcTemplate jdbc;

    // ── Config ───────────────────────────────────────────────────────────────
    @Value("${janitor.dry-run:false}")
    private boolean globalDryRun;    // can be overridden per-call

    public HibernationService(ApiClient apiClient, JdbcTemplate jdbc) {
        this.appsApi = new AppsV1Api(apiClient);
        this.jdbc    = jdbc;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HIBERNATE  –  scale everything to 0
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Hibernates every Deployment in the given namespace.
     *
     * @param namespace  target namespace
     * @param decision   the AI's verdict (stored for audit)
     * @param dryRun     if true, log what WOULD happen but change nothing
     * @return total replicas that were (or would have been) scaled down
     */
    public int hibernate(String namespace, AIDecision decision, boolean dryRun) {
        boolean effectiveDryRun = dryRun || globalDryRun;

        LOG.info("[HIBERNATE] namespace='{}' dryRun={} aiSafe={} aiReason='{}'",
                namespace, effectiveDryRun, decision.safe(), decision.reason());

        List<V1Deployment> deployments = listDeployments(namespace);

        if (deployments.isEmpty()) {
            LOG.info("[HIBERNATE] No deployments in '{}'. Nothing to hibernate.", namespace);
            return 0;
        }

        int totalReplicasDown = 0;

        for (V1Deployment dep : deployments) {
            String depName       = dep.getMetadata().getName();
            int    currentReplicas = dep.getSpec().getReplicas() != null
                    ? dep.getSpec().getReplicas() : 1;

            if (currentReplicas == 0) {
                LOG.debug("[HIBERNATE] '{}/{}' already at 0 replicas. Skipping.", namespace, depName);
                continue;
            }

            if (effectiveDryRun) {
                // ── DRY RUN: only log, never touch ──────────────────────────
                LOG.info("[HIBERNATE] [DRY-RUN] Would hibernate '{}/{}' (replicas {} → 0)",
                        namespace, depName, currentReplicas);
            } else {
                // ── REAL: annotate then patch ────────────────────────────────
                annotateDeployment(namespace, dep, currentReplicas, decision);
                patchReplicasToZero(namespace, depName);
                LOG.info("[HIBERNATE] Hibernated '{}/{}' (replicas {} → 0)",
                        namespace, depName, currentReplicas);
            }

            totalReplicasDown += currentReplicas;
        }

        // ── Persist to SQLite audit log ──────────────────────────────────────
        persistRecord(HibernationRecord.hibernated(namespace, totalReplicasDown, decision, effectiveDryRun));

        return totalReplicasDown;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  WAKE UP  –  restore original replica counts
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Wakes up every Deployment in the namespace by reading the saved
     * {@code janitor.io/original-replicas} annotation.
     *
     * @param namespace  target namespace
     * @return total replicas restored
     */
    public int wakeUp(String namespace) {
        LOG.info("[WAKEUP] Waking up namespace '{}'", namespace);

        List<V1Deployment> deployments = listDeployments(namespace);
        int totalRestored = 0;

        for (V1Deployment dep : deployments) {
            String depName = dep.getMetadata().getName();
            Map<String, String> annotations = dep.getMetadata().getAnnotations();

            if (annotations == null || !annotations.containsKey(ANN_ORIGINAL_REPLICAS)) {
                LOG.warn("[WAKEUP] '{}/{}' has no saved replica annotation. Setting to 1.",
                        namespace, depName);
                patchReplicas(namespace, depName, 1);
                totalRestored += 1;
                continue;
            }

            int originalReplicas = Integer.parseInt(annotations.get(ANN_ORIGINAL_REPLICAS));
            patchReplicas(namespace, depName, originalReplicas);

            // Remove the hibernation annotations (clean state)
            removeHibernationAnnotations(namespace, dep);

            LOG.info("[WAKEUP] Restored '{}/{}' to {} replica(s)", namespace, depName, originalReplicas);
            totalRestored += originalReplicas;
        }

        // ── Persist wake-up event ────────────────────────────────────────────
        persistRecord(HibernationRecord.wokenUp(namespace, totalRestored));

        return totalRestored;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PRIVATE: K8S OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Lists all Deployments in a namespace. */
    private List<V1Deployment> listDeployments(String namespace) {
        try {
            V1DeploymentList list = appsApi.listNamespacedDeployment(
                    namespace, null, null, null, null, null, null, null, null, null, null, null);
            return list.getItems();
        } catch (ApiException e) {
            throw new KubernetesOperationException(namespace,
                    "Failed to list deployments: " + e.getMessage(), e);
        }
    }

    /**
     * Adds three annotations to a Deployment BEFORE we touch replicas.
     * This is the safety net — even if the app crashes mid-operation,
     * the original state is recorded in etcd.
     */
    private void annotateDeployment(String namespace, V1Deployment dep,
                                    int originalReplicas, AIDecision decision) {
        Map<String, String> annotations = dep.getMetadata().getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
        }

        annotations.put(ANN_ORIGINAL_REPLICAS, String.valueOf(originalReplicas));
        annotations.put(ANN_HIBERNATED_AT,     java.time.Instant.now().toString());
        annotations.put(ANN_AI_REASON,         decision.reason());

        dep.getMetadata().setAnnotations(annotations);

        try {
            appsApi.replaceNamespacedDeployment(
                    dep.getMetadata().getName(), namespace, dep, null, null, null, null);
        } catch (ApiException e) {
            throw new KubernetesOperationException(namespace,
                    "Failed to annotate deployment '" + dep.getMetadata().getName() + "': " + e.getMessage(), e);
        }
    }

    /** Patches a single Deployment's replica count to 0. */
    private void patchReplicasToZero(String namespace, String deploymentName) {
        patchReplicas(namespace, deploymentName, 0);
    }

    /**
     * Generic replica patcher using a JSON merge patch.
     * This is the lightest-weight way to change just the replica count
     * without accidentally overwriting other fields.
     */
    private void patchReplicas(String namespace, String deploymentName, int replicas) {
        String patch = """
                {"spec":{"replicas":%d}}
                """.formatted(replicas);

        try {
            appsApi.patchNamespacedDeployment(
                    deploymentName, namespace,
                    new V1Patch(patch),     // JSON merge patch body
                    null, null, null, null, null
            );
        } catch (ApiException e) {
            throw new KubernetesOperationException(namespace,
                    "Failed to patch replicas on '" + deploymentName + "': " + e.getMessage(), e);
        }
    }

    /** Removes hibernation-specific annotations after a wake-up. */
    private void removeHibernationAnnotations(String namespace, V1Deployment dep) {
        Map<String, String> annotations = dep.getMetadata().getAnnotations();
        if (annotations != null) {
            annotations.remove(ANN_ORIGINAL_REPLICAS);
            annotations.remove(ANN_HIBERNATED_AT);
            annotations.remove(ANN_AI_REASON);
            dep.getMetadata().setAnnotations(annotations);

            try {
                appsApi.replaceNamespacedDeployment(
                        dep.getMetadata().getName(), namespace, dep, null, null, null, null);
            } catch (ApiException e) {
                LOG.warn("[WAKEUP] Could not clean annotations on '{}/{}': {}",
                        namespace, dep.getMetadata().getName(), e.getMessage());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  PRIVATE: SQLITE PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Writes a single row to hibernate_log.
     * This is the audit trail AND the data source for cost-savings calculations.
     */
    private void persistRecord(HibernationRecord record) {
        try {
            jdbc.update(
                    """
                    INSERT INTO hibernate_log
                        (namespace, action, original_replicas, ai_safe, ai_risk_score, ai_reason, actioned_at, dry_run)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.namespace(),
                    record.action(),
                    record.originalReplicas(),
                    record.aiSafe(),
                    record.aiRiskScore(),
                    record.aiReason(),
                    record.actionedAt().toString(),
                    record.dryRun()
            );
            LOG.debug("[DB] Persisted record: action={} namespace={}", record.action(), record.namespace());
        } catch (Exception e) {
            // Never let a DB write failure crash the controller
            LOG.error("[DB] Failed to persist hibernation record: {}", e.getMessage());
        }
    }
}