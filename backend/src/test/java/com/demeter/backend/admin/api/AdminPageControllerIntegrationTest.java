package com.demeter.backend.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import jakarta.servlet.http.Cookie;
import org.springframework.data.domain.Page;
import com.demeter.backend.admin.security.AdminSessionService;
import com.demeter.backend.admin.application.AdminRows.BillDetailRow;

@SpringBootTest(properties = {
        "demeter.admin.enabled=true",
        "demeter.admin.access-token=0123456789abcdef0123456789abcdef"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = {"classpath:db/testdata/cleanup.sql", "classpath:db/testdata/bills-basic.sql",
        "classpath:db/testdata/ocr-pending.sql"},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AdminPageControllerIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void requiresLoginAndCsrfForManagementWrites() throws Exception {
        mockMvc.perform(get("/admin/tenants")).andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/admin/login"));
        MvcResult login = mockMvc.perform(post("/admin/login").with(csrf()).param("accessToken",
                        "0123456789abcdef0123456789abcdef"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String cookie = cookieValue(login.getResponse().getHeader("Set-Cookie"));
                mockMvc.perform(post("/admin/tenants/1001/suspend").cookie(new Cookie("DEMETER_ADMIN_SESSION", cookie))
                        .param("reason", "风控核查").param("idempotencyKey", "admin-test-1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendsTenantAndWritesAuditedSystemOperation() throws Exception {
        String cookie = loginCookie();
        mockMvc.perform(post("/admin/tenants/1001/suspend").cookie(new Cookie("DEMETER_ADMIN_SESSION", cookie)).with(csrf())
                        .param("reason", "风控核查").param("idempotencyKey", "admin-test-2"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/tenants"));
        assertThat(jdbcTemplate.queryForObject("select status from tenants where id=1001", String.class))
                .isEqualTo("SUSPENDED");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='ADMIN_TENANT_SUSPENDED' and tenant_id=1001", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void activatesTenantIdempotentlyAndRejectsAChangedReplay() throws Exception {
        jdbcTemplate.update("update tenants set status='SUSPENDED' where id=1001");
        String cookie = loginCookie();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/admin/tenants/1001/activate").cookie(adminCookie(cookie)).with(csrf())
                            .param("reason", "复核完成").param("idempotencyKey", "admin-test-activate-1"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("/admin/tenants"))
                    .andExpect(flash().attribute("message", "租户已恢复"));
        }

        mockMvc.perform(post("/admin/tenants/1001/activate").cookie(adminCookie(cookie)).with(csrf())
                        .param("reason", "不同的恢复原因").param("idempotencyKey", "admin-test-activate-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "The Idempotency-Key was already used for a different request"));

        assertThat(jdbcTemplate.queryForObject("select status from tenants where id=1001", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='ADMIN_TENANT_ACTIVATED' and tenant_id=1001",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from admin_command_replays where operation_name='admin.tenant.activate' "
                        + "and idempotency_key='admin-test-activate-1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsBlankManagementReasonWithoutChangingState() throws Exception {
        mockMvc.perform(post("/admin/tenants/1001/suspend").cookie(adminCookie(loginCookie())).with(csrf())
                        .param("reason", "   ").param("idempotencyKey", "admin-test-blank-reason"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "状态变更原因不能为空"));

        assertThat(jdbcTemplate.queryForObject("select status from tenants where id=1001", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='ADMIN_TENANT_SUSPENDED' and tenant_id=1001",
                Integer.class)).isZero();
    }

    @Test
    void disablesAndEnablesUserWithTenantScopedAudit() throws Exception {
        String cookie = loginCookie();

        mockMvc.perform(post("/admin/users/1101/disable").cookie(adminCookie(cookie)).with(csrf())
                        .param("reason", "账号风险核查").param("idempotencyKey", "admin-test-disable-user-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "用户已禁用"));
        assertThat(jdbcTemplate.queryForObject("select status from users where id=1101", String.class))
                .isEqualTo("DISABLED");

        mockMvc.perform(post("/admin/users/1101/enable").cookie(adminCookie(cookie)).with(csrf())
                        .param("reason", "风险核查通过").param("idempotencyKey", "admin-test-enable-user-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "用户已启用"));

        assertThat(jdbcTemplate.queryForObject("select status from users where id=1101", String.class))
                .isEqualTo("ACTIVE");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action in ('ADMIN_USER_DISABLED', 'ADMIN_USER_ENABLED') "
                        + "and tenant_id=1001 and aggregate_id='1101'", Integer.class)).isEqualTo(2);
    }

    @Test
    void deletesAndRestoresSingleBillWithTenantScopedAudit() throws Exception {
        String cookie = loginCookie();

        mockMvc.perform(post("/admin/bills/1201/delete").cookie(adminCookie(cookie)).with(csrf())
                        .param("reason", "重复账单核验").param("idempotencyKey", "admin-test-delete-bill-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "账单已删除"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from bills where id=1201 and deleted_at is not null "
                        + "and deleted_by is null and delete_reason='重复账单核验'", Integer.class)).isEqualTo(1);

        mockMvc.perform(post("/admin/bills/1201/restore").cookie(adminCookie(cookie)).with(csrf())
                        .param("reason", "确认并非重复账单").param("idempotencyKey", "admin-test-restore-bill-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("message", "账单已恢复"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from bills where id=1201 and deleted_at is null "
                        + "and deleted_by is null and delete_reason is null", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action in ('ADMIN_BILL_DELETED', 'ADMIN_BILL_RESTORED') "
                        + "and tenant_id=1001 and aggregate_id='1201'", Integer.class)).isEqualTo(2);
    }

    @Test
    void exposesPaymentLedgerPageAndReversesPayment() throws Exception {
        String cookie = loginCookie();
        mockMvc.perform(get("/admin/payments").cookie(new Cookie("DEMETER_ADMIN_SESSION", cookie)))
                .andExpect(status().isOk()).andExpect(view().name("admin/payments"));
        mockMvc.perform(post("/admin/payments/1401/reverse").cookie(new Cookie("DEMETER_ADMIN_SESSION", cookie)).with(csrf())
                        .param("billId", "1202").param("reason", "退款核实").param("idempotencyKey", "admin-test-3"))
                .andExpect(status().is3xxRedirection());
        assertThat(jdbcTemplate.queryForObject("select status from payments where id=1401", String.class))
                .isEqualTo("REVERSED");
        assertThat(jdbcTemplate.queryForObject("select paid_amount from bills where id=1202", java.math.BigDecimal.class))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void revokesAllUserSessionsAndWritesAudit() throws Exception {
        String cookie = loginCookie();
        mockMvc.perform(post("/admin/users/1101/revoke-sessions")
                        .cookie(new Cookie("DEMETER_ADMIN_SESSION", cookie)).with(csrf())
                        .param("reason", "设备遗失").param("idempotencyKey", "admin-test-revoke-1"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/users"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from auth_sessions where user_id=1101 and revoked_at is not null", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='ADMIN_USER_SESSIONS_REVOKED' and aggregate_id='1101'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void revokesAllTenantSessionsAndWritesAudit() throws Exception {
        String cookie = loginCookie();
        mockMvc.perform(post("/admin/tenants/1001/revoke-sessions")
                        .cookie(new Cookie("DEMETER_ADMIN_SESSION", cookie)).with(csrf())
                        .param("reason", "租户整体隔离").param("idempotencyKey", "admin-test-tenant-revoke-1"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/tenants"));
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from auth_sessions s join users u on u.id=s.user_id where u.tenant_id=1001 and s.revoked_at is not null", Integer.class))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='ADMIN_TENANT_SESSIONS_REVOKED' and aggregate_id='1001'", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void batchDeletesBillsAtomicallyAndWritesTenantScopedAudit() throws Exception {
        String cookie = loginCookie();
        mockMvc.perform(post("/admin/bills/batch-delete")
                        .cookie(adminCookie(cookie)).with(csrf())
                        .param("billIds", "1201", "2201")
                        .param("reason", "批量清理")
                        .param("idempotencyKey", "admin-test-batch-delete-1"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/bills"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from bills where id in (1201, 2201) and deleted_at is not null", Integer.class))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='ADMIN_BILLS_BATCH_DELETED' and tenant_id=1001",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='ADMIN_BILLS_BATCH_DELETED' and tenant_id=1002",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void batchDeleteRollsBackWhenAnyBillIsMissing() throws Exception {
        String cookie = loginCookie();
        mockMvc.perform(post("/admin/bills/batch-delete")
                        .cookie(adminCookie(cookie)).with(csrf())
                        .param("billIds", "1201", "999999")
                        .param("reason", "批量清理")
                        .param("idempotencyKey", "admin-test-batch-delete-rollback"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/bills"));

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from bills where id=1201 and deleted_at is not null", Integer.class))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from audit_events where action='ADMIN_BILLS_BATCH_DELETED'", Integer.class))
                .isZero();
    }

    @Test
    void filtersManagementListsAndRendersOperationalPages() throws Exception {
        String cookie = loginCookie();

        assertPageSize(get("/admin/tenants").param("keyword", "001002").param("status", "ACTIVE"), cookie, 1);
        assertPageSize(get("/admin/users").param("tenantId", "1001").param("keyword", "open-id-alpha"), cookie, 1);
        assertPageSize(get("/admin/bills").param("tenantId", "1002").param("status", "UNPAID"), cookie, 1);
        assertPageSize(get("/admin/payments").param("billId", "1202").param("status", "ACTIVE"), cookie, 1);
        assertPageSize(get("/admin/ocr").param("status", "FAILED"), cookie, 0);
        assertPageSize(get("/admin/audit").param("action", "ADMIN_"), cookie, 0);

        mockMvc.perform(get("/admin/payment-reconciliation").cookie(adminCookie(cookie)))
                .andExpect(status().isOk()).andExpect(view().name("admin/payment-reconciliation"));
    }

    @Test
    void retriesFailedOcrWithAuditedReason() throws Exception {
        jdbcTemplate.update("update ocr_tasks set status='FAILED', attempt_count=max_attempts, "
                + "last_error_code='OCR_TEST_FAILURE', last_error_message='测试失败', "
                + "completed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP where id=1501");

        mockMvc.perform(post("/admin/ocr/00000000-0000-0000-0000-000000001501/retry")
                        .cookie(adminCookie(loginCookie())).with(csrf())
                        .param("reason", "供应商恢复后重试")
                        .param("idempotencyKey", "admin-test-ocr-retry-1"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/admin/ocr"));

        assertThat(jdbcTemplate.queryForObject("select status from ocr_tasks where id=1501", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "select details from audit_events where action='ADMIN_OCR_TASK_RETRIED' and aggregate_id=?",
                String.class, "00000000-0000-0000-0000-000000001501"))
                .contains("供应商恢复后重试");
    }

    @Test
    void scopesAdminSessionCookieToApplicationContext() throws Exception {
        mockMvc.perform(post("/demeter/admin/login").contextPath("/demeter").servletPath("/admin/login")
                .with(csrf()).param("accessToken", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/demeter/admin"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Path=/demeter/admin")));
    }

    @Test
    void rendersBillDetailWithPaymentsAndAuditContext() throws Exception {
        jdbcTemplate.update("update bills set deleted_at=CURRENT_TIMESTAMP, deleted_by=1101, delete_reason='重复账单核验' where id=1202");
        MvcResult result = mockMvc.perform(get("/admin/bills/1202").cookie(adminCookie(loginCookie())))
                .andExpect(status().isOk()).andExpect(view().name("admin/bill-detail")).andReturn();
        BillDetailRow detail = (BillDetailRow) result.getModelAndView().getModel().get("detail");
        assertThat(detail.code()).isEqualTo("TR-20240515-009");
        assertThat(detail.payments()).hasSize(1);
        assertThat(detail.deletedBy()).isEqualTo(1101L);
        assertThat(detail.deleteReason()).isEqualTo("重复账单核验");
        mockMvc.perform(get("/admin/bills/1202").cookie(adminCookie(loginCookie())))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/admin/payments?billId=1202")));
    }

    @Test
    void rendersMaintenanceRunPageAsReadOnly() throws Exception {
        mockMvc.perform(get("/admin/maintenance").cookie(adminCookie(loginCookie())))
                .andExpect(status().isOk()).andExpect(view().name("admin/maintenance"));
    }

    @Test
    void rendersAuditDetailWithoutExposingPersistenceEntity() throws Exception {
        jdbcTemplate.update("insert into audit_events (tenant_id, action, aggregate_type, aggregate_id, request_id, details, created_at) "
                + "values (1001, 'TEST_AUDIT', 'BILL', '1202', 'req-admin-detail', '{\"reason\":\"核验\"}', CURRENT_TIMESTAMP)");
        Long id = jdbcTemplate.queryForObject("select max(id) from audit_events where action='TEST_AUDIT'", Long.class);

        MvcResult result = mockMvc.perform(get("/admin/audit/" + id).cookie(adminCookie(loginCookie())))
                .andExpect(status().isOk()).andExpect(view().name("admin/audit-detail")).andReturn();
        Object detail = result.getModelAndView().getModel().get("detail");
        assertThat(detail).isInstanceOf(com.demeter.backend.admin.application.AdminRows.AuditDetailRow.class);
        com.demeter.backend.admin.application.AdminRows.AuditDetailRow row =
                (com.demeter.backend.admin.application.AdminRows.AuditDetailRow) detail;
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.details()).contains("核验");
    }

    private void assertPageSize(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            String cookie, long expectedElements) throws Exception {
        MvcResult result = mockMvc.perform(request.cookie(adminCookie(cookie)))
                .andExpect(status().isOk()).andReturn();
        Page<?> page = (Page<?>) result.getModelAndView().getModel().get("page");
        assertThat(page.getTotalElements()).isEqualTo(expectedElements);
    }

    private static Cookie adminCookie(String value) {
        return new Cookie(AdminSessionService.COOKIE_NAME, value);
    }

    private String loginCookie() throws Exception {
        String cookie = cookieValue(mockMvc.perform(post("/admin/login").with(csrf()).param("accessToken",
                        "0123456789abcdef0123456789abcdef"))
                .andExpect(status().is3xxRedirection()).andReturn().getResponse().getHeader("Set-Cookie"));
        return cookie;
    }

    private static String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }
}
