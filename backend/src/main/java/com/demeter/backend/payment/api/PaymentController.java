package com.demeter.backend.payment.api;

import com.demeter.backend.bill.api.PageResponse;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.payment.application.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/api/v1/bills/{billId}/payments")
@Tag(name = "Payments", description = "Bill payment ledger")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    @Operation(summary = "Record a payment")
    ResponseEntity<PaymentResponse> create(
            @PathVariable @Positive long billId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {
        PaymentResponse created = paymentService.create(billId, idempotencyKey, request);
        return ResponseEntity.created(URI.create(
                        "/api/v1/bills/" + billId + "/payments/" + created.id()))
                .body(created);
    }

    @GetMapping
    @Operation(summary = "List a bill's payment ledger")
    PageResponse<PaymentResponse> list(
            @PathVariable @Positive long billId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(10000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return paymentService.list(billId, page, size);
    }

    @PostMapping("/{paymentId}/reversal")
    @Operation(summary = "Reverse a payment without deleting its audit trail")
    PaymentResponse reverse(
            @PathVariable @Positive long billId,
            @PathVariable @Positive long paymentId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody ReversePaymentRequest request) {
        return paymentService.reverse(billId, paymentId, idempotencyKey, request);
    }
}
