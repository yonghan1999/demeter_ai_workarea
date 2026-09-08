package com.demeter.backend.admin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

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
@Sql(scripts = {"classpath:db/testdata/cleanup.sql", "classpath:db/testdata/bills-basic.sql"},
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
    void scopesAdminSessionCookieToApplicationContext() throws Exception {
        mockMvc.perform(post("/demeter/admin/login").contextPath("/demeter").servletPath("/admin/login")
                .with(csrf()).param("accessToken", "0123456789abcdef0123456789abcdef"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/demeter/admin"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Path=/demeter/admin")));
    }

    @Test
    void rendersBillDetailWithPaymentsAndAuditContext() throws Exception {
        MvcResult result = mockMvc.perform(get("/admin/bills/1202").cookie(adminCookie(loginCookie())))
                .andExpect(status().isOk()).andExpect(view().name("admin/bill-detail")).andReturn();
        BillDetailRow detail = (BillDetailRow) result.getModelAndView().getModel().get("detail");
        assertThat(detail.code()).isEqualTo("TR-20240515-009");
        assertThat(detail.payments()).hasSize(1);
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
