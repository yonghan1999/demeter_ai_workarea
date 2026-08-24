package com.demeter.backend.bill.api;

import com.demeter.backend.bill.application.BillService;
import com.demeter.backend.bill.domain.BillStatus;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.payment.application.PaymentService;
import com.demeter.backend.common.web.VersionEtags;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/api/v1/bills")
@Tag(name = "Bills", description = "Freight bill management")
public class BillController {

    private final BillService billService;
    private final PaymentService paymentService;

    public BillController(BillService billService, PaymentService paymentService) {
        this.billService = billService;
        this.paymentService = paymentService;
    }

    @GetMapping
    @Operation(summary = "List and filter bills")
    PageResponse<BillResponse> list(
            @RequestParam(required = false) @Size(max = 120) String keyword,
            @RequestParam(required = false) @Size(max = 64) String code,
            @RequestParam(required = false) @Size(max = 120) String shipper,
            @RequestParam(required = false) BillStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) @Size(max = 40) String tag,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "date,desc") String sort) {
        return billService.list(keyword, code, shipper, status, startDate, endDate, tag, page, size, sort);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one bill")
    ResponseEntity<BillResponse> get(@PathVariable @Positive long id) {
        BillResponse response = billService.get(id);
        return ResponseEntity.ok()
                .eTag(VersionEtags.format(response.version()))
                .body(response);
    }

    @PostMapping
    @Operation(summary = "Create a bill")
    ResponseEntity<BillResponse> create(
            @RequestHeader("Idempotency-Key") @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody BillUpsertRequest request) {
        BillResponse created = billService.create(idempotencyKey, request);
        return ResponseEntity.created(URI.create("/api/v1/bills/" + created.id()))
                .eTag(VersionEtags.format(created.version()))
                .body(created);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace editable bill fields")
    ResponseEntity<BillResponse> update(
            @PathVariable @Positive long id,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody BillUpsertRequest request) {
        BillResponse response = billService.update(id, ifMatch, idempotencyKey, request);
        return ResponseEntity.ok()
                .eTag(VersionEtags.format(response.version()))
                .body(response);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete one bill")
    DeleteBillsResponse delete(
            @PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @RequestParam(required = false) @Size(max = 240) String reason) {
        return billService.delete(id, idempotencyKey, reason);
    }

    @PostMapping("/batch-delete")
    @Operation(summary = "Soft-delete selected bills atomically")
    DeleteBillsResponse deleteBatch(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody BatchDeleteBillsRequest request) {
        return billService.deleteBatch(idempotencyKey, request);
    }

    @PostMapping("/{id}/restore")
    @Operation(summary = "Restore a soft-deleted bill")
    ResponseEntity<BillResponse> restore(
            @PathVariable @Positive long id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody RestoreBillRequest request) {
        BillResponse response = billService.restore(id, idempotencyKey, request);
        return ResponseEntity.ok()
                .eTag(VersionEtags.format(response.version()))
                .body(response);
    }

    @GetMapping("/shipper-suggestions")
    @Operation(summary = "Suggest existing shipper names")
    List<ShipperSuggestionResponse> suggestShippers(
            @RequestParam(defaultValue = "") @Size(max = 120) String keyword,
            @RequestParam(defaultValue = "3") @Min(1) @Max(20) int limit) {
        return billService.suggestShippers(keyword, limit);
    }

    @PostMapping("/resolve-shipper")
    @Operation(summary = "Resolve a shipper name to an existing normalized name")
    ShipperResolutionResponse resolveShipper(@Valid @RequestBody ShipperResolutionRequest request) {
        return billService.resolveShipper(request.name());
    }

    @GetMapping("/search-suggestions")
    @Operation(summary = "Suggest shipper and route search terms")
    List<SearchSuggestionResponse> suggestSearch(
            @RequestParam @Size(min = 1, max = 120) String keyword,
            @RequestParam(defaultValue = "6") @Min(1) @Max(20) int limit) {
        return billService.suggestSearch(keyword, limit);
    }
}
