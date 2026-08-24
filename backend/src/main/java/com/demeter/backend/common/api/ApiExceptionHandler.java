package com.demeter.backend.common.api;

import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.common.error.DependencyUnavailableException;
import com.demeter.backend.common.error.ExternalServiceException;
import com.demeter.backend.common.error.PreconditionRequiredException;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.common.error.ServiceNotConfiguredException;
import com.demeter.backend.common.error.UnauthorizedException;
import com.demeter.backend.common.web.ApiRequestSizeFilter.RequestBodyTooLargeException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import com.demeter.backend.auth.infrastructure.WechatLoginRejectedException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    ProblemDetail handleNotFound(ResourceNotFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    ProblemDetail handleUnauthorized(UnauthorizedException exception, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", exception.getMessage(), request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    ProblemDetail handleBusinessRule(BusinessRuleException exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "BUSINESS_RULE_VIOLATION", exception.getMessage(), request);
    }

    @ExceptionHandler({ConflictException.class, ObjectOptimisticLockingFailureException.class})
    ProblemDetail handleConflict(Exception exception, HttpServletRequest request) {
        String detail = exception instanceof ConflictException
                ? exception.getMessage()
                : "The resource was changed by another request; reload and retry";
        return problem(HttpStatus.CONFLICT, "RESOURCE_CONFLICT", detail, request);
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ProblemDetail handlePreconditionRequired(
            PreconditionRequiredException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED", exception.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail handleDataConflict(DataIntegrityViolationException exception, HttpServletRequest request) {
        log.warn("Database constraint violation: type={}", exception.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "DATA_CONFLICT", "The request conflicts with existing data", request);
    }

    @ExceptionHandler(ServiceNotConfiguredException.class)
    ProblemDetail handleServiceNotConfigured(ServiceNotConfiguredException exception, HttpServletRequest request) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(DependencyUnavailableException.class)
    ProblemDetail handleDependencyUnavailable(
            DependencyUnavailableException exception,
            HttpServletRequest request) {
        log.warn("Internal dependency unavailable: code={}", exception.getCode());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(ExternalServiceException.class)
    ProblemDetail handleExternalService(ExternalServiceException exception, HttpServletRequest request) {
        log.warn("External service request failed: code={}", exception.getCode());
        return problem(HttpStatus.BAD_GATEWAY, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler(WechatLoginRejectedException.class)
    ProblemDetail handleWechatLoginRejected(
            WechatLoginRejectedException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, exception.getCode(), exception.getMessage(), request);
    }

    @ExceptionHandler({CallNotPermittedException.class, BulkheadFullException.class})
    ProblemDetail handleDependencyProtection(Exception exception, HttpServletRequest request) {
        log.warn("External dependency protection rejected a request: type={}", exception.getClass().getSimpleName());
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DEPENDENCY_UNAVAILABLE",
                "An external dependency is temporarily unavailable",
                request);
    }

    @ExceptionHandler({CannotAcquireLockException.class, PessimisticLockingFailureException.class})
    ProblemDetail handleDatabaseContention(Exception exception, HttpServletRequest request) {
        log.warn("Database lock contention: type={}", exception.getClass().getSimpleName());
        return problem(
                HttpStatus.CONFLICT,
                "CONCURRENT_OPERATION",
                "Another operation is changing the same data; retry with the same Idempotency-Key",
                request);
    }

    @ExceptionHandler({QueryTimeoutException.class, TransactionTimedOutException.class})
    ProblemDetail handleDatabaseTimeout(Exception exception, HttpServletRequest request) {
        log.warn("Database operation timed out: type={}", exception.getClass().getSimpleName());
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "DATABASE_TIMEOUT",
                "The database operation timed out; retry later",
                request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MissingServletRequestParameterException.class})
    ProblemDetail handleMissingRequestValue(Exception exception, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "MISSING_REQUEST_VALUE", exception.getMessage(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ProblemDetail handleMethodNotSupported(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "HTTP method is not supported", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ProblemDetail handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException exception,
            HttpServletRequest request) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "UNSUPPORTED_MEDIA_TYPE",
                "Request media type is not supported",
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ProblemDetail handleMediaTypeNotAcceptable(
            HttpMediaTypeNotAcceptableException exception,
            HttpServletRequest request) {
        return problem(
                HttpStatus.NOT_ACCEPTABLE,
                "NOT_ACCEPTABLE",
                "No acceptable response representation is available",
                request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail handleNoResource(NoResourceFoundException exception, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The requested resource does not exist", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        List<FieldViolation> violations = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail detail = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "One or more request fields are invalid",
                request);
        detail.setProperty("errors", violations);
        return detail;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail handleConstraintViolation(ConstraintViolationException exception, HttpServletRequest request) {
        List<FieldViolation> violations = exception.getConstraintViolations().stream()
                .map(violation -> new FieldViolation(
                        violation.getPropertyPath().toString(),
                        violation.getMessage()))
                .toList();
        ProblemDetail detail = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "One or more request parameters are invalid",
                request);
        detail.setProperty("errors", violations);
        return detail;
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MaxUploadSizeExceededException.class})
    ProblemDetail handleUpload(Exception exception, HttpServletRequest request) {
        String message = exception instanceof MaxUploadSizeExceededException
                ? "The uploaded image exceeds the configured size limit"
                : "The image upload part is required";
        return problem(HttpStatus.BAD_REQUEST, "INVALID_UPLOAD", message, request);
    }

    @ExceptionHandler(RequestBodyTooLargeException.class)
    ProblemDetail handleRequestBodyTooLarge(
            RequestBodyTooLargeException exception,
            HttpServletRequest request) {
        return problem(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "PAYLOAD_TOO_LARGE",
                "The request body exceeds the configured size limit",
                request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail handleMalformedRequest(Exception exception, HttpServletRequest request) {
        if (hasCause(exception, RequestBodyTooLargeException.class)) {
            return problem(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "PAYLOAD_TOO_LARGE",
                    "The request body exceeds the configured size limit",
                    request);
        }
        return problem(
                HttpStatus.BAD_REQUEST,
                "MALFORMED_REQUEST",
                "The request body or parameter format is invalid",
                request);
    }

    private static boolean hasCause(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @ExceptionHandler(ResponseStatusException.class)
    ProblemDetail handleResponseStatus(ResponseStatusException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());
        return problem(status, "REQUEST_REJECTED", exception.getReason(), request);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure: type={}", exception.getClass().getSimpleName(), exception);
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "The server could not complete the request",
                request);
    }

    private ProblemDetail problem(
            HttpStatus status,
            String code,
            String detail,
            HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail == null ? status.getReasonPhrase() : detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setType(URI.create("urn:demeter:error:" + code.toLowerCase().replace('_', '-')));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("requestId", MDC.get("requestId"));
        return problem;
    }
}
