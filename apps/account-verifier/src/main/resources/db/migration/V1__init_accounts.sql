CREATE TABLE IF NOT EXISTS accounts (
    account_id    VARCHAR(50)     PRIMARY KEY,
    customer_name VARCHAR(100)    NOT NULL,
    balance       NUMERIC(15, 2)  NOT NULL,
    status        VARCHAR(20)     NOT NULL DEFAULT 'ACTIVE'
);

INSERT INTO accounts (account_id, customer_name, balance, status) VALUES
    ('ACC-001', 'Alice Martin',   10000.00, 'ACTIVE'),
    ('ACC-002', 'Bob Johnson',     5000.00, 'ACTIVE'),
    ('ACC-003', 'Charlie Brown',      0.00, 'ACTIVE'),
    ('ACC-004', 'Diana Prince',   25000.00, 'SUSPENDED');
