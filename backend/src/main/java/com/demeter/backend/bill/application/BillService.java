package com.demeter.backend.bill.application;

import com.demeter.backend.audit.application.AuditService;
import com.demeter.backend.bill.api.BatchDeleteBillsRequest;
import com.demeter.backend.bill.api.BillResponse;
import com.demeter.backend.bill.api.BillUpsertRequest;
import com.demeter.backend.bill.api.DeleteBillsResponse;
import com.demeter.backend.bill.api.PageResponse;
import com.demeter.backend.bill.api.SearchSuggestionResponse;
import com.demeter.backend.bill.api.RestoreBillRequest;
import com.demeter.backend.bill.api.ShipperResolutionResponse;
import com.demeter.backend.bill.api.ShipperSuggestionResponse;
import com.demeter.backend.bill.domain.Bill;
import com.demeter.backend.bill.domain.BillCodeSequence;
import com.demeter.backend.bill.domain.BillStatus;
import com.demeter.backend.bill.infrastructure.BillCodeSequenceRepository;
import com.demeter.backend.bill.infrastructure.BillRepository;
import com.demeter.backend.bill.infrastructure.BillSpecifications;
import com.demeter.backend.common.chain.BusinessChain;
import com.demeter.backend.common.chain.BusinessChainExecutor;
import com.demeter.backend.common.chain.BusinessChainExecutionMode;
import com.demeter.backend.common.chain.BusinessContext;
import com.demeter.backend.common.chain.BusinessHandler;
import com.demeter.backend.common.error.BusinessRuleException;
import com.demeter.backend.common.error.ConflictException;
import com.demeter.backend.common.error.ResourceNotFoundException;
import com.demeter.backend.common.idempotency.BusinessCommandReplayService;
import com.demeter.backend.common.idempotency.CanonicalValues;
import com.demeter.backend.common.idempotency.IdempotencyKeys;
import com.demeter.backend.common.web.PaginationGuard;
import com.demeter.backend.common.web.PaginationProperties;
import com.demeter.backend.common.web.VersionEtags;
import com.demeter.backend.security.CurrentActor;
import com.demeter.backend.security.DemeterPrincipal;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;

@Service
@ConditionalOnRuntimeRole(RuntimeRole.API)
public class BillService {

    private static final String SEQUENCE_NAME = "bill";
    private static final String DEFAULT_DELETE_REASON = "用户删除";
    private static final DateTimeFormatter CODE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Pattern SHIPPER_PUNCTUATION = Pattern.compile("[\\s·•,，.。()（）-]");
    private static final Pattern SHIPPER_SUFFIX = Pattern.compile("(有限责任公司|股份有限公司|有限公司|公司)$");
    private static final Set<String> ALLOWED_SORTS = Set.of(
            "date", "createdAt", "updatedAt", "amount", "shipper", "code");

    private final BillRepository billRepository;
    private final BillCodeSequenceRepository sequenceRepository;
    private final CurrentActor currentActor;
    private final AuditService auditService;
    private final BusinessChainExecutor chainExecutor;
    private final BusinessCommandReplayService replayService;
    private final Clock clock;
    private final PaginationProperties pagination;

    private final BusinessChain<ListBillsContext, PageResponse<BillResponse>> listChain;
    private final BusinessChain<GetBillContext, BillResponse> getChain;
    private final BusinessChain<UpsertBillContext, BillResponse> createChain;
    private final BusinessChain<UpsertBillContext, BillResponse> updateChain;
    private final BusinessChain<DeleteBillsContext, DeleteBillsResponse> deleteChain;
    private final BusinessChain<DeleteBillsContext, DeleteBillsResponse> batchDeleteChain;
    private final BusinessChain<RestoreBillContext, BillResponse> restoreChain;
    private final BusinessChain<SuggestShippersContext, List<ShipperSuggestionResponse>> suggestShippersChain;
    private final BusinessChain<ResolveShipperContext, ShipperResolutionResponse> resolveShipperChain;
    private final BusinessChain<SuggestSearchContext, List<SearchSuggestionResponse>> suggestSearchChain;

    public BillService(
            BillRepository billRepository,
            BillCodeSequenceRepository sequenceRepository,
            CurrentActor currentActor,
            AuditService auditService,
            BusinessChainExecutor chainExecutor,
            BusinessCommandReplayService replayService,
            Clock clock,
            PaginationProperties pagination) {
        this.billRepository = billRepository;
        this.sequenceRepository = sequenceRepository;
        this.currentActor = currentActor;
        this.auditService = auditService;
        this.chainExecutor = chainExecutor;
        this.replayService = replayService;
        this.clock = clock;
        this.pagination = pagination;
        this.listChain = buildListChain();
        this.getChain = buildGetChain();
        this.createChain = buildCreateChain();
        this.updateChain = buildUpdateChain();
        this.deleteChain = buildDeleteChain("bill.delete", "BILL_DELETED");
        this.batchDeleteChain = buildDeleteChain("bill.batch-delete", "BILLS_BATCH_DELETED");
        this.restoreChain = buildRestoreChain();
        this.suggestShippersChain = buildSuggestShippersChain();
        this.resolveShipperChain = buildResolveShipperChain();
        this.suggestSearchChain = buildSuggestSearchChain();
    }

    @Transactional(readOnly = true)
    public PageResponse<BillResponse> list(
            String keyword,
            String code,
            String shipper,
            BillStatus status,
            LocalDate startDate,
            LocalDate endDate,
            String tag,
            int page,
            int size,
            String sort) {
        return chainExecutor.execute(listChain, new ListBillsContext(
                keyword, code, shipper, status, startDate, endDate, tag, page, size, sort));
    }

    @Transactional(readOnly = true)
    public BillResponse get(long id) {
        return chainExecutor.execute(getChain, new GetBillContext(id));
    }

    @Transactional
    public BillResponse create(String idempotencyKey, BillUpsertRequest request) {
        return chainExecutor.execute(createChain, new UpsertBillContext(null, null, idempotencyKey, request));
    }

    @Transactional
    public BillResponse update(long id, String ifMatch, String idempotencyKey, BillUpsertRequest request) {
        return chainExecutor.execute(updateChain, new UpsertBillContext(id, ifMatch, idempotencyKey, request));
    }

    @Transactional
    public DeleteBillsResponse delete(long id, String idempotencyKey, String reason) {
        return chainExecutor.execute(
                deleteChain,
                new DeleteBillsContext(List.of(id), idempotencyKey, reason));
    }

    @Transactional
    public DeleteBillsResponse deleteBatch(String idempotencyKey, BatchDeleteBillsRequest request) {
        return chainExecutor.execute(
                batchDeleteChain,
                new DeleteBillsContext(request.ids(), idempotencyKey, request.reason()));
    }

    @Transactional
    public BillResponse restore(long id, String idempotencyKey, RestoreBillRequest request) {
        return chainExecutor.execute(restoreChain, new RestoreBillContext(id, idempotencyKey, request));
    }

    @Transactional(readOnly = true)
    public List<ShipperSuggestionResponse> suggestShippers(String keyword, int limit) {
        return chainExecutor.execute(suggestShippersChain, new SuggestShippersContext(keyword, limit));
    }

    @Transactional(readOnly = true)
    public ShipperResolutionResponse resolveShipper(String input) {
        return chainExecutor.execute(resolveShipperChain, new ResolveShipperContext(input));
    }

    @Transactional(readOnly = true)
    public List<SearchSuggestionResponse> suggestSearch(String keyword, int limit) {
        return chainExecutor.execute(suggestSearchChain, new SuggestSearchContext(keyword, limit));
    }

    private BusinessChain<ListBillsContext, PageResponse<BillResponse>> buildListChain() {
        return BusinessChain.of(
                "bill.list",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("validate-query", context -> validateDateRange(
                                context.startDate, context.endDate)),
                        BusinessHandler.named("validate-pagination", context -> PaginationGuard.requireValid(
                                context.page, context.size, pagination)),
                        BusinessHandler.named("build-page-request", context ->
                                context.pageable = PageRequest.of(context.page, context.size, parseSort(context.sort))),
                        BusinessHandler.named("query-bills", context -> context.pageResult = billRepository.findAll(
                                BillSpecifications.filteredBy(
                                        context.actor.tenantId(),
                                        context.keyword,
                                        context.code,
                                        context.shipper,
                                        context.status,
                                        context.startDate,
                                        context.endDate,
                                        context.tag),
                                context.pageable)),
                        BusinessHandler.named("map-response", context ->
                                context.result = PageResponse.from(context.pageResult.map(BillResponse::from)))),
                context -> context.result);
    }

    private BusinessChain<GetBillContext, BillResponse> buildGetChain() {
        return BusinessChain.of(
                "bill.get",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("load-bill", context ->
                                context.bill = requireBill(context.actor.tenantId(), context.id)),
                        BusinessHandler.named("map-response", context ->
                                context.result = BillResponse.from(context.bill))),
                context -> context.result);
    }

    private BusinessChain<UpsertBillContext, BillResponse> buildCreateChain() {
        return BusinessChain.of(
                "bill.create",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("validate-idempotency", context ->
                                context.idempotencyKey = requireIdempotencyKey(context.requestedIdempotencyKey)),
                        BusinessHandler.named("normalize-input", this::normalizeInput),
                        BusinessHandler.named("validate-business-rules", this::validateInput),
                        BusinessHandler.named("compute-request-hash", context ->
                                context.requestHash = billRequestHash(context.input)),
                        BusinessHandler.named("resolve-idempotency", this::resolveCreateIdempotency),
                        BusinessHandler.named("lock-sequence-and-generate-code", context -> {
                            BillCodeSequence sequence = sequenceRepository
                                    .findByTenantAndNameForUpdate(context.actor.tenantId(), SEQUENCE_NAME)
                                    .orElseThrow(() -> new IllegalStateException("Bill code sequence is missing"));
                            resolveCreateIdempotencyForUpdate(context);
                            if (!context.isHalted()) {
                                long value = sequence.incrementAndGet();
                                context.code = "TR-" + CODE_DATE.format(LocalDate.now(clock)) + "-"
                                        + String.format(Locale.ROOT, "%03d", value);
                            }
                        }),
                        BusinessHandler.named("construct-bill", context -> context.bill = new Bill(
                                context.actor.tenantId(),
                                context.actor.userId(),
                                context.code,
                                context.input.shipper(),
                                context.input.shipperNormalized(),
                                context.input.vehicleCargo(),
                                context.input.date(),
                                context.input.origin(),
                                context.input.destination(),
                                context.input.amount(),
                                context.input.dueDate(),
                                context.input.tags(),
                                context.idempotencyKey,
                                context.requestHash,
                                clock.instant())),
                        BusinessHandler.named("persist-bill", context ->
                                context.bill = billRepository.saveAndFlush(context.bill)),
                        BusinessHandler.named("write-audit", context -> auditService.record(
                                context.actor,
                                "BILL_CREATED",
                                "BILL",
                                context.bill.getId(),
                                Map.of("code", context.bill.getCode()))),
                        BusinessHandler.named("map-response", context ->
                                context.result = BillResponse.from(context.bill))),
                context -> context.result);
    }

    private BusinessChain<UpsertBillContext, BillResponse> buildUpdateChain() {
        return BusinessChain.of(
                "bill.update",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("parse-expected-version", context ->
                                context.expectedVersion = VersionEtags.parseRequired(context.ifMatch)),
                        BusinessHandler.named("validate-idempotency", context ->
                                context.idempotencyKey = requireIdempotencyKey(context.requestedIdempotencyKey)),
                        BusinessHandler.named("normalize-input", this::normalizeInput),
                        BusinessHandler.named("compute-request-hash", context -> context.requestHash =
                                CanonicalValues.sha256(
                                        context.id,
                                        context.expectedVersion,
                                        billRequestHash(context.input))),
                        BusinessHandler.named("resolve-idempotency", context -> replayService.find(
                                        context.actor,
                                        "bill.update",
                                        context.idempotencyKey,
                                        context.requestHash,
                                        BillResponse.class)
                                .ifPresent(response -> {
                                    context.result = response;
                                    context.halt();
                                })),
                        BusinessHandler.named("load-bill-for-update", context ->
                                context.bill = requireBillForUpdate(context.actor.tenantId(), context.id)),
                        BusinessHandler.named("resolve-idempotency-after-lock", context -> replayService.findForUpdate(
                                        context.actor,
                                        "bill.update",
                                        context.idempotencyKey,
                                        context.requestHash,
                                        BillResponse.class)
                                .ifPresent(response -> {
                                    context.result = response;
                                    context.halt();
                                })),
                        BusinessHandler.named("validate-version", context -> {
                            if (context.bill.getVersion() != context.expectedVersion) {
                                throw new ConflictException(
                                        "The bill was changed by another request; reload and retry");
                            }
                        }),
                        BusinessHandler.named("validate-business-rules", this::validateInput),
                        BusinessHandler.named("apply-update", context -> context.bill.update(
                                context.actor.userId(),
                                context.input.shipper(),
                                context.input.shipperNormalized(),
                                context.input.vehicleCargo(),
                                context.input.date(),
                                context.input.origin(),
                                context.input.destination(),
                                context.input.amount(),
                                context.input.dueDate(),
                                context.input.tags(),
                                clock.instant())),
                        BusinessHandler.named("persist-bill", context ->
                                context.bill = billRepository.saveAndFlush(context.bill)),
                        BusinessHandler.named("write-audit", context -> auditService.record(
                                context.actor,
                                "BILL_UPDATED",
                                "BILL",
                                context.bill.getId(),
                                Map.of("version", context.bill.getVersion()))),
                        BusinessHandler.named("map-response", context ->
                                context.result = BillResponse.from(context.bill)),
                        BusinessHandler.named("record-idempotency", context -> replayService.record(
                                context.actor,
                                "bill.update",
                                context.idempotencyKey,
                                context.requestHash,
                                context.result))),
                context -> context.result);
    }

    private BusinessChain<DeleteBillsContext, DeleteBillsResponse> buildDeleteChain(
            String chainName,
            String auditAction) {
        return BusinessChain.of(
                chainName,
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("normalize-command", context -> {
                            context.ids = context.requestedIds.stream().distinct().sorted().toList();
                            context.idempotencyKey = requireIdempotencyKey(context.requestedIdempotencyKey);
                            context.reason = trimToNull(context.requestedReason);
                            if (context.reason == null) {
                                context.reason = DEFAULT_DELETE_REASON;
                            }
                            context.requestHash = CanonicalValues.builder()
                                    .addCollection(context.ids)
                                    .add(context.reason)
                                    .digest();
                        }),
                        BusinessHandler.named("resolve-idempotency", context -> replayService.find(
                                        context.actor,
                                        chainName,
                                        context.idempotencyKey,
                                        context.requestHash,
                                        DeleteBillsResponse.class)
                                .ifPresent(response -> {
                                    context.result = response;
                                    context.halt();
                                })),
                        BusinessHandler.named("load-bills", context -> {
                            context.bills = billRepository.findAllByTenantIdAndIdsForUpdate(
                                    context.actor.tenantId(), context.ids);
                            Set<Long> existing = context.bills.stream()
                                    .map(Bill::getId)
                                    .collect(java.util.stream.Collectors.toSet());
                            List<Long> missing = context.ids.stream().filter(id -> !existing.contains(id)).toList();
                            if (!missing.isEmpty()) {
                                DeleteBillsResponse replay = replayService.findForUpdate(
                                                context.actor,
                                                chainName,
                                                context.idempotencyKey,
                                                context.requestHash,
                                                DeleteBillsResponse.class)
                                        .orElse(null);
                                if (replay != null) {
                                    context.result = replay;
                                    context.halt();
                                    return;
                                }
                                throw new ResourceNotFoundException("Bills do not exist: " + missing);
                            }
                        }),
                        BusinessHandler.named("soft-delete-bills", context -> {
                            java.time.Instant now = clock.instant();
                            context.bills.forEach(bill -> bill.softDelete(
                                    context.actor.userId(), context.reason, now));
                        }),
                        BusinessHandler.named("persist-bills", context ->
                                billRepository.saveAllAndFlush(context.bills)),
                        BusinessHandler.named("write-audit", context -> auditService.record(
                                context.actor,
                                auditAction,
                                "BILL_BATCH",
                                "batch:" + CanonicalValues.builder()
                                        .addCollection(context.ids)
                                        .digest()
                                        .substring(0, 32),
                                Map.of("ids", context.ids, "reason", context.reason))),
                        BusinessHandler.named("map-response", context -> context.result =
                                new DeleteBillsResponse(true, context.ids.size(), context.ids)),
                        BusinessHandler.named("record-idempotency", context -> replayService.record(
                                context.actor,
                                chainName,
                                context.idempotencyKey,
                                context.requestHash,
                                context.result))),
                context -> context.result);
    }

    private BusinessChain<SuggestShippersContext, List<ShipperSuggestionResponse>> buildSuggestShippersChain() {
        return BusinessChain.of(
                "bill.suggest-shippers",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("normalize-keyword", context ->
                                context.search = trimToEmpty(context.keyword)),
                        BusinessHandler.named("query-shippers", context -> context.names = billRepository.findShipperNames(
                                context.actor.tenantId(), context.search, PageRequest.of(0, context.limit))),
                        BusinessHandler.named("map-response", context -> context.result = context.names.stream()
                                .map(name -> {
                                    long count = billRepository.countByTenantIdAndShipperAndDeletedAtIsNull(
                                            context.actor.tenantId(), name);
                                    return new ShipperSuggestionResponse(
                                            "shipper-" + Integer.toUnsignedString(name.hashCode()),
                                            name,
                                            name,
                                            "最近使用 · " + count + "笔账单");
                                })
                                .toList())),
                context -> context.result);
    }

    private BusinessChain<RestoreBillContext, BillResponse> buildRestoreChain() {
        return BusinessChain.of(
                "bill.restore",
                BusinessChainExecutionMode.ATOMIC_DATABASE,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("normalize-command", context -> {
                            context.idempotencyKey = requireIdempotencyKey(context.requestedIdempotencyKey);
                            context.reason = trimRequired(context.request.reason(), "恢复原因不能为空");
                            context.requestHash = CanonicalValues.sha256(context.id, context.reason);
                        }),
                        BusinessHandler.named("resolve-idempotency", context -> replayService.find(
                                        context.actor,
                                        "bill.restore",
                                        context.idempotencyKey,
                                        context.requestHash,
                                        BillResponse.class)
                                .ifPresent(response -> {
                                    context.result = response;
                                    context.halt();
                                })),
                        BusinessHandler.named("load-bill-for-update", context -> context.bill = billRepository
                                .findAnyByIdAndTenantIdForUpdate(context.id, context.actor.tenantId())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                        "Bill " + context.id + " does not exist"))),
                        BusinessHandler.named("resolve-idempotency-after-lock", context -> replayService.findForUpdate(
                                        context.actor,
                                        "bill.restore",
                                        context.idempotencyKey,
                                        context.requestHash,
                                        BillResponse.class)
                                .ifPresent(response -> {
                                    context.result = response;
                                    context.halt();
                                })),
                        BusinessHandler.named("validate-deleted", context -> {
                            if (context.bill.getDeletedAt() == null) {
                                throw new ConflictException("The bill is not deleted");
                            }
                        }),
                        BusinessHandler.named("restore-bill", context -> context.bill.restore(
                                context.actor.userId(), clock.instant())),
                        BusinessHandler.named("persist-bill", context ->
                                context.bill = billRepository.saveAndFlush(context.bill)),
                        BusinessHandler.named("write-audit", context -> auditService.record(
                                context.actor,
                                "BILL_RESTORED",
                                "BILL",
                                context.bill.getId(),
                                Map.of("reason", context.reason))),
                        BusinessHandler.named("map-response", context ->
                                context.result = BillResponse.from(context.bill)),
                        BusinessHandler.named("record-idempotency", context -> replayService.record(
                                context.actor,
                                "bill.restore",
                                context.idempotencyKey,
                                context.requestHash,
                                context.result))),
                context -> context.result);
    }

    private BusinessChain<ResolveShipperContext, ShipperResolutionResponse> buildResolveShipperChain() {
        return BusinessChain.of(
                "bill.resolve-shipper",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("normalize-name", context -> {
                            context.value = trimRequired(context.input, "托运人不能为空");
                            context.normalized = normalizeShipperName(context.value);
                        }),
                        BusinessHandler.named("query-existing", context -> context.existing = billRepository
                                .findFirstByTenantIdAndShipperNormalizedAndDeletedAtIsNullOrderByUpdatedAtDesc(
                                        context.actor.tenantId(), context.normalized)
                                .orElse(null)),
                        BusinessHandler.named("map-response", context -> context.result = context.existing == null
                                ? new ShipperResolutionResponse(context.value, false)
                                : new ShipperResolutionResponse(context.existing.getShipper(), true))),
                context -> context.result);
    }

    private BusinessChain<SuggestSearchContext, List<SearchSuggestionResponse>> buildSuggestSearchChain() {
        return BusinessChain.of(
                "bill.suggest-search",
                BusinessChainExecutionMode.READ_ONLY,
                List.of(
                        BusinessHandler.named("resolve-actor", this::resolveActor),
                        BusinessHandler.named("normalize-keyword", context ->
                                context.search = trimRequired(context.keyword, "搜索关键词不能为空")),
                        BusinessHandler.named("query-shippers", context ->
                                billRepository.findShipperNames(
                                                context.actor.tenantId(),
                                                context.search,
                                                PageRequest.of(0, context.limit))
                                        .stream()
                                        .map(name -> new SearchSuggestionResponse("托运人", name))
                                        .forEach(context.suggestions::add)),
                        BusinessHandler.named("query-routes", context -> {
                            int remaining = context.limit - context.suggestions.size();
                            if (remaining > 0) {
                                billRepository.findRoutes(
                                                context.actor.tenantId(),
                                                context.search,
                                                PageRequest.of(0, remaining))
                                        .stream()
                                        .map(route -> new SearchSuggestionResponse("路线", route))
                                        .forEach(context.suggestions::add);
                            }
                        }),
                        BusinessHandler.named("map-response", context ->
                                context.result = List.copyOf(context.suggestions))),
                context -> context.result);
    }

    private <C extends ActorContext> void resolveActor(C context) {
        context.actor = currentActor.require();
    }

    private void normalizeInput(UpsertBillContext context) {
        BillUpsertRequest request = context.request;
        String shipper = trimRequired(request.shipper(), "托运人不能为空");
        Set<String> tags = request.tags() == null
                ? Set.of()
                : request.tags().stream()
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        context.input = new NormalizedBillInput(
                shipper,
                normalizeShipperName(shipper),
                trimToNull(request.vehicleCargo()),
                request.date(),
                trimRequired(request.from(), "始发地不能为空"),
                trimRequired(request.to(), "目的地不能为空"),
                request.amount(),
                request.status(),
                request.dueDate(),
                tags);
    }

    private void validateInput(UpsertBillContext context) {
        NormalizedBillInput input = context.input;
        if (input.origin().equals(input.destination())) {
            throw new BusinessRuleException("始发地与目的地不能相同");
        }
        if (input.dueDate() != null && input.dueDate().isBefore(input.date())) {
            throw new BusinessRuleException("应收日期不能早于运输日期");
        }
        if (context.bill != null && input.amount().compareTo(context.bill.getPaidAmount()) < 0) {
            throw new BusinessRuleException("账单金额不能小于已收金额");
        }
        if (context.bill == null && input.requestedStatus() != BillStatus.UNPAID) {
            throw new BusinessRuleException("请先创建账单，再通过收款接口登记收款");
        }
        if (context.bill != null && input.requestedStatus() != context.bill.getStatus()) {
            throw new BusinessRuleException("账单状态必须通过收款接口修改");
        }
    }

    private Bill requireBill(long tenantId, long id) {
        return billRepository.findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill " + id + " does not exist"));
    }

    private Bill requireBillForUpdate(long tenantId, long id) {
        return billRepository.findByIdAndTenantIdForUpdate(id, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Bill " + id + " does not exist"));
    }

    private void resolveCreateIdempotency(UpsertBillContext context) {
        billRepository.findByTenantIdAndCreationIdempotencyKey(
                        context.actor.tenantId(), context.idempotencyKey)
                .ifPresent(existing -> {
                    if (!context.requestHash.equals(existing.getCreationRequestHash())) {
                        throw new ConflictException(
                                "The Idempotency-Key was already used for a different bill request");
                    }
                    if (existing.getDeletedAt() != null) {
                        throw new ConflictException(
                                "The bill created by this Idempotency-Key has been deleted");
                    }
                    context.bill = existing;
                    context.result = BillResponse.from(existing);
                    context.halt();
        });
    }

    private void resolveCreateIdempotencyForUpdate(UpsertBillContext context) {
        billRepository.findByTenantIdAndCreationIdempotencyKeyForUpdate(
                        context.actor.tenantId(), context.idempotencyKey)
                .ifPresent(existing -> {
                    if (!context.requestHash.equals(existing.getCreationRequestHash())) {
                        throw new ConflictException(
                                "The Idempotency-Key was already used for a different bill request");
                    }
                    if (existing.getDeletedAt() != null) {
                        throw new ConflictException(
                                "The bill created by this Idempotency-Key has been deleted");
                    }
                    context.bill = existing;
                    context.result = BillResponse.from(existing);
                    context.halt();
                });
    }

    private static String requireIdempotencyKey(String value) {
        return IdempotencyKeys.require(value);
    }

    private static String billRequestHash(NormalizedBillInput input) {
        return CanonicalValues.builder()
                .add(input.shipper())
                .add(input.shipperNormalized())
                .add(input.vehicleCargo())
                .add(input.date())
                .add(input.origin())
                .add(input.destination())
                .add(input.amount().stripTrailingZeros().toPlainString())
                .add(input.requestedStatus())
                .add(input.dueDate())
                .addCollection(input.tags().stream().sorted().toList())
                .digest();
    }

    private Sort parseSort(String value) {
        String raw = value == null || value.isBlank() ? "date,desc" : value.trim();
        String[] parts = raw.split(",", 2);
        String property = parts[0];
        if (!ALLOWED_SORTS.contains(property)) {
            throw new BusinessRuleException("Unsupported sort property: " + property);
        }
        Sort.Direction direction = parts.length > 1
                ? Sort.Direction.fromOptionalString(parts[1]).orElseThrow(
                        () -> new BusinessRuleException("Unsupported sort direction: " + parts[1]))
                : Sort.Direction.ASC;
        return Sort.by(direction, property).and(Sort.by(Sort.Direction.DESC, "id"));
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new BusinessRuleException("开始日期不能晚于结束日期");
        }
    }

    private static String normalizeShipperName(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
        normalized = SHIPPER_PUNCTUATION.matcher(normalized).replaceAll("");
        return SHIPPER_SUFFIX.matcher(normalized).replaceFirst("");
    }

    private static String trimRequired(String value, String message) {
        String trimmed = trimToEmpty(value);
        if (trimmed.isEmpty()) {
            throw new BusinessRuleException(message);
        }
        return trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String trimToNull(String value) {
        String trimmed = trimToEmpty(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private abstract static class ActorContext extends BusinessContext {
        protected DemeterPrincipal actor;
    }

    private static final class ListBillsContext extends ActorContext {
        private final String keyword;
        private final String code;
        private final String shipper;
        private final BillStatus status;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final String tag;
        private final int page;
        private final int size;
        private final String sort;
        private Pageable pageable;
        private Page<Bill> pageResult;
        private PageResponse<BillResponse> result;

        private ListBillsContext(
                String keyword,
                String code,
                String shipper,
                BillStatus status,
                LocalDate startDate,
                LocalDate endDate,
                String tag,
                int page,
                int size,
                String sort) {
            this.keyword = keyword;
            this.code = code;
            this.shipper = shipper;
            this.status = status;
            this.startDate = startDate;
            this.endDate = endDate;
            this.tag = tag;
            this.page = page;
            this.size = size;
            this.sort = sort;
        }
    }

    private static final class GetBillContext extends ActorContext {
        private final long id;
        private Bill bill;
        private BillResponse result;

        private GetBillContext(long id) {
            this.id = id;
        }
    }

    private static final class UpsertBillContext extends ActorContext {
        private final Long id;
        private final String ifMatch;
        private final String requestedIdempotencyKey;
        private final BillUpsertRequest request;
        private long expectedVersion;
        private String idempotencyKey;
        private String requestHash;
        private NormalizedBillInput input;
        private String code;
        private Bill bill;
        private BillResponse result;

        private UpsertBillContext(
                Long id,
                String ifMatch,
                String requestedIdempotencyKey,
                BillUpsertRequest request) {
            this.id = id;
            this.ifMatch = ifMatch;
            this.requestedIdempotencyKey = requestedIdempotencyKey;
            this.request = request;
        }
    }

    private static final class DeleteBillsContext extends ActorContext {
        private final List<Long> requestedIds;
        private final String requestedIdempotencyKey;
        private final String requestedReason;
        private List<Long> ids;
        private String idempotencyKey;
        private String requestHash;
        private String reason;
        private List<Bill> bills;
        private DeleteBillsResponse result;

        private DeleteBillsContext(
                List<Long> requestedIds,
                String requestedIdempotencyKey,
                String requestedReason) {
            this.requestedIds = requestedIds;
            this.requestedIdempotencyKey = requestedIdempotencyKey;
            this.requestedReason = requestedReason;
        }
    }

    private static final class SuggestShippersContext extends ActorContext {
        private final String keyword;
        private final int limit;
        private String search;
        private List<String> names;
        private List<ShipperSuggestionResponse> result;

        private SuggestShippersContext(String keyword, int limit) {
            this.keyword = keyword;
            this.limit = limit;
        }
    }

    private static final class RestoreBillContext extends ActorContext {
        private final long id;
        private final String requestedIdempotencyKey;
        private final RestoreBillRequest request;
        private String idempotencyKey;
        private String requestHash;
        private String reason;
        private Bill bill;
        private BillResponse result;

        private RestoreBillContext(long id, String requestedIdempotencyKey, RestoreBillRequest request) {
            this.id = id;
            this.requestedIdempotencyKey = requestedIdempotencyKey;
            this.request = request;
        }
    }

    private static final class ResolveShipperContext extends ActorContext {
        private final String input;
        private String value;
        private String normalized;
        private Bill existing;
        private ShipperResolutionResponse result;

        private ResolveShipperContext(String input) {
            this.input = input;
        }
    }

    private static final class SuggestSearchContext extends ActorContext {
        private final String keyword;
        private final int limit;
        private String search;
        private final List<SearchSuggestionResponse> suggestions = new ArrayList<>();
        private List<SearchSuggestionResponse> result;

        private SuggestSearchContext(String keyword, int limit) {
            this.keyword = keyword;
            this.limit = limit;
        }
    }

    private record NormalizedBillInput(
            String shipper,
            String shipperNormalized,
            String vehicleCargo,
            LocalDate date,
            String origin,
            String destination,
            BigDecimal amount,
            BillStatus requestedStatus,
            LocalDate dueDate,
            Set<String> tags) {
    }
}
