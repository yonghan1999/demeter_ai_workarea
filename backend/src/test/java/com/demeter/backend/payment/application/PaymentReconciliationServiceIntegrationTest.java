package com.demeter.backend.payment.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = {
        "classpath:db/testdata/cleanup.sql",
        "classpath:db/testdata/bills-basic.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PaymentReconciliationServiceIntegrationTest {

    @Autowired
    PaymentReconciliationService reconciliationService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void detectsButDoesNotSilentlyRepairLedgerDiscrepancies() {
        jdbcTemplate.update(
                "UPDATE bills SET paid_amount = 100.00, status = 'PARTIALLY_PAID' WHERE id = 1201");

        PaymentReconciliationService.ReconciliationResult result = reconciliationService.run();

        assertThat(result.sampledDiscrepancies()).isEqualTo(1);
        assertThat(result.truncated()).isFalse();
        BigDecimal paidAmount = jdbcTemplate.queryForObject(
                "SELECT paid_amount FROM bills WHERE id = 1201",
                BigDecimal.class);
        assertThat(paidAmount).isEqualByComparingTo("100.00");
    }
}
