-- Hibernate 6 (Quarkus 3.15) derives the sequence name from the table name as
-- <table_name>_seq. V2 used BIGSERIAL which creates outbox_messages_id_seq (PostgreSQL
-- convention). This migration creates the sequence Hibernate actually looks up.
-- allocationSize=50 matches Hibernate's default batch allocation size.
CREATE SEQUENCE IF NOT EXISTS outbox_messages_seq
    START WITH 1
    INCREMENT BY 50;
