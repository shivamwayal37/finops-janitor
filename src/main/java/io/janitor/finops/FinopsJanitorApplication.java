package io.janitor.finops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * FinOps Janitor — lightweight, AI-augmented Kubernetes controller.
 *
 * Startup checklist (what happens when this class boots):
 *   1. {@link config.KubernetesConfig}   – wires the K8s ApiClient (in-cluster OR kubeconfig file).
 *   2. {@link config.DatabaseConfig}     – creates the SQLite schema (hibernate_log table).
 *   3. {@link scheduler.CleanupScheduler} – registers the @Scheduled cron job.
 *   4. {@link controller.JanitorController} – exposes REST endpoints for manual triggers & status.
 */
@SpringBootApplication
@EnableScheduling
public class FinopsJanitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinopsJanitorApplication.class, args);
    }
}
