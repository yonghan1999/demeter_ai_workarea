package com.demeter.backend.ocr.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.demeter.backend.ocr.domain.OcrRecognitionResult;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import com.demeter.backend.ocr.spi.OcrRecognitionRequest;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(OcrTaskControllerIntegrationTest.OcrProviderConfiguration.class)
@Sql(scripts = {
        "classpath:db/testdata/cleanup.sql",
        "classpath:db/testdata/bills-basic.sql"
}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class OcrTaskControllerIntegrationTest {

    private static final String ALPHA_TOKEN = "test-token-alpha";
    private static final String BETA_TOKEN = "test-token-beta";
    private static final byte[] VALID_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void uploadsAValidatedImageAndReturnsAPersistentPendingTask() throws Exception {
        MvcResult result = upload("ocr-upload-001", VALID_PNG, ALPHA_TOKEN)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", org.hamcrest.Matchers.matchesPattern(
                        "/api/v1/ocr/tasks/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.status", is("pending")))
                .andExpect(jsonPath("$.currency", is("CNY")))
                .andExpect(jsonPath("$.originalFilename", is("ledger.png")))
                .andReturn();

        String taskId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/ocr/tasks/{id}", taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(taskId)))
                .andExpect(jsonPath("$.status", is("pending")));

        mockMvc.perform(get("/api/v1/ocr/tasks")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void returnsTheExistingTaskForAnIdempotentUploadRetry() throws Exception {
        MvcResult first = upload("ocr-same-key", VALID_PNG, ALPHA_TOKEN)
                .andExpect(status().isAccepted())
                .andReturn();
        MvcResult second = upload("ocr-same-key", VALID_PNG, ALPHA_TOKEN)
                .andExpect(status().isAccepted())
                .andReturn();

        JsonNode firstJson = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondJson = objectMapper.readTree(second.getResponse().getContentAsString());
        assertThat(secondJson.get("id").asText()).isEqualTo(firstJson.get("id").asText());
    }

    @Test
    void rejectsADeclaredImageWhoseBytesAreNotAnImage() throws Exception {
        upload("ocr-invalid-image", new byte[] {1, 2, 3}, ALPHA_TOKEN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")));
    }

    @Test
    void neverExposesAnotherTenantsTask() throws Exception {
        MvcResult result = upload("ocr-private", VALID_PNG, ALPHA_TOKEN)
                .andExpect(status().isAccepted())
                .andReturn();
        String taskId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();

        mockMvc.perform(get("/api/v1/ocr/tasks/{id}", taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + BETA_TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void retriesAFailedTaskIdempotently() throws Exception {
        String taskId = uploadAndFailTask("ocr-to-retry", false);

        mockMvc.perform(post("/api/v1/ocr/tasks/{id}/retry", taskId)
                        .header("Idempotency-Key", "ocr-retry-001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("pending")))
                .andExpect(jsonPath("$.attemptCount", is(0)));

        mockMvc.perform(post("/api/v1/ocr/tasks/{id}/retry", taskId)
                        .header("Idempotency-Key", "ocr-retry-001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ALPHA_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("pending")));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ocr_retry_commands WHERE idempotency_key = 'ocr-retry-001'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsRetryAfterStorageCleanupHasStarted() throws Exception {
        String taskId = uploadAndFailTask("ocr-retention-expired", true);

        mockMvc.perform(post("/api/v1/ocr/tasks/{id}/retry", taskId)
                        .header("Idempotency-Key", "ocr-retry-expired-001")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ALPHA_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("BUSINESS_RULE_VIOLATION")));
    }

    private String uploadAndFailTask(String idempotencyKey, boolean cleanupStarted) throws Exception {
        MvcResult result = upload(idempotencyKey, VALID_PNG, ALPHA_TOKEN)
                .andExpect(status().isAccepted())
                .andReturn();
        String taskId = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        jdbcTemplate.update(
                """
                UPDATE ocr_tasks
                SET status = 'FAILED', completed_at = CURRENT_TIMESTAMP,
                    last_error_code = 'TEST_FAILURE', last_error_message = 'test failure',
                    storage_cleanup_started_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END
                WHERE public_id = ?
                """,
                cleanupStarted,
                taskId);
        return taskId;
    }

    private org.springframework.test.web.servlet.ResultActions upload(
            String idempotencyKey,
            byte[] content,
            String token) throws Exception {
        MockMultipartFile image = new MockMultipartFile(
                "image",
                "ledger.png",
                "image/png",
                content);
        return mockMvc.perform(multipart("/api/v1/ocr/tasks")
                .file(image)
                .header("Idempotency-Key", idempotencyKey)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class OcrProviderConfiguration {

        @Bean
        HandwrittenBillOcrProvider contractTestOcrProvider() {
            return (OcrRecognitionRequest request) ->
                    new OcrRecognitionResult("contract-test", null, List.of());
        }
    }
}
