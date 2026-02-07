-- ═══════════════════════════════════════════════════════════════
-- schema.sql  –  SQLite schema for FinOps Janitor
--
-- Runs automatically on every app startup (Spring DataSourceInitializer).
-- Uses IF NOT EXISTS so restarts are safe.
-- ═══════════════════════════════════════════════════════════════

-- ── hibernate_log ─────────────────────────────────────────────
-- Every action the Janitor takes is written here.
-- This table serves three purposes:
--   1. Audit trail ("who did what, when")
--   2. Cost-savings calculation ("sum up hibernated hours × hourly rate")
--   3. Accuracy metric ("compare AI prediction vs actual outcome")
--
-- action values: HIBERNATED | WOKEN_UP | ALERT_SENT | DRY_RUN

CREATE TABLE IF NOT EXISTS hibernate_log (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    namespace        TEXT    NOT NULL,
    action           TEXT    NOT NULL    CHECK(action IN ('HIBERNATED','WOKEN_UP','ALERT_SENT','DRY_RUN')),
    original_replicas INTEGER NOT NULL  DEFAULT 0,
    ai_safe          INTEGER NOT NULL   DEFAULT 1,    -- SQLite has no BOOLEAN; 1=true, 0=false
    ai_risk_score    INTEGER NOT NULL   DEFAULT 5,
    ai_reason        TEXT,
    actioned_at      TEXT    NOT NULL,                 -- ISO-8601 timestamp string
    dry_run          INTEGER NOT NULL   DEFAULT 0     -- 1=dry run, 0=real action
);

-- Index on namespace + actioned_at for fast history queries
CREATE INDEX IF NOT EXISTS idx_hibernate_log_namespace_time
    ON hibernate_log (namespace, actioned_at DESC);

-- Index on action for stats queries (how many HIBERNATEDs this week?)
CREATE INDEX IF NOT EXISTS idx_hibernate_log_action
    ON hibernate_log (action);
