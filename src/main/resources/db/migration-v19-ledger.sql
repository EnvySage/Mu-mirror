-- Ledger writes belong to Java. Agents read confirmed monetary facts from the view.
CREATE TABLE IF NOT EXISTS ledger_categories (
    id VARCHAR(36) PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(60) NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ledger_category_owner_name
    ON ledger_categories (user_id, lower(name)) WHERE user_id IS NOT NULL;
INSERT INTO ledger_categories(id, name) VALUES
    ('food','餐饮'), ('transport','交通'), ('study','学习'), ('work','工作'),
    ('life','生活'), ('home','居住'), ('health','健康'), ('fun','娱乐'),
    ('salary','工资'), ('other','其他')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS ledger_records (
    id UUID PRIMARY KEY,
    source_id BIGSERIAL UNIQUE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    record_date DATE NOT NULL,
    diary_action_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(id, user_id)
);
CREATE INDEX IF NOT EXISTS idx_ledger_records_owner_day ON ledger_records(user_id, record_date DESC);

CREATE TABLE IF NOT EXISTS ledger_flows (
    id UUID PRIMARY KEY,
    record_id UUID NOT NULL,
    user_id UUID NOT NULL,
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('EXPENSE','INCOME','RECOVERY')),
    amount NUMERIC(13,2) NOT NULL CHECK (amount > 0),
    category_id VARCHAR(36) NOT NULL REFERENCES ledger_categories(id),
    occurred_on DATE NOT NULL,
    note VARCHAR(500) NOT NULL DEFAULT '',
    related_expense_id UUID,
    evidence_id BIGINT REFERENCES vault_items(id) ON DELETE SET NULL,
    position INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(id, user_id),
    FOREIGN KEY(record_id, user_id) REFERENCES ledger_records(id, user_id) ON DELETE CASCADE,
    FOREIGN KEY(related_expense_id, user_id) REFERENCES ledger_flows(id, user_id),
    CHECK (related_expense_id IS NULL OR (kind = 'RECOVERY' AND related_expense_id <> id))
);
CREATE INDEX IF NOT EXISTS idx_ledger_flows_owner_day ON ledger_flows(user_id, occurred_on DESC);
CREATE INDEX IF NOT EXISTS idx_ledger_flows_group ON ledger_flows(record_id, user_id);

CREATE TABLE IF NOT EXISTS ledger_write_receipts (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    request_id UUID NOT NULL,
    record_id UUID NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    PRIMARY KEY(user_id, request_id)
);

CREATE TABLE IF NOT EXISTS ledger_diary_links (
    record_id UUID NOT NULL,
    user_id UUID NOT NULL,
    diary_id BIGINT NOT NULL REFERENCES records(id) ON DELETE CASCADE,
    source VARCHAR(12) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','TEMPLATE','AI')),
    PRIMARY KEY(record_id, diary_id),
    FOREIGN KEY(record_id,user_id) REFERENCES ledger_records(id,user_id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ledger_diary_owner ON ledger_diary_links(user_id, diary_id);

CREATE SCHEMA IF NOT EXISTS mirror_ai;
CREATE OR REPLACE VIEW mirror_ai.ledger_cashflows WITH (security_barrier = true) AS
SELECT f.user_id, r.id AS record_id, r.title, r.description,
       f.id AS flow_id, f.kind, f.amount, f.occurred_on,
       COALESCE(original.occurred_on, f.occurred_on) AS budget_day,
       COALESCE(original.category_id, f.category_id) AS category_id,
       c.name AS category_name, f.note,
       ARRAY(SELECT l.diary_id FROM ledger_diary_links l
             JOIN records d ON d.id=l.diary_id AND d.user_id=l.user_id
             WHERE l.record_id=r.id AND l.user_id=r.user_id AND d.deleted_at IS NULL
             ORDER BY l.diary_id) AS diary_ids
FROM ledger_flows f
JOIN ledger_records r ON r.id = f.record_id AND r.user_id = f.user_id
LEFT JOIN ledger_flows original ON original.id = f.related_expense_id AND original.user_id = f.user_id
JOIN ledger_categories c ON c.id = COALESCE(original.category_id, f.category_id);
