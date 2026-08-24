-- Apply after Flyway has created the schema. Passwords are provisioned by the
-- caller and never belong in this file.

-- API: public HTTP commands and queries. Ledger/audit rows are append-only;
-- database triggers enforce the remaining invariants.
GRANT SELECT ON `__DB_NAME__`.`flyway_schema_history` TO 'demeter_api'@'%';
GRANT SELECT, INSERT ON `__DB_NAME__`.`tenants` TO 'demeter_api'@'%';
GRANT SELECT, INSERT ON `__DB_NAME__`.`users` TO 'demeter_api'@'%';
GRANT SELECT, INSERT, UPDATE ON `__DB_NAME__`.`auth_sessions` TO 'demeter_api'@'%';
GRANT SELECT, INSERT, UPDATE ON `__DB_NAME__`.`bill_code_sequences` TO 'demeter_api'@'%';
GRANT SELECT, INSERT, UPDATE ON `__DB_NAME__`.`bills` TO 'demeter_api'@'%';
GRANT SELECT, INSERT, DELETE ON `__DB_NAME__`.`bill_tags` TO 'demeter_api'@'%';
GRANT SELECT, INSERT, UPDATE ON `__DB_NAME__`.`payments` TO 'demeter_api'@'%';
GRANT SELECT, INSERT, UPDATE ON `__DB_NAME__`.`ocr_tasks` TO 'demeter_api'@'%';
GRANT SELECT, INSERT ON `__DB_NAME__`.`ocr_retry_commands` TO 'demeter_api'@'%';
GRANT SELECT, INSERT ON `__DB_NAME__`.`business_command_replays` TO 'demeter_api'@'%';
GRANT INSERT ON `__DB_NAME__`.`audit_events` TO 'demeter_api'@'%';

-- Worker: claims and updates OCR tasks, and appends audit events.
GRANT SELECT ON `__DB_NAME__`.`flyway_schema_history` TO 'demeter_worker'@'%';
GRANT SELECT, UPDATE ON `__DB_NAME__`.`ocr_tasks` TO 'demeter_worker'@'%';
GRANT INSERT ON `__DB_NAME__`.`audit_events` TO 'demeter_worker'@'%';

-- Maintenance: bounded retention cleanup, OCR lifecycle cleanup and ledger reads.
GRANT SELECT ON `__DB_NAME__`.`flyway_schema_history` TO 'demeter_maintenance'@'%';
GRANT SELECT, INSERT, UPDATE ON `__DB_NAME__`.`shedlock` TO 'demeter_maintenance'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON `__DB_NAME__`.`maintenance_runs` TO 'demeter_maintenance'@'%';
GRANT SELECT, DELETE ON `__DB_NAME__`.`auth_sessions` TO 'demeter_maintenance'@'%';
GRANT SELECT, DELETE ON `__DB_NAME__`.`business_command_replays` TO 'demeter_maintenance'@'%';
GRANT SELECT, DELETE ON `__DB_NAME__`.`ocr_retry_commands` TO 'demeter_maintenance'@'%';
GRANT SELECT, UPDATE ON `__DB_NAME__`.`ocr_tasks` TO 'demeter_maintenance'@'%';
GRANT SELECT ON `__DB_NAME__`.`bills` TO 'demeter_maintenance'@'%';
GRANT SELECT ON `__DB_NAME__`.`payments` TO 'demeter_maintenance'@'%';

FLUSH PRIVILEGES;
