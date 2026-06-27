CREATE TABLE outbox_messages (
    id          BIGSERIAL PRIMARY KEY,
    topic       VARCHAR(100)  NOT NULL,
    payload     TEXT          NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT now(),
    sent        BOOLEAN       NOT NULL DEFAULT FALSE,
    sent_at     TIMESTAMP WITH TIME ZONE
);
