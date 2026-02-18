package io.janitor.finops.config;

import okhttp3.OkHttpClient;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Shared {@link OkHttpClient} bean + Slack/Groq property binding.
 *
 * OkHttp is used for TWO things:
 *   1. Groq API calls (send logs, receive AI decision).
 *   2. Slack webhook posts (notify the team before hibernation).
 *
 * Why a single bean?
 *   OkHttpClient owns a connection pool + thread pool.  Sharing one instance
 *   across the app keeps socket count and RAM flat.
 */
@Configuration
public class HttpConfig {

    /**
     * A single, app-wide OkHttp client with conservative timeouts.
     * Groq p99 latency is ~2 s; Slack webhooks are < 1 s.
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15))   // Groq can take a moment
                .writeTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ── Groq properties ──────────────────────────────────────────────────────
    @Component
    @ConfigurationProperties(prefix = "groq")
    public static class GroqProperties {
        private String apiKey;
        private String model;
        private String url;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String v) {
            this.apiKey = v;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String v) {
            this.model = v;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String v) {
            this.url = v;
        }
    }

    // ── Slack properties ─────────────────────────────────────────────────────
    @Component
    @ConfigurationProperties(prefix = "slack")
    public static class SlackProperties {
        private String webhookUrl;       // Set in application.yml or env
        private boolean enabled;    // Master switch — set false during local dev

        public String  getWebhookUrl()        { return webhookUrl; }
        public void    setWebhookUrl(String v) { this.webhookUrl = v; }
        public boolean isEnabled()            { return enabled; }
        public void    setEnabled(boolean v)  { this.enabled = v; }
    }
}
