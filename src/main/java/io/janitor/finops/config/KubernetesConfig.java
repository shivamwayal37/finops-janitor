package io.janitor.finops.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileReader;
import java.nio.file.Path;

/**
 * Builds a single {@link ApiClient} bean that every service in the app shares.
 *
 * Resolution order (first match wins):
 *   1. In-cluster config  – when the JAR runs inside a K8s Pod itself.
 *   2. KUBECONFIG env var  – explicit path (CI/CD pipelines).
 *   3. ~/.kube/config      – default developer laptop path.
 *
 * Why a singleton?
 *   ApiClient owns an HTTP connection pool.  Reusing one instance keeps memory
 *   and open-file-descriptor count flat — critical on an 8 GB machine.
 */
@Configuration
public class KubernetesConfig {

    private static final Logger LOG = LoggerFactory.getLogger(KubernetesConfig.class);

    @Bean
    public ApiClient apiClient() throws Exception {

        // ── 1. Try in-cluster first (Production / Pod environment) ──────────
        try {
            ApiClient client = ClientBuilder.cluster().build();
            LOG.info("[K8s] Using IN-CLUSTER config.");
            return client;
        } catch (Exception ignored) {
            // Not running inside a cluster — fall through.
        }

        // ── 2. Resolve kubeconfig file path ────────────────────────────────
        String kubeConfigPath = System.getenv("KUBECONFIG");

        if (kubeConfigPath == null || kubeConfigPath.isBlank()) {
            kubeConfigPath = Path.of(System.getProperty("user.home"), ".kube", "config")
                    .toString();
        }

        // ── 3. Load file-based config ──────────────────────────────────────
        try (FileReader reader = new FileReader(kubeConfigPath)) {
            KubeConfig kubeConfig = KubeConfig.loadKubeConfig(reader);
            ApiClient client = ClientBuilder.kubeconfig(kubeConfig).build();
            
            LOG.info("[K8s] Using FILE config: {}", kubeConfigPath);
            return client;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[K8s] No kubeconfig found at: " + kubeConfigPath +
                    "\n      Set KUBECONFIG env var or place config at ~/.kube/config", e);
        }
    }
}