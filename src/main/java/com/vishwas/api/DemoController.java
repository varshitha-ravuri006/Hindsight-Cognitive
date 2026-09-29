package com.vishwas.api;

import com.vishwas.demo.DemoService;
import com.vishwas.demo.SeedCatalog;
import com.vishwas.memory.MemoryStats;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

/** Reset, sample downloads and the seeded vendor letters. */
@RestController
@RequestMapping("/api")
public class DemoController {

    private final DemoService demo;
    private final SeedCatalog seed;
    private final MemoryStats stats;
    private final com.vishwas.workflow.LetterService letters;

    public DemoController(DemoService demo, SeedCatalog seed, MemoryStats stats, com.vishwas.workflow.LetterService letters) {
        this.demo = demo;
        this.seed = seed;
        this.stats = stats;
        this.letters = letters;
    }

    @PostMapping("/demo/reset")
    public DemoService.ResetResult reset() {
        DemoService.ResetResult r = demo.reset();
        stats.invalidate();
        return r;
    }

    @GetMapping("/samples/{period}/{kind}")
    public ResponseEntity<byte[]> sample(@PathVariable String period, @PathVariable String kind) {
        YearMonth.parse(period);
        boolean books = "purchase-register.csv".equals(kind);
        if (!books && !"gstr2b.json".equals(kind)) {
            throw new IllegalArgumentException("Unknown sample " + kind);
        }
        byte[] bytes = books ? seed.books(period) : seed.gstr2b(period);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + period + "-" + kind + "\"")
                .contentType(books ? MediaType.parseMediaType("text/csv") : MediaType.APPLICATION_JSON)
                .body(bytes);
    }

    /** A seeded letter or one uploaded in the app. */
    @GetMapping("/letters/{name}")
    public ResponseEntity<byte[]> letter(@PathVariable String name) {
        byte[] bytes = seed.hasLetter(name) ? seed.letter(name)
                : letters.read(name).orElseThrow(() -> new java.util.NoSuchElementException("No letter " + name));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(bytes);
    }
}
