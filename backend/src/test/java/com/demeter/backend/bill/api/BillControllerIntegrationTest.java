package com.demeter.backend.bill.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = {
        "classpath:db/testdata/cleanup.sql",
        "classpath:db/testdata/bills-basic.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BillControllerIntegrationTest {

    private static final String ALPHA_TOKEN = "test-token-alpha";
    private static final String BETA_TOKEN = "test-token-beta";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void rejectsUnauthenticatedRequests() throws Exception {
        mockMvc.perform(get("/api/v1/bills"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void listsBillsWithStatusAndKeywordFilters() throws Exception {
        mockMvc.perform(authenticated(get("/api/v1/bills")
                        .param("status", "unpaid")
                        .param("keyword", "上海"), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].currency", is("CNY")))
                .andExpect(jsonPath("$.content[0].shipper", is("张三物流有限公司")))
                .andExpect(jsonPath("$.content[0].status", is("unpaid")))
                .andExpect(jsonPath("$.content[0].statusText", is("未收款")))
                .andExpect(jsonPath("$.totalElements", is(1)));
    }

    @Test
    void searchesByCombinedRoute() throws Exception {
        mockMvc.perform(authenticated(get("/api/v1/bills")
                        .param("keyword", "上海 → 北京"), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].code", is("TR-20240520-001")));
    }

    @Test
    void neverExposesAnotherTenantsBills() throws Exception {
        mockMvc.perform(authenticated(get("/api/v1/bills"), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));

        mockMvc.perform(authenticated(get("/api/v1/bills/{id}", 2201), ALPHA_TOKEN))
                .andExpect(status().isNotFound());

        mockMvc.perform(authenticated(get("/api/v1/bills/{id}", 2201), BETA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipper", is("乙方专属物流")));
    }

    @Test
    void rejectsInvalidPaginationAsValidationProblem() throws Exception {
        mockMvc.perform(authenticated(get("/api/v1/bills").param("size", "0"), ALPHA_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
    }

    @Test
    void createsUpdatesAndMarksBillPaidThroughTheFullBusinessPath() throws Exception {
        MvcResult createResult = mockMvc.perform(authenticated(post("/api/v1/bills")
                        .header("Idempotency-Key", "bill-create-workflow-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBillJson("王五货运队", "武汉", "广州")), ALPHA_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(header().string(
                        "Location",
                        org.hamcrest.Matchers.matchesPattern("/api/v1/bills/\\d+")))
                .andExpect(jsonPath("$.code", org.hamcrest.Matchers.matchesPattern("TR-\\d{8}-\\d+")))
                .andReturn();

        JsonNode created = objectMapper.readTree(createResult.getResponse().getContentAsString());
        long id = created.get("id").asLong();

        String updateBody = validBillJson("王五运输有限公司", "武汉", "深圳");
        MvcResult updateResult = mockMvc.perform(authenticated(put("/api/v1/bills/{id}", id)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "bill-update-workflow-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shipper", is("王五运输有限公司")))
                .andExpect(jsonPath("$.to", is("深圳")))
                .andReturn();

        MvcResult updateRetry = mockMvc.perform(authenticated(put("/api/v1/bills/{id}", id)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "bill-update-workflow-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(updateRetry.getResponse().getContentAsString())
                .isEqualTo(updateResult.getResponse().getContentAsString());

        String paymentBody = "{\"amount\":3200.50,\"method\":\"bank_transfer\","
                + "\"referenceNo\":\"WORKFLOW-001\"}";
        mockMvc.perform(authenticated(post("/api/v1/bills/{id}/payments", id)
                        .header("Idempotency-Key", "bill-payment-workflow-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody), ALPHA_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.billStatus", is("paid")))
                .andExpect(jsonPath("$.billOutstandingAmount", is(0.0)));

        mockMvc.perform(authenticated(post("/api/v1/bills/{id}/payments", id)
                        .header("Idempotency-Key", "bill-payment-workflow-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(paymentBody), ALPHA_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.billStatus", is("paid")));

        mockMvc.perform(authenticated(get("/api/v1/bills/{id}", id), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("paid")))
                .andExpect(jsonPath("$.statusText", is("已收款")));

        Integer billAuditCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = 1001 AND aggregate_id = ?",
                Integer.class,
                String.valueOf(id));
        assertThat(billAuditCount).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE tenant_id = 1001",
                Integer.class)).isEqualTo(3);
    }

    @Test
    void doesNotExposeADirectPaymentStatusMutationEndpoint() throws Exception {
        mockMvc.perform(authenticated(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .patch("/api/v1/bills/{id}/status", 1201)
                                .header("Idempotency-Key", "forbidden-status-mutation")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"paid\"}"),
                        ALPHA_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsAnUpdateWhenTheClientUsesAStaleVersion() throws Exception {
        mockMvc.perform(authenticated(put("/api/v1/bills/{id}", 1201)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "bill-update-first-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBillJson("第一次修改", "上海", "北京")), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""));

        mockMvc.perform(authenticated(put("/api/v1/bills/{id}", 1201)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .header("Idempotency-Key", "bill-update-stale-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBillJson("过期修改", "上海", "北京")), ALPHA_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RESOURCE_CONFLICT")));

        mockMvc.perform(authenticated(get("/api/v1/bills/{id}", 1201), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.shipper", is("第一次修改")));
    }

    @Test
    void requiresIfMatchForBillUpdates() throws Exception {
        mockMvc.perform(authenticated(put("/api/v1/bills/{id}", 1201)
                        .header("Idempotency-Key", "bill-update-no-version-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBillJson("无版本修改", "上海", "北京")), ALPHA_TOKEN))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code", is("PRECONDITION_REQUIRED")));
    }

    @Test
    void rejectsInvalidRouteWithProblemDetails() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/bills")
                        .header("Idempotency-Key", "bill-invalid-route-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBillJson("王五货运队", "武汉", "武汉")), ALPHA_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void createsOnlyOneBillForAnIdempotentRetryAndRejectsDifferentContent() throws Exception {
        String firstBody = validBillJson("幂等托运人", "南京", "合肥");

        MvcResult first = mockMvc.perform(authenticated(post("/api/v1/bills")
                        .header("Idempotency-Key", "bill-create-same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstBody), ALPHA_TOKEN))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(authenticated(post("/api/v1/bills")
                        .header("Idempotency-Key", "bill-create-same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstBody), ALPHA_TOKEN))
                .andExpect(status().isCreated())
                .andReturn();

        long firstId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asLong();
        long secondId = objectMapper.readTree(second.getResponse().getContentAsString()).get("id").asLong();
        assertThat(secondId).isEqualTo(firstId);

        mockMvc.perform(authenticated(post("/api/v1/bills")
                        .header("Idempotency-Key", "bill-create-same-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validBillJson("不同托运人", "南京", "合肥")), ALPHA_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RESOURCE_CONFLICT")));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bills WHERE tenant_id = 1001 AND creation_idempotency_key = ?",
                Integer.class,
                "bill-create-same-key");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void batchDeleteIsAtomicWhenOneBillIsMissing() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/bills/batch-delete")
                        .header("Idempotency-Key", "bill-delete-batch-missing-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[1201,999999],\"reason\":\"重复录入\"}"), ALPHA_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("RESOURCE_NOT_FOUND")));

        mockMvc.perform(authenticated(get("/api/v1/bills/{id}", 1201), ALPHA_TOKEN))
                .andExpect(status().isOk());

        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bills WHERE id = 1201 AND deleted_at IS NOT NULL",
                Integer.class);
        assertThat(deleted).isZero();
    }

    @Test
    void softDeletesOneBillAndWritesAnAuditRecord() throws Exception {
        mockMvc.perform(authenticated(delete("/api/v1/bills/{id}", 1201)
                        .header("Idempotency-Key", "bill-delete-single-001")
                        .param("reason", "录入错误"), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok", is(true)))
                .andExpect(jsonPath("$.count", is(1)));

        mockMvc.perform(authenticated(delete("/api/v1/bills/{id}", 1201)
                        .header("Idempotency-Key", "bill-delete-single-001")
                        .param("reason", "录入错误"), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count", is(1)));

        mockMvc.perform(authenticated(get("/api/v1/bills/{id}", 1201), ALPHA_TOKEN))
                .andExpect(status().isNotFound());

        Map<String, Object> deleted = jdbcTemplate.queryForMap(
                "SELECT deleted_by, delete_reason, deleted_at FROM bills WHERE id = 1201");
        assertThat(((Number) deleted.get("deleted_by")).longValue()).isEqualTo(1101);
        assertThat(deleted.get("delete_reason")).isEqualTo("录入错误");
        assertThat(deleted.get("deleted_at")).isNotNull();

        Integer audits = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'BILL_DELETED' AND tenant_id = 1001",
                Integer.class);
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void restoresASoftDeletedBillIdempotentlyAndKeepsItTenantScoped() throws Exception {
        mockMvc.perform(authenticated(delete("/api/v1/bills/{id}", 1201)
                        .header("Idempotency-Key", "bill-delete-before-restore-001")
                        .param("reason", "误删"), ALPHA_TOKEN))
                .andExpect(status().isOk());

        String restoreBody = "{\"reason\":\"客服核实后恢复\"}";
        mockMvc.perform(authenticated(post("/api/v1/bills/{id}/restore", 1201)
                        .header("Idempotency-Key", "bill-restore-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restoreBody), BETA_TOKEN))
                .andExpect(status().isNotFound());

        mockMvc.perform(authenticated(post("/api/v1/bills/{id}/restore", 1201)
                        .header("Idempotency-Key", "bill-restore-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restoreBody), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.id", is(1201)));

        mockMvc.perform(authenticated(post("/api/v1/bills/{id}/restore", 1201)
                        .header("Idempotency-Key", "bill-restore-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(restoreBody), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(1201)));

        mockMvc.perform(authenticated(get("/api/v1/bills/{id}", 1201), ALPHA_TOKEN))
                .andExpect(status().isOk());
        Map<String, Object> restored = jdbcTemplate.queryForMap(
                "SELECT deleted_by, delete_reason, deleted_at FROM bills WHERE id = 1201");
        assertThat(restored.get("deleted_by")).isNull();
        assertThat(restored.get("delete_reason")).isNull();
        assertThat(restored.get("deleted_at")).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_events WHERE action = 'BILL_RESTORED' AND tenant_id = 1001",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void resolvesEquivalentShipperNameInsideTheTenant() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/bills/resolve-shipper")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三物流公司\"}"), ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists", is(true)))
                .andExpect(jsonPath("$.value", is("张三物流有限公司")));

        mockMvc.perform(authenticated(post("/api/v1/bills/resolve-shipper")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三物流公司\"}"), BETA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exists", is(false)));
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private String validBillJson(String shipper, String from, String to) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "shipper", shipper,
                "vehicleCargo", "9.6米高栏 / 建材",
                "date", "2024-06-01",
                "from", from,
                "to", to,
                "amount", 3200.50,
                "status", "unpaid",
                "dueDate", "2024-06-03",
                "tags", java.util.List.of("新账单")));
    }
}
