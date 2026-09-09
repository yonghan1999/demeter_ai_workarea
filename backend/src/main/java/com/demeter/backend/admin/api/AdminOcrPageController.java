package com.demeter.backend.admin.api;

import com.demeter.backend.admin.application.AdminOcrCommandService;
import com.demeter.backend.admin.application.AdminQueryFilters.OcrFilter;
import com.demeter.backend.admin.application.AdminQueryService;
import com.demeter.backend.config.ConditionalOnRuntimeRole;
import com.demeter.backend.config.RuntimeRole;
import com.demeter.backend.ocr.domain.OcrTaskStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@ConditionalOnRuntimeRole(RuntimeRole.API)
@RequestMapping("/admin/ocr")
public class AdminOcrPageController {
    private final AdminQueryService queries;
    private final AdminOcrCommandService commands;
    private final AdminPageRequestFactory pages;
    private final AdminOperationFeedback feedback;

    public AdminOcrPageController(AdminQueryService queries, AdminOcrCommandService commands,
            AdminPageRequestFactory pages, AdminOperationFeedback feedback) {
        this.queries = queries;
        this.commands = commands;
        this.pages = pages;
        this.feedback = feedback;
    }

    @GetMapping
    String list(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "") String taskId, @RequestParam(required = false) Long tenantId,
            @RequestParam(required = false) OcrTaskStatus status,
            @RequestParam(defaultValue = "") String errorCode, Model model) {
        OcrFilter filter = new OcrFilter(
                pages.queryText(taskId), tenantId, status, pages.queryText(errorCode));
        model.addAttribute("filter", filter);
        model.addAttribute("statuses", OcrTaskStatus.values());
        model.addAttribute("page", queries.ocr(filter, pages.descending(page, "createdAt")));
        return "admin/ocr";
    }

    @PostMapping("/{id}/retry")
    String retry(@PathVariable String id, @RequestParam String reason, @RequestParam String idempotencyKey,
            RedirectAttributes redirect) {
        return feedback.execute(() -> commands.retry(id, reason, idempotencyKey), redirect,
                "/admin/ocr", "OCR 任务已重新排队");
    }
}
