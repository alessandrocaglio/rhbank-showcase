CREATE TABLE IF NOT EXISTS transaction_ledger (
    transaction_id       VARCHAR(50)     PRIMARY KEY,
    source_account       VARCHAR(50)     NOT NULL,
    destination_account  VARCHAR(50)     NOT NULL,
    amount               NUMERIC(15, 2)  NOT NULL,
    currency             VARCHAR(10)     NOT NULL DEFAULT 'USD',
    status               VARCHAR(20)     NOT NULL,
    created_at           TIMESTAMP       NOT NULL DEFAULT NOW()
);
