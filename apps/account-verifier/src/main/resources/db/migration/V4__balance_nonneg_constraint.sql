-- Enforce at the DB level that balance can never go below zero.
-- This is the last line of defence against the TOCTOU race (two concurrent
-- transactions that both pass the application-level balance check).
-- The pessimistic lock in AccountVerificationService is the primary guard;
-- this constraint is the fallback if the lock is ever bypassed.
ALTER TABLE accounts ADD CONSTRAINT chk_balance_nonneg CHECK (balance >= 0);
