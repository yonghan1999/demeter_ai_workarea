package com.demeter.backend.ocr.application;

import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.bill.api.PageResponse;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.common.idempotency.CanonicalValues;
import com.demeter.backend.common.idempotency.IdempotencyKeys;
import com.demeter.backend.common.web.PaginationGuard;
import com.demeter.backend.common.web.PaginationProperties;
import com.demeter.backend.ocr.api.OcrTaskResponse;
import com.demeter.backend.ocr.domain.OcrDocument;
import com.demeter.backend.ocr.domain.OcrTask;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import com.demeter.backend.ocr.domain.OcrRetryCommand;
import com.demeter.backend.ocr.infrastructure.OcrRetryCommandRepository;
import com.demeter.backend.ocr.infrastructure.OcrTaskRepository;
import com.demeter.backend.ocr.infrastructure.OcrWorkerProperties;
import com.demeter.backend.ocr.infrastructure.OcrUploadProperties;
import com.demeter.backend.ocr.spi.OcrDocumentStorage;
import com.demeter.backend.security.CurrentActor;
import com.demeter.backend.security.DemeterPrincipal;
import com.demeter.backend.security.TokenDigests;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.dao.DataIntegrityViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Directory;
import com.drew.metadata.Metadata;
import com.drew.metadata.heif.HeifDirectory;
import com.drew.metadata.jpeg.JpegDirectory;
import com.drew.metadata.png.PngDirectory;
import com.drew.metadata.webp.WebpDirectory;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class OcrTaskService {

    private static final Logger log = LoggerFactory.getLogger(OcrTaskService.class);
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    private static final Set<String> SUPPORTED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/webp", "image/heic", "image/heif");

    private final OcrTaskRepository taskRepository;
    private final OcrRetryCommandRepository retryCommandRepository;
    private final OcrDocumentStorage storage;
    private final CurrentActor currentActor;
    private final AuditService auditService;
    private final BusinessChainExecutor chainExecutor;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final OcrWorkerProperties workerProperties;
    private final OcrUploadProperties uploadProperties;
    private final Clock clock;
    private final PaginationProperties pagination;

    private final BusinessChain<CreateTaskContext, OcrTaskResponse> createChain;
    private final BusinessChain<GetTaskContext, OcrTaskResponse> getChain;
    private final BusinessChain<ListTasksContext, PageResponse<OcrTaskResponse>> listChain;
    private final BusinessChain<RetryTaskContext, OcrTaskResponse> retryChain;

    public OcrTaskService(
            OcrTaskRepository taskRepository,
            OcrRetryCommandRepository retryCommandRepository,
            OcrDocumentStorage storage,
            CurrentActor currentActor,
            AuditService auditService,
            BusinessChainExecutor chainExecutor,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            OcrWorkerProperties workerProperties,
            OcrUploadProperties uploadProperties,
            Clock clock,
            PaginationProperties pagination) {
        this.taskRepository = taskRepository;
        this.retryCommandRepository = retryCommandRepository;
        this.storage = storage;
        this.currentActor = currentActor;
        this.auditService = auditService;
        this.chainExecutor = chainExecutor;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.workerProperties = workerProperties;
        this.uploadProperties = uploadProperties;
        this.clock = clock;
        this.pagination = pagination;
        this.createChain = buildCreateChain();
        this.getChain = buildGetChain();
        this.listChain = buildListChain();
        this.retryChain = buildRetryChain();
    }

    public OcrTaskResponse create(MultipartFile image, String idempotencyKey) {
        return chainExecutor.execute(createChain, new CreateTaskContext(image, idempotencyKey));
    }

    public OcrTaskResponse get(String publicId) {
        return chainExecutor.execute(getChain, new GetTaskContext(publicId));
    }

    public PageResponse<OcrTaskResponse> list(OcrTaskStatus status, int page, int size) {
        return chainExecutor.execute(listChain, new ListTasksContext(status, page, size));
    }

    public OcrTaskResponse retry(String publicId, String idempotencyKey) {
        return chainExecutor.execute(retryChain, new RetryTaskContext(publicId, idempotencyKey));
    }

    private BusinessChain<CreateTaskContext, OcrTaskResponse> buildCreateChain() {
        return BusinessChain.of(
                "ocr.task.create",
                BusinessChainExecutionMode.EXTERNAL_IO,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("validate-idempotency", context ->
                                context.idempotencyKey = requireIdempotencyKey(context.requestedIdempotencyKey)),
                        BusinessHandler.named("read-and-validate-image", this::readAndValidateImage),
                        BusinessHandler.named("compute-request-hash", context -> {
                            context.contentSha256 = TokenDigests.sha256Bytes(context.document.content());
                            context.requestHash = CanonicalValues.sha256(
                                    context.contentSha256,
                                    context.contentType,
                                    context.document.content().length);
                        }),
                        BusinessHandler.named("resolve-idempotency", context -> transactionTemplate.executeWithoutResult(
                                status -> taskRepository.findByTenantIdAndIdempotencyKey(
                                                context.actor.tenantId(), context.idempotencyKey)
                                        .ifPresent(existing -> {
                                            if (!existing.getRequestHash().equals(context.requestHash)) {
                                                throw new ConflictException(
                                                        "The Idempotency-Key was already used for a different OCR image");
                                            }
                                            context.task = existing;
                                            context.result = OcrTaskResponse.from(existing, objectMapper);
                                            context.halt();
                                        }))),
                        BusinessHandler.named("store-image", context -> context.storageKey = storage.store(
                                context.actor.tenantId(), context.fileName, context.document)),
                        BusinessHandler.named("persist-task", this::persistTask),
                        BusinessHandler.named("map-response", context ->
                                context.result = OcrTaskResponse.from(context.task, objectMapper))),
                context -> context.result);
    }

    private BusinessChain<GetTaskContext, OcrTaskResponse> buildGetChain() {
        return BusinessChain.of(
                "ocr.task.get",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("load-task", context -> context.task = transactionTemplate.execute(status ->
                                requireTask(context.publicId, context.actor.tenantId()))),
                        BusinessHandler.named("map-response", context ->
                                context.result = OcrTaskResponse.from(context.task, objectMapper))),
                context -> context.result);
    }

    private BusinessChain<ListTasksContext, PageResponse<OcrTaskResponse>> buildListChain() {
        return BusinessChain.of(
                "ocr.task.list",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("validate-pagination", context -> PaginationGuard.requireValid(
                                context.page, context.size, pagination)),
                        BusinessHandler.named("build-page-request", context -> context.pageable = PageRequest.of(
                                context.page,
                                context.size,
                                Sort.by(Sort.Direction.DESC, "createdAt")
                                        .and(Sort.by(Sort.Direction.DESC, "id")))),
                        BusinessHandler.named("query-tasks", context -> context.tasks = transactionTemplate.execute(
                                transaction -> context.status == null
                                        ? taskRepository.findAllByTenantId(
                                                context.actor.tenantId(), context.pageable)
                                        : taskRepository.findAllByTenantIdAndStatus(
                                                context.actor.tenantId(), context.status, context.pageable))),
                        BusinessHandler.named("map-response", context -> context.result = PageResponse.from(
                                context.tasks.map(OcrTaskResponse::summary)))),
                context -> context.result);
    }

    private BusinessChain<RetryTaskContext, OcrTaskResponse> buildRetryChain() {
        return BusinessChain.of(
                "ocr.task.retry",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("normalize-command", context -> {
                            context.idempotencyKey = requireIdempotencyKey(context.requestedIdempotencyKey);
                            context.requestHash = CanonicalValues.sha256(context.publicId);
                        }),
                        BusinessHandler.named("reset-task", context -> transactionTemplate.executeWithoutResult(status -> {
                            OcrTask task = taskRepository.findByPublicIdAndTenantIdForUpdate(
                                            context.publicId, context.actor.tenantId())
                                    .orElseThrow(() -> new ResourceNotFoundException(
                                            "OCR task does not exist"));
                            OcrRetryCommand existing = retryCommandRepository
                                    .findByTenantIdAndIdempotencyKey(
                                            context.actor.tenantId(), context.idempotencyKey)
                                    .orElse(null);
                            if (existing != null) {
                                if (!existing.getTaskId().equals(task.getId())
                                        || !existing.getRequestHash().equals(context.requestHash)) {
                                    throw new ConflictException(
                                            "The Idempotency-Key was already used for a different OCR retry");
                                }
                                context.task = task;
                                return;
                            }
                            try {
                                task.retry(clock.instant());
                            } catch (IllegalStateException exception) {
                                throw new BusinessRuleException(exception.getMessage());
                            }
                            context.task = taskRepository.saveAndFlush(task);
                            retryCommandRepository.saveAndFlush(new OcrRetryCommand(
                                    context.actor.tenantId(),
                                    task.getId(),
                                    context.idempotencyKey,
                                    context.requestHash,
                                    task.getVersion(),
                                    context.actor.userId(),
                                    clock.instant()));
                            auditService.record(
                                    context.actor,
                                    "OCR_TASK_RETRIED",
                                    "OCR_TASK",
                                    task.getPublicId(),
                                    null);
                        })),
                        BusinessHandler.named("map-response", context ->
                                context.result = OcrTaskResponse.from(context.task, objectMapper))),
                context -> context.result);
    }

    private void readAndValidateImage(CreateTaskContext context) {
        MultipartFile image = context.image;
        if (image == null || image.isEmpty()) {
            throw new BusinessRuleException("识别图片不能为空");
        }
        if (image.getSize() <= 0 || image.getSize() > uploadProperties.maxBytes()) {
            throw new BusinessRuleException("识别图片超过允许的大小");
        }
        String contentType = image.getContentType();
        if (contentType == null) {
            throw new BusinessRuleException("无法识别图片类型");
        }
        contentType = contentType.toLowerCase(Locale.ROOT);
        if (!SUPPORTED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessRuleException("仅支持 JPEG、PNG、WebP、HEIC 或 HEIF 图片");
        }
        try {
            byte[] content = image.getBytes();
            if (!matchesContentType(contentType, content)) {
                throw new BusinessRuleException("图片内容与声明的文件类型不一致");
            }
            validateImageDimensions(contentType, content);
            context.contentType = contentType;
            context.fileName = safeFilename(image.getOriginalFilename());
            context.document = new OcrDocument(context.fileName, contentType, content);
        } catch (IOException exception) {
            throw new BusinessRuleException("无法读取上传的识别图片");
        }
    }

    private void validateImageDimensions(String contentType, byte[] content) {
        try {
            Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(content));
            ImageDimensions dimensions = findDimensions(contentType, metadata);
            if (dimensions == null || dimensions.width() <= 0 || dimensions.height() <= 0) {
                throw new BusinessRuleException("无法读取图片尺寸");
            }
            long pixels = Math.multiplyExact((long) dimensions.width(), dimensions.height());
            if (dimensions.width() > uploadProperties.maxWidth()
                    || dimensions.height() > uploadProperties.maxHeight()
                    || pixels > uploadProperties.maxPixels()) {
                throw new BusinessRuleException("识别图片尺寸超过允许范围");
            }
        } catch (ImageProcessingException | IOException | ArithmeticException exception) {
            throw new BusinessRuleException("无法解析识别图片");
        }
    }

    private static ImageDimensions findDimensions(String contentType, Metadata metadata) {
        return switch (contentType) {
            case "image/jpeg" -> dimensions(
                    metadata.getFirstDirectoryOfType(JpegDirectory.class),
                    JpegDirectory.TAG_IMAGE_WIDTH,
                    JpegDirectory.TAG_IMAGE_HEIGHT);
            case "image/png" -> dimensions(
                    metadata.getFirstDirectoryOfType(PngDirectory.class),
                    PngDirectory.TAG_IMAGE_WIDTH,
                    PngDirectory.TAG_IMAGE_HEIGHT);
            case "image/webp" -> dimensions(
                    metadata.getFirstDirectoryOfType(WebpDirectory.class),
                    WebpDirectory.TAG_IMAGE_WIDTH,
                    WebpDirectory.TAG_IMAGE_HEIGHT);
            case "image/heic", "image/heif" -> dimensions(
                    metadata.getFirstDirectoryOfType(HeifDirectory.class),
                    HeifDirectory.TAG_IMAGE_WIDTH,
                    HeifDirectory.TAG_IMAGE_HEIGHT);
            default -> null;
        };
    }

    private static ImageDimensions dimensions(Directory directory, int widthTag, int heightTag) {
        if (directory == null || !directory.containsTag(widthTag) || !directory.containsTag(heightTag)) {
            return null;
        }
        return new ImageDimensions(directory.getInteger(widthTag), directory.getInteger(heightTag));
    }

    private void persistTask(CreateTaskContext context) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                OcrTask task = new OcrTask(
                        context.actor.tenantId(),
                        context.actor.userId(),
                        context.storageKey,
                        context.fileName,
                        context.contentType,
                        context.document.content().length,
                        context.contentSha256,
                        context.idempotencyKey,
                        context.requestHash,
                        workerProperties.maxAttempts(),
                        clock.instant());
                context.task = taskRepository.saveAndFlush(task);
                auditService.record(
                        context.actor,
                        "OCR_TASK_CREATED",
                        "OCR_TASK",
                        task.getPublicId(),
                        Map.of(
                                "contentType", task.getContentType(),
                                "sizeBytes", task.getSizeBytes()));
            });
        } catch (DataIntegrityViolationException conflict) {
            deleteStoredDocument(context.storageKey, conflict);
            OcrTask existing = transactionTemplate.execute(status -> taskRepository
                    .findByTenantIdAndIdempotencyKey(context.actor.tenantId(), context.idempotencyKey)
                    .orElse(null));
            if (existing == null) {
                throw conflict;
            }
            if (!existing.getRequestHash().equals(context.requestHash)) {
                throw new ConflictException(
                        "The Idempotency-Key was already used for a different OCR image");
            }
            context.task = existing;
        } catch (RuntimeException exception) {
            deleteStoredDocument(context.storageKey, exception);
            throw exception;
        }
    }

    private void deleteStoredDocument(String storageKey, RuntimeException originalFailure) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            originalFailure.addSuppressed(cleanupFailure);
            log.error("Could not clean up OCR document after task persistence failed: storageKey={}", storageKey);
        }
    }

    private OcrTask requireTask(String publicId, long tenantId) {
        return taskRepository.findByPublicIdAndTenantId(publicId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("OCR task does not exist"));
    }

    private <C extends ActorContext> void resolveActor(C context) {
        context.actor = currentActor.require();
    }

    private static String requireIdempotencyKey(String value) {
        return IdempotencyKeys.require(value);
    }

    private static String safeFilename(String value) {
        if (value == null || value.isBlank()) {
            return "ocr-image";
        }
        String normalized = value.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.isEmpty()) {
            return "ocr-image";
        }
        return normalized.length() <= 255 ? normalized : normalized.substring(normalized.length() - 255);
    }

    private static boolean matchesContentType(String contentType, byte[] content) {
        return switch (contentType) {
            case "image/jpeg" -> content.length >= 3
                    && unsigned(content[0]) == 0xff
                    && unsigned(content[1]) == 0xd8
                    && unsigned(content[2]) == 0xff;
            case "image/png" -> content.length >= 8
                    && unsigned(content[0]) == 0x89
                    && content[1] == 0x50
                    && content[2] == 0x4e
                    && content[3] == 0x47
                    && content[4] == 0x0d
                    && content[5] == 0x0a
                    && content[6] == 0x1a
                    && content[7] == 0x0a;
            case "image/webp" -> content.length >= 12
                    && ascii(content, 0, "RIFF")
                    && ascii(content, 8, "WEBP");
            case "image/heic", "image/heif" -> content.length >= 12
                    && ascii(content, 4, "ftyp")
                    && isHeifBrand(content, 8);
            default -> false;
        };
    }

    private static boolean isHeifBrand(byte[] content, int offset) {
        String brand = new String(content, offset, 4, java.nio.charset.StandardCharsets.US_ASCII);
        return Set.of("heic", "heix", "hevc", "hevx", "mif1", "msf1").contains(brand);
    }

    private static boolean ascii(byte[] content, int offset, String expected) {
        if (content.length < offset + expected.length()) {
            return false;
        }
        for (int i = 0; i < expected.length(); i++) {
            if (content[offset + i] != (byte) expected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }

    private record ImageDimensions(int width, int height) {
    }

    private abstract static class ActorContext extends BusinessContext {
        protected DemeterPrincipal actor;
    }

    private static final class CreateTaskContext extends ActorContext {
        private final MultipartFile image;
        private final String requestedIdempotencyKey;
        private String idempotencyKey;
        private String contentType;
        private String fileName;
        private String contentSha256;
        private String requestHash;
        private String storageKey;
        private OcrDocument document;
        private OcrTask task;
        private OcrTaskResponse result;

        private CreateTaskContext(MultipartFile image, String requestedIdempotencyKey) {
            this.image = image;
            this.requestedIdempotencyKey = requestedIdempotencyKey;
        }
    }

    private static final class GetTaskContext extends ActorContext {
        private final String publicId;
        private OcrTask task;
        private OcrTaskResponse result;

        private GetTaskContext(String publicId) {
            this.publicId = publicId;
        }
    }

    private static final class ListTasksContext extends ActorContext {
        private final OcrTaskStatus status;
        private final int page;
        private final int size;
        private PageRequest pageable;
        private Page<OcrTask> tasks;
        private PageResponse<OcrTaskResponse> result;

        private ListTasksContext(OcrTaskStatus status, int page, int size) {
            this.status = status;
            this.page = page;
            this.size = size;
        }
    }

    private static final class RetryTaskContext extends ActorContext {
        private final String publicId;
        private final String requestedIdempotencyKey;
        private String idempotencyKey;
        private String requestHash;
        private OcrTask task;
        private OcrTaskResponse result;

        private RetryTaskContext(String publicId, String requestedIdempotencyKey) {
            this.publicId = publicId;
            this.requestedIdempotencyKey = requestedIdempotencyKey;
        }
    }
}
