package com.vishwas.api;

import com.vishwas.demo.HistoryLoader;
import com.vishwas.demo.SeedCatalog;
import com.vishwas.workflow.ReconcileService;
import com.vishwas.workflow.WorkspaceService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.YearMonth;

/**
 * Steps 1 to 4 of the product: load history, reconcile a month (sample files prefilled, uploads optional),
 * watch the advice arrive, and let the next month's GSTR-2B judge the open cases.
 */
@RestController
@RequestMapping("/api")
public class ReconcileController {

    private final HistoryLoader history;
    private final ReconcileService reconcile;
    private final WorkspaceService workspace;
    private final SeedCatalog seed;

    public ReconcileController(HistoryLoader history, ReconcileService reconcile, WorkspaceService workspace, SeedCatalog seed) {
        this.history = history;
        this.reconcile = reconcile;
        this.workspace = workspace;
        this.seed = seed;
    }

    @PostMapping("/history/load")
    public HistoryLoader.Status loadHistory() {
        return history.start();
    }

    @GetMapping("/history/status")
    public HistoryLoader.Status historyStatus() {
        return history.status();
    }

    @PostMapping(value = "/periods/{period}/reconcile", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.ALL_VALUE})
    public ReconcileService.ReconcileResult reconcile(@PathVariable String period,
                                                      @RequestParam(value = "books", required = false) MultipartFile books,
                                                      @RequestParam(value = "gstr2b", required = false) MultipartFile gstr2b) throws IOException {
        YearMonth.parse(period);
        if (!history.databaseLoaded()) {
            throw new IllegalStateException("Load the history first (Step 1).");
        }
        return reconcile.reconcile(period, file(books, SeedCatalog.BOOKS_FILE, () -> seed.books(period)),
                file(gstr2b, SeedCatalog.GSTR2B_FILE, () -> seed.gstr2b(period)));
    }

    @PostMapping("/periods/{period}/advise")
    public WorkspaceService.AdviceStatus readvise(@PathVariable String period) {
        YearMonth.parse(period);
        return workspace.adviceStatus(reconcile.readvise(period).getId());
    }

    @GetMapping("/periods/{period}/workspace")
    public WorkspaceService.Workspace workspace(@PathVariable String period) {
        YearMonth.parse(period);
        return workspace.workspace(period);
    }

    @GetMapping("/advice-runs/{id}")
    public WorkspaceService.AdviceStatus adviceRun(@PathVariable long id) {
        WorkspaceService.AdviceStatus s = workspace.adviceStatus(id);
        if (s == null) {
            throw new java.util.NoSuchElementException("No advice run " + id);
        }
        return s;
    }

    /** Step 4: the next month's GSTR-2B arrives (prepared sample unless a file is uploaded). */
    @PostMapping(value = "/periods/{period}/next-month", consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.ALL_VALUE})
    public ReconcileService.NextMonthResult nextMonth(@PathVariable String period,
                                                      @RequestParam(value = "gstr2b", required = false) MultipartFile gstr2b,
                                                      @RequestParam(value = "books", required = false) MultipartFile books) throws IOException {
        YearMonth.parse(period);
        return reconcile.nextMonth(period, file(gstr2b, SeedCatalog.GSTR2B_FILE, () -> seed.gstr2b(period)),
                file(books, SeedCatalog.BOOKS_FILE, () -> seed.books(period)));
    }

    @GetMapping("/periods/{period}/verdicts")
    public java.util.List<ReconcileService.VerdictView> verdicts(@PathVariable String period) {
        YearMonth.parse(period);
        return reconcile.verdicts(period);
    }

    private static ReconcileService.FileInput file(MultipartFile upload, String defaultName, java.util.function.Supplier<byte[]> sample)
            throws IOException {
        if (upload != null && !upload.isEmpty()) {
            return new ReconcileService.FileInput(upload.getOriginalFilename(), upload.getBytes());
        }
        return new ReconcileService.FileInput("sample " + defaultName, sample.get());
    }
}
