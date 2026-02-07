package io.janitor.finops.scheduler;

import io.janitor.finops.model.AIDecision;
import io.janitor.finops.model.NamespaceStatus;
import io.janitor.finops.model.ResourceStatus;
import io.janitor.finops.service.AIService;
import io.janitor.finops.service.HibernationService;
import io.janitor.finops.service.ScannerService;
import io.janitor.finops.service.SlackService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CleanupScheduler}.
 *
 * Strategy:
 *   • All four dependencies (Scanner, AI, Hibernation, Slack) are mocked.
 *   • We control exactly what the scanner returns and what the AI says,
 *     then verify the scheduler routes to the RIGHT next step.
 *   • Key scenarios:
 *       – No idle namespaces → nothing happens
 *       – One idle, AI says safe → hibernate is called
 *       – One idle, AI says broken → Slack alert, NO hibernate
 *       – Multiple idle namespaces → each is processed independently
 */
@ExtendWith(MockitoExtension.class)
class CleanupSchedulerTest {

    @Mock private ScannerService     scanner;
    @Mock private AIService          ai;
    @Mock private HibernationService hibernation;
    @Mock private SlackService       slack;
    @Mock private JdbcTemplate       jdbc;

    @InjectMocks
    private CleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Default: dry-run ON, 50 log lines (matches application.yml defaults)
        // We use reflection or just accept the defaults from @Value
        // For unit tests, the @Value fields won't be injected — we set them manually.
        setField(scheduler, "dryRun", true);
        setField(scheduler, "podLogLines", 50);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  HELPER: set private fields via reflection (for @Value fields in tests)
    // ═══════════════════════════════════════════════════════════════════════════
    private static void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Could not set field: " + fieldName, e);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SCENARIO 1: Empty cluster — nothing to do
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("When no namespaces are idle")
    class NoIdleNamespaces {

        @Test
        @DisplayName("does not call AI, Hibernate, or Slack")
        void noInteractionsWithDownstreamServices() {
            // Scanner returns one ACTIVE namespace
            when(scanner.scanAll()).thenReturn(List.of(
                    activeSnapshot("production")
            ));

            scheduler.runCleanupCycle();

            // AI should never be called — nothing is idle
            verifyNoInteractions(ai);
            verifyNoInteractions(hibernation);
            verifyNoInteractions(slack);
        }

        @Test
        @DisplayName("counters stay at zero")
        void countersAreZero() {
            when(scanner.scanAll()).thenReturn(List.of());

            scheduler.runCleanupCycle();

            assertEquals(0, scheduler.getLastCycleHibernatedCount());
            assertEquals(0, scheduler.getLastCycleAlertCount());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SCENARIO 2: One idle namespace, AI says SAFE → hibernate
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("When one namespace is idle and AI says safe")
    class IdleAndSafe {

        @Test
        @DisplayName("calls hibernate and Slack notification")
        void hibernateAndSlackAreCalled() {
            when(scanner.scanAll()).thenReturn(List.of(
                    idleSnapshot("engineering-dev")
            ));
            when(scanner.fetchPodLogs("engineering-dev", 50)).thenReturn("INFO: idle server");
            when(ai.analyzeNamespace("engineering-dev", "INFO: idle server"))
                    .thenReturn(new AIDecision(true, 2, "No errors. Safe."));

            scheduler.runCleanupCycle();

            // Slack pre-hibernate notification should fire
            verify(slack, times(1)).notifyPreHibernation(eq("engineering-dev"), any(AIDecision.class));

            // Hibernate should be called with dryRun=true (our setUp default)
            verify(hibernation, times(1)).hibernate(eq("engineering-dev"), any(AIDecision.class), eq(true));

            // BROKEN alert should NOT fire
            verify(slack, never()).notifyBrokenNamespace(any(), any());

            assertEquals(1, scheduler.getLastCycleHibernatedCount());
            assertEquals(0, scheduler.getLastCycleAlertCount());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SCENARIO 3: One idle namespace, AI says BROKEN → alert only
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("When one namespace is idle but AI says broken")
    class IdleAndBroken {

        @Test
        @DisplayName("sends Slack alert but does NOT hibernate")
        void alertSentNoHibernation() {
            when(scanner.scanAll()).thenReturn(List.of(
                    idleSnapshot("qa-testing")));
            when(scanner.fetchPodLogs(eq("qa-testing"), anyInt()))
                    .thenReturn("FATAL: OOMKilled\nERROR: CrashLoopBackOff");
            when(ai.analyzeNamespace(eq("qa-testing"), eq("FATAL: OOMKilled\nERROR: CrashLoopBackOff")))
                    .thenReturn(new AIDecision(false, 9, "Pod is crash-looping."));

            scheduler.runCleanupCycle();

            // Slack BROKEN alert should fire
            verify(slack, times(1)).notifyBrokenNamespace(eq("qa-testing"), any(AIDecision.class));

            // Hibernate should NEVER be called
            verify(hibernation, never()).hibernate(anyString(), any(AIDecision.class), anyBoolean());

            // Pre-hibernate notification should NOT fire
            verify(slack, never()).notifyPreHibernation(anyString(), any(AIDecision.class));

            assertEquals(0, scheduler.getLastCycleHibernatedCount());
            assertEquals(1, scheduler.getLastCycleAlertCount());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SCENARIO 4: Mixed — two idle namespaces, one safe, one broken
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Mixed idle namespaces — one safe, one broken")
    class MixedScenario {

        @Test
        @DisplayName("hibernates the safe one, alerts on the broken one")
        void correctRoutingForEachNamespace() {
            when(scanner.scanAll()).thenReturn(List.of(
                    idleSnapshot("safe-ns"),
                    idleSnapshot("broken-ns"),
                    activeSnapshot("active-ns") // this one should be ignored
            ));

            // safe-ns logs
            when(scanner.fetchPodLogs(eq("safe-ns"), anyInt())).thenReturn("INFO: all good");
            when(ai.analyzeNamespace(eq("safe-ns"), eq("INFO: all good")))
                    .thenReturn(new AIDecision(true, 1, "Idle."));

            // broken-ns logs
            when(scanner.fetchPodLogs(eq("broken-ns"), anyInt())).thenReturn("ERROR: connection refused");
            when(ai.analyzeNamespace(eq("broken-ns"), eq("ERROR: connection refused")))
                    .thenReturn(new AIDecision(false, 7, "Connection errors."));

            scheduler.runCleanupCycle();

            // safe-ns → hibernate
            verify(hibernation, times(1)).hibernate(eq("safe-ns"), any(AIDecision.class), eq(true));
            verify(slack, times(1)).notifyPreHibernation(eq("safe-ns"), any(AIDecision.class));

            // broken-ns → alert only
            verify(slack, times(1)).notifyBrokenNamespace(eq("broken-ns"), any(AIDecision.class));
            verify(hibernation, never()).hibernate(eq("broken-ns"), any(AIDecision.class), anyBoolean());

            assertEquals(1, scheduler.getLastCycleHibernatedCount());
            assertEquals(1, scheduler.getLastCycleAlertCount());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  SCENARIO 5: Scanner throws an exception mid-scan
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("When one namespace throws during processing")
    class ExceptionHandling {

        @Test
        @DisplayName("continues processing other namespaces without crashing")
        void doesNotCrashOnOneNamespaceError() {
            when(scanner.scanAll()).thenReturn(List.of(
                    idleSnapshot("error-ns"),
                    idleSnapshot("good-ns")));

            // error-ns: fetchPodLogs throws
            when(scanner.fetchPodLogs(eq("error-ns"), anyInt()))
                    .thenThrow(new RuntimeException("K8s API timeout"));

            // good-ns: works normally
            when(scanner.fetchPodLogs(eq("good-ns"), anyInt())).thenReturn("INFO: fine");
            when(ai.analyzeNamespace(eq("good-ns"), eq("INFO: fine")))
                    .thenReturn(new AIDecision(true, 2, "Safe."));

            // Should NOT throw — the scheduler catches per-namespace errors
            assertDoesNotThrow(() -> scheduler.runCleanupCycle());

            // good-ns should still be hibernated
            verify(hibernation, times(1)).hibernate(eq("good-ns"), any(AIDecision.class), eq(true));

            // error-ns should NOT be hibernated (it errored out)
            verify(hibernation, never()).hibernate(eq("error-ns"), any(AIDecision.class), anyBoolean());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  TEST DATA BUILDERS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Creates an ACTIVE ResourceStatus snapshot. */
    private static ResourceStatus activeSnapshot(String namespace) {
        return new ResourceStatus(namespace, 25.0, 40.0, 3, 7200, NamespaceStatus.ACTIVE, Instant.now());
    }

    /** Creates an IDLE ResourceStatus snapshot. */
    private static ResourceStatus idleSnapshot(String namespace) {
        return new ResourceStatus(namespace, 0.0, 0.0, 1, 14400, NamespaceStatus.IDLE, Instant.now());
    }
}
