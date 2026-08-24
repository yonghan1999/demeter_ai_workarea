ALTER TABLE audit_events
    MODIFY COLUMN details MEDIUMTEXT NULL;

ALTER TABLE business_command_replays
    MODIFY COLUMN response_json MEDIUMTEXT NOT NULL;
