ALTER TABLE outbox_messages ADD COLUMN traceparent VARCHAR(55);
ALTER TABLE outbox_messages ADD COLUMN tracestate  VARCHAR(512);
