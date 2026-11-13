CREATE TABLE IF NOT EXISTS ledger_recurring_rules (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    amount NUMERIC(13, 2) NOT NULL CHECK (amount > 0),
    category_id VARCHAR(64) NOT NULL,
    start_on DATE NOT NULL,
    interval_unit VARCHAR(8) NOT NULL CHECK (interval_unit IN ('DAY', 'WEEK', 'MONTH')),
    interval_count INTEGER NOT NULL CHECK (interval_count BETWEEN 1 AND 366),
    note VARCHAR(500) NOT NULL DEFAULT '',
    next_index BIGINT NOT NULL DEFAULT 0 CHECK (next_index >= 0),
    next_due_on DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (user_id, id)
);

CREATE INDEX IF NOT EXISTS idx_ledger_recurring_schedule
    ON ledger_recurring_rules (next_due_on, user_id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS ledger_recurring_occurrences (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    rule_id UUID NOT NULL,
    due_on DATE NOT NULL,
    amount NUMERIC(13, 2) NOT NULL CHECK (amount > 0),
    state VARCHAR(12) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING', 'CONFIRMED', 'SKIPPED')),
    record_id UUID,
    decided_at TIMESTAMPTZ,
    FOREIGN KEY (user_id, rule_id) REFERENCES ledger_recurring_rules(user_id, id) ON DELETE CASCADE,
    UNIQUE (user_id, rule_id, due_on),
    CHECK ((state = 'CONFIRMED') = (record_id IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_ledger_recurring_pending
    ON ledger_recurring_occurrences (user_id, due_on, id) WHERE state = 'PENDING';

CREATE TABLE IF NOT EXISTS ledger_wishes (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    link VARCHAR(2048),
    evidence_id BIGINT,
    price NUMERIC(13, 2) CHECK (price > 0),
    kind VARCHAR(12) NOT NULL CHECK (kind IN ('ONCE', 'RECURRING')),
    metric VARCHAR(12) NOT NULL CHECK (metric IN ('SAVINGS', 'INCOME', 'BALANCE')),
    start_on DATE NOT NULL,
    window_days INTEGER NOT NULL CHECK (window_days BETWEEN 7 AND 3660),
    window_mode VARCHAR(12) NOT NULL CHECK (window_mode IN ('FIXED', 'ROLLING')),
    target_amount NUMERIC(13, 2) NOT NULL CHECK (target_amount > 0),
    budget NUMERIC(13, 2) CHECK (budget >= 0),
    category_budgets JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(category_budgets) = 'object'),
    evaluated_through DATE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at TIMESTAMPTZ,
    UNIQUE (user_id, id),
    CHECK (kind <> 'RECURRING' OR window_mode = 'FIXED'),
    CHECK ((metric = 'SAVINGS' AND ((budget IS NOT NULL) <> (category_budgets <> '{}'::jsonb)))
        OR (metric <> 'SAVINGS' AND budget IS NULL AND category_budgets = '{}'::jsonb))
);

CREATE INDEX IF NOT EXISTS idx_ledger_wishes_owner
    ON ledger_wishes (user_id, created_at, id) WHERE deleted_at IS NULL;

CREATE TABLE IF NOT EXISTS ledger_wish_achievements (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    wish_id UUID NOT NULL,
    title VARCHAR(120) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    achieved_on DATE NOT NULL,
    amount NUMERIC NOT NULL,
    target_amount NUMERIC(13, 2) NOT NULL,
    budget NUMERIC,
    category_budgets JSONB NOT NULL,
    record_ids UUID[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    FOREIGN KEY (user_id, wish_id) REFERENCES ledger_wishes(user_id, id) ON DELETE CASCADE,
    UNIQUE (user_id, wish_id, period_start),
    CHECK (period_start <= period_end),
    CHECK (achieved_on BETWEEN period_start AND period_end)
);

CREATE INDEX IF NOT EXISTS idx_ledger_wish_achievements_calendar
    ON ledger_wish_achievements (user_id, achieved_on, id);
