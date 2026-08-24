INSERT INTO tenants (id, public_id, name, status, created_at, updated_at)
VALUES
    (1001, '00000000-0000-0000-0000-000000001001', '甲方物流账本', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1002, '00000000-0000-0000-0000-000000001002', '乙方物流账本', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO users (
    id, tenant_id, open_id, union_id, display_name, status, version, created_at, updated_at)
VALUES
    (1101, 1001, 'test-open-id-alpha', NULL, '测试用户甲', 'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1102, 1002, 'test-open-id-beta', NULL, '测试用户乙', 'ACTIVE', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO auth_sessions (id, user_id, token_hash, expires_at, revoked_at, created_at)
VALUES
    ('00000000-0000-0000-0000-000000001101', 1101,
     '05272b9d20f61cbfc976ef4e81c93161fd0224f7a4ccb1f5e92dfa7241a61bb5',
     '2099-01-01 00:00:00', NULL, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000001102', 1102,
     '09454e35b80939a2d12023fad0ea0f343068522fb530476d6b7f47dd4d2720b5',
     '2099-01-01 00:00:00', NULL, CURRENT_TIMESTAMP);

INSERT INTO bill_code_sequences (id, tenant_id, sequence_name, next_value)
VALUES
    (1301, 1001, 'bill', 100),
    (1302, 1002, 'bill', 100);

INSERT INTO bills (
    id, tenant_id, code, shipper, shipper_normalized, vehicle_cargo,
    transport_date, origin, destination, amount, paid_amount, status, due_date, version,
    created_by, updated_by, deleted_by, delete_reason, deleted_at, created_at, updated_at)
VALUES
    (1201, 1001, 'TR-20240520-001', '张三物流有限公司', '张三物流', '9.6米高栏 / 煤炭',
     '2024-05-20', '上海', '北京', 4500.00, 0.00, 'UNPAID', '2024-05-23', 0,
     1101, 1101, NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (1202, 1001, 'TR-20240515-009', '李四物流个体户', '李四物流个体户', '6.8米中卡 / 农产品',
     '2024-05-15', '成都', '重庆', 2100.00, 2100.00, 'PAID', '2024-05-17', 0,
     1101, 1101, NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2201, 1002, 'TR-20240501-001', '乙方专属物流', '乙方专属物流', '厢式货车 / 设备',
     '2024-05-01', '杭州', '苏州', 8000.00, 0.00, 'UNPAID', '2024-05-04', 0,
     1102, 1102, NULL, NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO bill_tags (bill_id, tag)
VALUES
    (1201, '本月活跃'),
    (1202, '待核销'),
    (2201, '乙方数据');

INSERT INTO payments (
    id, tenant_id, bill_id, amount, method, paid_at, reference_no, note,
    idempotency_key, request_hash, status, version, reversed_at, reversed_by,
    reversal_reason, reversal_idempotency_key, reversal_request_hash, created_by, created_at)
VALUES
    (1401, 1001, 1202, 2100.00, 'BANK_TRANSFER', CURRENT_TIMESTAMP,
     'FIXTURE-1202', '测试初始化收款', 'fixture-payment-1202',
     '0000000000000000000000000000000000000000000000000000000000001202',
     'ACTIVE', 0, NULL, NULL, NULL, NULL, NULL, 1101, CURRENT_TIMESTAMP);
