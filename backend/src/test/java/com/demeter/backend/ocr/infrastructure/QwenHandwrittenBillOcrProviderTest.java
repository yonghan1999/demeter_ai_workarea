package com.demeter.backend.ocr.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.demeter.backend.bill.domain.BillStatus;
import com.demeter.backend.ocr.domain.OcrDocument;
import com.demeter.backend.ocr.domain.OcrRecognitionResult;
import com.demeter.backend.ocr.spi.OcrRecognitionRequest;
import com.demeter.backend.ocr.spi.OcrProviderFailure;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class QwenHandwrittenBillOcrProviderTest {

    private static final URI ENDPOINT = URI.create(
            "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions");

    @Test
    void mapsStructuredQwenResponseToOcrContract() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(ENDPOINT))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer test-key"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "id":"chatcmpl-test-001",
                                  "choices":[{"message":{"content":"{\\"bills\\":[{\\"code\\":\\"HAND-1\\",\\"shipper\\":\\"上海运输有限公司\\",\\"vehicleCargo\\":\\"9.6米高栏 / 建材\\",\\"date\\":\\"2026-08-28\\",\\"from\\":\\"上海\\",\\"to\\":\\"杭州\\",\\"amount\\":1200.50,\\"status\\":\\"UNPAID\\",\\"confidence\\":0.93,\\"fieldConfidences\\":{\\"amount\\":0.98}}]}"}}]
                                }
                                """));

        QwenHandwrittenBillOcrProvider provider = provider(builder);

        OcrRecognitionResult result = provider.recognize(request());

        assertThat(result.provider()).isEqualTo("qwen3-vl-plus");
        assertThat(result.providerRequestId()).isEqualTo("chatcmpl-test-001");
        assertThat(result.bills()).singleElement().satisfies(candidate -> {
            assertThat(candidate.externalId()).isEqualTo("task-001-1");
            assertThat(candidate.amount()).isEqualByComparingTo("1200.50");
            assertThat(candidate.status()).isEqualTo(BillStatus.UNPAID);
            assertThat(candidate.confidence()).isEqualByComparingTo("0.93");
            assertThat(candidate.fieldConfidences().get("amount")).isEqualByComparingTo("0.98");
        });
        server.verify();
    }

    @Test
    void mapsQwenRateLimitToQuotaExceededFailure() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        assertThatThrownBy(() -> provider(builder).recognize(request()))
                .isInstanceOf(OcrProviderFailure.class)
                .extracting(exception -> ((OcrProviderFailure) exception).kind())
                .isEqualTo(OcrProviderFailure.Kind.QUOTA_EXCEEDED);
        server.verify();
    }

    @Test
    void mapsLocalHandwrittenBillFixtureWithoutCallingAlibabaCloud() throws Exception {
        String fixture = """
                {"bills":[
                  {"shipper":"谭晓阳","vehicleCargo":"3山380","date":"2025-03-01","from":"小嘴头","to":"圣元路北头","amount":400.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.95,"date":0.85,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"陈明刚","vehicleCargo":"50铲车","date":"2025-03-01","from":"大院","to":"城志华鑫","amount":950.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.95,"date":0.85,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"崔","vehicleCargo":"拉拉了","date":"2025-03-01","from":"龙门顶","to":"袁家村","amount":600.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.85,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"谭晓阳","vehicleCargo":"3山380","date":"2025-03-01","from":"圣元路","to":"小嘴头","amount":400.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.95,"date":0.85,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"涂金玉","vehicleCargo":"土坦克","date":"2025-03-01","from":"即墨","to":"久合西","amount":2000.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.85,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"赵焕波","vehicleCargo":"349杨林","date":"2025-03-01","from":"琅琊","to":"禄家庄","amount":1000.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.85,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"郑吉宗","vehicleCargo":"360加长臂","date":"2025-04-01","from":"柏乡","to":"基地","amount":200.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.95,"date":0.90,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"逄非逢","vehicleCargo":"255","date":"2025-04-01","from":"大荒","to":"东新村","amount":300.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.90,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"薛福林","vehicleCargo":"305","date":"2025-04-01","from":"店头","to":"名人岛","amount":300.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.90,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"王玉龙","vehicleCargo":"铲车","date":"2025-04-01","from":"网城","to":"公司","amount":2400.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.90,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"陈辉","vehicleCargo":"神刚260","date":"2025-04-01","from":"向刚","to":"唐家庄","amount":400.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.90,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"王玉龙","vehicleCargo":"铲车","date":"2025-04-01","from":"诸城","to":"河流沟子","amount":3100.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.90,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"张金","vehicleCargo":"后院机","date":"2025-04-01","from":"铁山","to":"高尔夫","amount":300.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.90,"from":0.95,"to":0.95,"amount":0.95}},
                  {"shipper":"崔文鑫","vehicleCargo":"后八轮","date":"2025-04-01","from":"哈工大","to":"山东路桥","amount":400.00,"status":"UNPAID","confidence":0.95,"fieldConfidences":{"shipper":0.95,"vehicleCargo":0.90,"date":0.90,"from":0.95,"to":0.95,"amount":0.95}}
                ]}
                """;
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(ENDPOINT))
                .andRespond(withStatus(org.springframework.http.HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"id\":\"chatcmpl-local-fixture\",\"choices\":[{\"message\":{\"content\":"
                                + new ObjectMapper().writeValueAsString(fixture)
                                + "}}]}"));

        OcrRecognitionResult result = provider(builder).recognize(request());

        assertThat(result.providerRequestId()).isEqualTo("chatcmpl-local-fixture");
        assertThat(result.bills()).hasSize(14);
        assertThat(result.bills()).extracting(candidate -> candidate.externalId())
                .containsExactlyElementsOf(List.of(
                        "task-001-1", "task-001-2", "task-001-3", "task-001-4", "task-001-5", "task-001-6",
                        "task-001-7", "task-001-8", "task-001-9", "task-001-10", "task-001-11", "task-001-12",
                        "task-001-13", "task-001-14"));
        assertThat(result.bills()).extracting(candidate -> candidate.amount())
                .containsExactlyElementsOf(List.of(
                        new java.math.BigDecimal("400.00"), new java.math.BigDecimal("950.00"),
                        new java.math.BigDecimal("600.00"), new java.math.BigDecimal("400.00"),
                        new java.math.BigDecimal("2000.00"), new java.math.BigDecimal("1000.00"),
                        new java.math.BigDecimal("200.00"), new java.math.BigDecimal("300.00"),
                        new java.math.BigDecimal("300.00"), new java.math.BigDecimal("2400.00"),
                        new java.math.BigDecimal("400.00"), new java.math.BigDecimal("3100.00"),
                        new java.math.BigDecimal("300.00"), new java.math.BigDecimal("400.00")));
        server.verify();
    }

    private static QwenHandwrittenBillOcrProvider provider(RestClient.Builder builder) {
        return new QwenHandwrittenBillOcrProvider(
                new QwenOcrProperties(true, "test-key", ENDPOINT, "qwen3-vl-plus", 4096),
                builder,
                new ObjectMapper());
    }

    private static OcrRecognitionRequest request() {
        return new OcrRecognitionRequest(
                "task-001",
                1,
                new OcrDocument("bill.png", "image/png", "image".getBytes(StandardCharsets.UTF_8)));
    }
}
