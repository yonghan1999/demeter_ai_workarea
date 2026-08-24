package com.demeter.backend.ocr.api;

import com.demeter.backend.bill.api.PageResponse;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.ocr.application.OcrTaskService;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/api/v1/ocr/tasks")
@Tag(name = "OCR Tasks", description = "Asynchronous handwritten bill recognition")
public class OcrTaskController {

    private static final String UUID_PATTERN =
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

    private final OcrTaskService taskService;

    public OcrTaskController(OcrTaskService taskService) {
        this.taskService = taskService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload an image and enqueue an OCR task")
    ResponseEntity<OcrTaskResponse> create(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @RequestPart("image") MultipartFile image) {
        OcrTaskResponse created = taskService.create(image, idempotencyKey);
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/ocr/tasks/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get an OCR task and its result")
    OcrTaskResponse get(@PathVariable @Pattern(regexp = UUID_PATTERN) String id) {
        return taskService.get(id);
    }

    @GetMapping
    @Operation(summary = "List OCR tasks")
    PageResponse<OcrTaskResponse> list(
            @RequestParam(required = false) OcrTaskStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return taskService.list(status, page, size);
    }

    @PostMapping("/{id}/retry")
    @Operation(summary = "Retry a failed OCR task")
    OcrTaskResponse retry(
            @PathVariable @Pattern(regexp = UUID_PATTERN) String id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
        return taskService.retry(id, idempotencyKey);
    }
}
