package com.demeter.backend.ocr.infrastructure;

import com.demeter.backend.bill.domain.BillStatus;
import com.demeter.backend.ocr.domain.OcrBillCandidate;
import com.demeter.backend.ocr.domain.OcrRecognitionResult;
import com.demeter.backend.ocr.spi.HandwrittenBillOcrProvider;
import com.demeter.backend.ocr.spi.OcrRecognitionRequest;
import com.demeter.backend.ocr.spi.OcrRecognitionMemory;
import com.demeter.backend.ocr.spi.OcrProviderFailure;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Adapts Qwen3-VL's OpenAI-compatible chat API to the stable OCR provider contract. */
@Component
@ConditionalOnProperty(prefix = "demeter.ocr.qwen", name = "enabled", havingValue = "true")
public class QwenHandwrittenBillOcrProvider implements HandwrittenBillOcrProvider {

    private static final String PROVIDER = "qwen3-vl-plus";

    private static final String SYSTEM_PROMPT = """
            这是一张挖掘机/工程机械托运账单。请从图片中提取所有独立账单，方便导入运输账单软件。

            软件字段语义固定，但图片中的书写顺序、版式、方向、列数和是否有表头都可能不同。请根据
            表头、行结构、相对位置、重复模式和同一账单边界自行判断，不要假设固定列顺序：
            - shipper：需求人/托运人姓名，String
            - from：托运出发地，String
            - to：托运目的地，String
            - vehicleCargo：托运车型或工程机械名称，String
            - amount：托运价格，JSON number，对应 BigDecimal，不带货币符号
            - date：托运日期，格式必须为 yyyy-MM-dd

            这是真实 OCR 提取，不是数据补全。只读取图片中有视觉证据支持的内容；无法辨认、缺失、遮挡
            或存在多个可能读法时必须返回 null，禁止猜测、纠错、规范化或根据常识补全人名、地名、车型、
            货物、日期和金额。不要将表头、月份标题、合计、页码、备注或勾选标记当成账单。金额算式只在
            明确属于该账单且结果清晰时计算。每一行或明确的单据区域对应一条 bill，不要合并不同账单。

            返回且仅返回 JSON，不要 Markdown。字段名和值类型必须严格一致：
            {"bills":[{"shipper":string|null,"vehicleCargo":string|null,"date":"yyyy-MM-dd"|null,
            "from":string|null,"to":string|null,"amount":number|null,"status":"UNPAID",
            "confidence":number,"fieldConfidences":object}]}

            每个非空核心字段都必须有独立 fieldConfidences（0 到 1），不要默认所有字段相同。整体 confidence
            不得高于核心字段最低置信度；任一核心字段为 null 时不得高于 0.60。置信度只反映视觉证据，
            不反映字段是否看起来合理。

            请求中可能附带当前租户历史账单词典。词典只用于帮助核对手写字形和消歧，不是事实来源；
            只有图片中的文字与词典及位置证据一致时才可采用。严禁把词典中的值凭空填入图片中不存在
            的字段，也不得将其他租户或未提供的历史值带入结果。
            """;

    private static final String USER_PROMPT = """
            请识别这张图片中的所有挖掘机或工程机械托运账单，并严格按照系统提示中的 JSON 结构返回。
            图片中信息的顺序可能不同，请先判断每条账单的边界和字段归属，再输出结果；不要把相邻字段拼接。
            """;

    private final QwenOcrProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public QwenHandwrittenBillOcrProvider(
            QwenOcrProperties properties,
            RestClient.Builder restClientBuilder,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.restClient = restClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public OcrRecognitionResult recognize(OcrRecognitionRequest request) {
        try {
            JsonNode response = restClient.post()
                    .uri(properties.chatCompletionsUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .body(buildRequest(request))
                    .retrieve()
                    .body(JsonNode.class);
            return parseResponse(response, request.taskId());
        } catch (OcrProviderFailure exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw httpFailure(exception);
        } catch (RestClientException exception) {
            throw new OcrProviderFailure(
                    OcrProviderFailure.Kind.TRANSIENT,
                    "Qwen OCR service is temporarily unavailable",
                    exception);
        } catch (RuntimeException exception) {
            throw new OcrProviderFailure(
                    OcrProviderFailure.Kind.PERMANENT,
                    "Qwen OCR returned an invalid recognition result",
                    exception);
        }
    }

    private Map<String, Object> buildRequest(OcrRecognitionRequest request) {
        String dataUrl = "data:" + request.document().contentType() + ";base64,"
                + Base64.getEncoder().encodeToString(request.document().content());
        return Map.of(
                "model", properties.model(),
                "temperature", 0,
                "max_tokens", properties.maxOutputTokens(),
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of(
                                "role", "user",
                                "content", List.of(
                                        Map.of("type", "text", "text", USER_PROMPT + "\n" + memoryPrompt(request.memory())),
                                        Map.of(
                                                "type", "image_url",
                                                "image_url", Map.of("url", dataUrl))))));
    }

    private static String memoryPrompt(OcrRecognitionMemory memory) {
        return "当前租户历史账单记忆（仅作视觉消歧候选，不得臆测）：\\n"
                + "托运人候选: " + String.join("、", memory.shippers()) + "\\n"
                + "出发地候选: " + String.join("、", memory.origins()) + "\\n"
                + "目的地候选: " + String.join("、", memory.destinations()) + "\\n"
                + "车型/货物候选: " + String.join("、", memory.vehicleCargo());
    }

    private OcrRecognitionResult parseResponse(JsonNode response, String taskId) {
        if (response == null) {
            throw invalidResponse("Qwen OCR returned an empty response");
        }
        JsonNode content = response.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.textValue().isBlank()) {
            throw invalidResponse("Qwen OCR did not return a structured result");
        }
        JsonNode result = readJsonObject(removeMarkdownFence(content.textValue()));
        JsonNode bills = result.path("bills");
        if (!bills.isArray()) {
            throw invalidResponse("Qwen OCR result does not contain a bill list");
        }
        List<OcrBillCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (JsonNode bill : bills) {
            if (!bill.isObject()) {
                throw invalidResponse("Qwen OCR result contains an invalid bill");
            }
            index += 1;
            candidates.add(toCandidate(bill, taskId, index));
        }
        String providerRequestId = text(response, "id");
        return new OcrRecognitionResult(PROVIDER, providerRequestId, List.copyOf(candidates));
    }

    private OcrBillCandidate toCandidate(JsonNode value, String taskId, int index) {
        String externalId = text(value, "externalId");
        if (externalId == null) {
            externalId = taskId + "-" + index;
        }
        String shipper = text(value, "shipper");
        String vehicleCargo = text(value, "vehicleCargo");
        LocalDate date = date(value, "date");
        String origin = text(value, "from");
        String destination = text(value, "to");
        BigDecimal amount = decimal(value, "amount");
        Map<String, BigDecimal> fieldConfidences = fieldConfidences(value.path("fieldConfidences"));
        return new OcrBillCandidate(
                externalId,
                text(value, "code"),
                shipper,
                vehicleCargo,
                date,
                origin,
                destination,
                amount,
                status(value),
                calibrateConfidence(
                        decimal(value, "confidence"),
                        fieldConfidences,
                        shipper,
                        vehicleCargo,
                        date,
                        origin,
                        destination,
                        amount),
                fieldConfidences);
    }

    private static BigDecimal calibrateConfidence(
            BigDecimal reported,
            Map<String, BigDecimal> fieldConfidences,
            String shipper,
            String vehicleCargo,
            LocalDate date,
            String origin,
            String destination,
            BigDecimal amount) {
        List<BigDecimal> core = List.of("shipper", "vehicleCargo", "date", "from", "to", "amount").stream()
                .map(fieldConfidences::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        boolean incomplete = shipper == null || vehicleCargo == null || date == null
                || origin == null || destination == null || amount == null;
        if (incomplete) {
            return minimum(reported, new BigDecimal("0.60"));
        }
        if (core.isEmpty()) {
            return reported;
        }
        BigDecimal weakest = core.stream().min(BigDecimal::compareTo).orElseThrow();
        return minimum(reported, weakest);
    }

    private static BigDecimal minimum(BigDecimal first, BigDecimal second) {
        if (first == null) {
            return second;
        }
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static String text(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw invalidResponse("Qwen OCR " + field + " must be text");
        }
        String result = node.textValue().trim();
        return result.isEmpty() ? null : result;
    }

    private static LocalDate date(JsonNode value, String field) {
        String raw = text(value, field);
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException exception) {
            throw invalidResponse("Qwen OCR date is invalid", exception);
        }
    }

    private static BigDecimal decimal(JsonNode value, String field) {
        JsonNode node = value.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual()) {
                String raw = node.textValue().trim().replace(",", "").replace("¥", "");
                return raw.isEmpty() ? null : new BigDecimal(raw);
            }
        } catch (NumberFormatException exception) {
            throw invalidResponse("Qwen OCR " + field + " is invalid", exception);
        }
        throw invalidResponse("Qwen OCR " + field + " must be a number");
    }

    private static BillStatus status(JsonNode value) {
        String raw = text(value, "status");
        if (raw == null) {
            return BillStatus.UNPAID;
        }
        try {
            return BillStatus.fromValue(raw);
        } catch (IllegalArgumentException exception) {
            throw invalidResponse("Qwen OCR status is invalid", exception);
        }
    }

    private static Map<String, BigDecimal> fieldConfidences(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw invalidResponse("Qwen OCR field confidences must be an object");
        }
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        node.properties().forEach(entry -> values.put(entry.getKey(), decimalValue(entry.getValue(), entry.getKey())));
        return Map.copyOf(values);
    }

    private static BigDecimal decimalValue(JsonNode node, String field) {
        try {
            if (node.isNumber()) {
                return node.decimalValue();
            }
            if (node.isTextual()) {
                return new BigDecimal(node.textValue().trim());
            }
        } catch (NumberFormatException exception) {
            throw invalidResponse("Qwen OCR confidence " + field + " is invalid", exception);
        }
        throw invalidResponse("Qwen OCR confidence " + field + " must be a number");
    }

    private JsonNode readJsonObject(String value) {
        try {
            JsonNode parsed = objectMapper.readTree(value);
            if (parsed == null || !parsed.isObject()) {
                throw invalidResponse("Qwen OCR result must be a JSON object");
            }
            return parsed;
        } catch (JsonProcessingException exception) {
            throw invalidResponse("Qwen OCR result is not valid JSON", exception);
        }
    }

    private static String removeMarkdownFence(String value) {
        String trimmed = value.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstNewline = trimmed.indexOf('\n');
        int lastFence = trimmed.lastIndexOf("```");
        if (firstNewline < 0 || lastFence <= firstNewline) {
            throw invalidResponse("Qwen OCR returned an invalid Markdown fence");
        }
        return trimmed.substring(firstNewline + 1, lastFence).trim();
    }

    private static OcrProviderFailure httpFailure(RestClientResponseException exception) {
        HttpStatusCode status = exception.getStatusCode();
        OcrProviderFailure.Kind kind = switch (status.value()) {
            case 400, 413, 415, 422 -> OcrProviderFailure.Kind.INVALID_INPUT;
            case 429 -> OcrProviderFailure.Kind.QUOTA_EXCEEDED;
            case 408, 409, 425, 500, 502, 503, 504 -> OcrProviderFailure.Kind.TRANSIENT;
            default -> OcrProviderFailure.Kind.PERMANENT;
        };
        return new OcrProviderFailure(kind, "Qwen OCR request was rejected", exception);
    }

    private static OcrProviderFailure invalidResponse(String message) {
        return new OcrProviderFailure(OcrProviderFailure.Kind.PERMANENT, message);
    }

    private static OcrProviderFailure invalidResponse(String message, Exception cause) {
        return new OcrProviderFailure(OcrProviderFailure.Kind.PERMANENT, message, cause);
    }
}
