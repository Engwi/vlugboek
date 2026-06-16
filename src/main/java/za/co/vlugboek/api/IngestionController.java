package za.co.vlugboek.api;

import java.time.LocalDate;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import za.co.vlugboek.api.dto.IngestionRunDto;
import za.co.vlugboek.api.dto.IngestionWorkspaceDto;
import za.co.vlugboek.domain.AppUser;
import za.co.vlugboek.domain.IngestionRun;
import za.co.vlugboek.repo.IngestionItemRepository;
import za.co.vlugboek.repo.IngestionRunRepository;
import za.co.vlugboek.service.IngestionPaths;
import za.co.vlugboek.service.IngestionPipelineService;

@RestController
@RequestMapping("/api/admin/ingestion-runs")
public class IngestionController {
    private final IngestionPipelineService ingestionPipeline;
    private final IngestionRunRepository runs;
    private final IngestionItemRepository items;

    public IngestionController(IngestionPipelineService ingestionPipeline, IngestionRunRepository runs,
                               IngestionItemRepository items) {
        this.ingestionPipeline = ingestionPipeline;
        this.runs = runs;
        this.items = items;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public IngestionWorkspaceDto workspace() {
        IngestionPaths paths = ingestionPipeline.paths();
        return new IngestionWorkspaceDto(
                paths.rootPath(),
                paths.inboxPath(),
                paths.processingPath(),
                paths.importedPath(),
                paths.skippedPath(),
                paths.rejectedPath(),
                paths.reportsPath(),
                recentRuns()
        );
    }

    @PostMapping
    @Transactional
    public IngestionRunDto run(@RequestBody(required = false) IngestionRunRequest request, Authentication authentication) {
        AppUser actor = currentUser(authentication);
        LocalDate effectiveDate = request == null ? null : request.effectiveDate();
        if (effectiveDate == null) {
            throw new IllegalArgumentException("Choose an effective date for this ingestion run");
        }
        IngestionRun run = ingestionPipeline.runInbox(actor, effectiveDate);
        return Dtos.ingestionRun(run, items.findByRunIdOrderByIdAsc(run.getId()));
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public IngestionRunDto run(@PathVariable Long id) {
        IngestionRun run = runs.findById(id).orElseThrow();
        return Dtos.ingestionRun(run, items.findByRunIdOrderByIdAsc(id));
    }

    @GetMapping(value = "/{id}/report.html", produces = MediaType.TEXT_HTML_VALUE)
    @Transactional(readOnly = true)
    public String report(@PathVariable Long id) {
        return ingestionPipeline.reportHtml(runs.findById(id).orElseThrow());
    }

    private List<IngestionRunDto> recentRuns() {
        return runs.findTop20ByOrderByStartedAtDesc().stream()
                .map(run -> Dtos.ingestionRun(run, items.findByRunIdOrderByIdAsc(run.getId())))
                .toList();
    }

    private AppUser currentUser(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            return user;
        }
        throw new IllegalArgumentException("Sign in to run ingestion");
    }

    public record IngestionRunRequest(LocalDate effectiveDate) {
    }
}
