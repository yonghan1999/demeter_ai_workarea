package com.demeter.backend.ocr.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Sql(scripts = {
        "classpath:db/testdata/cleanup.sql",
        "classpath:db/testdata/bills-basic.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class OcrProviderUnavailableIntegrationTest {

    private static final byte[] VALID_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void rejectsUploadsWithoutPersistingAnythingWhenNoProviderIsConfigured() throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "ledger.png",
                "image/png",
                VALID_PNG);

        mockMvc.perform(multipart("/api/v1/ocr/tasks")
                        .file(image)
                        .header("Idempotency-Key", "ocr-provider-missing")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer test-token-alpha"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code", is("OCR_NOT_CONFIGURED")));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ocr_tasks", Integer.class)).isZero();
    }
}
