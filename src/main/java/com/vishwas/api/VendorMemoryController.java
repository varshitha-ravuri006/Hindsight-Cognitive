package com.vishwas.api;

import com.vishwas.memory.CurationService;
import com.vishwas.memory.MemoryCorrection;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.workflow.DossierService;
import com.vishwas.workflow.LetterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Vendor-level memory work: correct the history, upload letters, the knowledge page and the dossier export. */
@RestController
@RequestMapping("/api/vendors/{gstin}")
public class VendorMemoryController {

    public record CorrectionRequest(@NotBlank String memoryId, @NotNull MemoryCorrection.Action action,
                                    @Size(max = 4000) String newText, @NotBlank @Size(max = 1000) String reason,
                                    @Size(max = 100) String by) {
    }

    private final CurationService curation;
    private final LetterService letters;
    private final DossierService dossiers;
    private final MemoryPublisher publisher;
    private final com.vishwas.config.VishwasProperties props;

    public VendorMemoryController(CurationService curation, LetterService letters, DossierService dossiers, MemoryPublisher publisher,
                                  com.vishwas.config.VishwasProperties props) {
        this.curation = curation;
        this.letters = letters;
        this.dossiers = dossiers;
        this.publisher = publisher;
        this.props = props;
    }

    /** The vendor's raw facts in memory, for "Correct this history". */
    @GetMapping("/memory-facts")
    public Map<String, Object> facts(@PathVariable String gstin) {
        if (!publisher.enabled()) {
            return Map.of("available", false, "message", "Memory is off.", "corrections", curation.history(gstin));
        }
        try {
            return Map.of("available", true, "facts", curation.facts(gstin), "corrections", curation.history(gstin));
        } catch (HindsightException e) {
            return Map.of("available", false, "message", "Memory did not answer: " + e.getMessage(), "corrections", curation.history(gstin));
        }
    }

    @PostMapping("/corrections")
    public MemoryCorrection correct(@PathVariable String gstin, @Valid @RequestBody CorrectionRequest r) {
        if (!publisher.enabled()) {
            throw new IllegalStateException("Memory is off, so there is no history to correct.");
        }
        String actor = r.by() == null || r.by().isBlank() ? props.company().accountant() : r.by();
        return curation.correct(gstin, r.memoryId(), r.action(), r.newText(), r.reason(), actor);
    }

    @PostMapping(value = "/letters", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LetterService.Uploaded upload(@PathVariable String gstin, @RequestParam("file") MultipartFile file,
                                         @RequestParam String summary,
                                         @RequestParam(required = false) LocalDate receivedOn,
                                         @RequestParam(required = false) LocalDate promiseBy,
                                         @RequestParam(required = false) String invoices,
                                         @RequestParam(required = false) String from) throws IOException {
        List<String> refs = invoices == null || invoices.isBlank() ? List.of()
                : Arrays.stream(invoices.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return letters.upload(gstin, file.getOriginalFilename(), file.getBytes(), receivedOn, summary, promiseBy, refs, from);
    }

    @GetMapping("/page")
    public DossierService.PageView page(@PathVariable String gstin) {
        return dossiers.page(gstin);
    }

    @PostMapping("/page/refresh")
    public Map<String, Object> refreshPage(@PathVariable String gstin) {
        try {
            return Map.of("operationId", dossiers.refresh(gstin).orElse(""));
        } catch (HindsightException e) {
            throw new IllegalStateException("Memory did not answer: " + e.getMessage());
        }
    }

    @GetMapping("/dossier")
    public ResponseEntity<byte[]> dossier(@PathVariable String gstin) {
        byte[] md = dossiers.dossier(gstin).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"vishwas-dossier-" + gstin + ".md\"")
                .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
                .body(md);
    }
}
