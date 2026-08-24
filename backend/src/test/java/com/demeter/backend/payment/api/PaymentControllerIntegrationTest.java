package com.demeter.backend.payment.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
class PaymentControllerIntegrationTest {

    private static final String TOKEN = "test-token-alpha";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void recordsPartialPaymentAndReturnsTheSameResultForAnIdempotentRetry() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "amount", 1500.00,
                "method", "bank_transfer",
                "referenceNo", "BANK-20240822-001",
                "note", "首笔收款"));

        MvcResult first = mockMvc.perform(authenticated(post("/api/v1/bills/{billId}/payments", 1201)
                        .header("Idempotency-Key", "payment-create-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency", is("CNY")))
                .andExpect(jsonPath("$.billPaidAmount", is(1500.0)))
                .andExpect(jsonPath("$.billOutstandingAmount", is(3000.0)))
                .andExpect(jsonPath("$.billStatus", is("partially_paid")))
                .andReturn();

        long paymentId = objectMapper.readTree(first.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(authenticated(post("/api/v1/bills/{billId}/payments", 1201)
                        .header("Idempotency-Key", "payment-create-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is((int) paymentId)))
                .andExpect(jsonPath("$.billPaidAmount", is(1500.0)));

        Integer payments = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE bill_id = 1201",
                Integer.class);
        assertThat(payments).isEqualTo(1);
    }

    @Test
    void rejectsReusingAnIdempotencyKeyWithDifferentPaymentData() throws Exception {
        createPayment("same-key", 500.00)
                .andExpect(status().isCreated());

        createPayment("same-key", 600.00)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RESOURCE_CONFLICT")));
    }

    @Test
    void preventsOverpaymentAndRollsBackTheWholeTransaction() throws Exception {
        createPayment("too-large", 5000.00)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")));

        Map<String, Object> bill = jdbcTemplate.queryForMap(
                "SELECT paid_amount, status FROM bills WHERE id = 1201");
        assertThat(((java.math.BigDecimal) bill.get("paid_amount"))).isEqualByComparingTo("0.00");
        assertThat(bill.get("status")).isEqualTo("UNPAID");
    }

    @Test
    void reversesAPaymentWithoutDeletingItsHistory() throws Exception {
        MvcResult created = createPayment("to-reverse", 1000.00)
                .andExpect(status().isCreated())
                .andReturn();
        long paymentId = objectMapper.readTree(created.getResponse().getContentAsString()).get("id").asLong();

        mockMvc.perform(authenticated(post(
                                "/api/v1/bills/{billId}/payments/{paymentId}/reversal",
                                1201,
                                paymentId)
                        .header("Idempotency-Key", "reverse-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"银行退回\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("reversed")))
                .andExpect(jsonPath("$.billPaidAmount", is(0.0)))
                .andExpect(jsonPath("$.billStatus", is("unpaid")));

        mockMvc.perform(authenticated(get("/api/v1/bills/{billId}/payments", 1201)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].id", is((int) paymentId)))
                .andExpect(jsonPath("$.content[0].reversalReason", is("银行退回")));
    }

    @Test
    void doesNotExposeAnotherTenantsPaymentLedger() throws Exception {
        mockMvc.perform(authenticated(get("/api/v1/bills/{billId}/payments", 2201)))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejectsWritesWhenTheBillSummaryDoesNotMatchTheLedger() throws Exception {
        jdbcTemplate.update(
                "UPDATE bills SET paid_amount = 100.00, status = 'PARTIALLY_PAID' WHERE id = 1201");

        createPayment("inconsistent-ledger", 100.00)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("RESOURCE_CONFLICT")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE bill_id = 1201",
                Integer.class)).isZero();
    }

    private org.springframework.test.web.servlet.ResultActions createPayment(String key, double amount)
            throws Exception {
        return mockMvc.perform(authenticated(post("/api/v1/bills/{billId}/payments", 1201)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "amount", amount,
                        "method", "cash")))));
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
    }
}
