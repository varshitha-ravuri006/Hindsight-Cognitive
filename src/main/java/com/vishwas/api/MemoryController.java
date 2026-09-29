package com.vishwas.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vishwas.advisor.AdviceService;
import com.vishwas.advisor.Recommendation;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Mismatch;
import com.vishwas.memory.MemoryBatchRepository;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.MentalModels;
import com.vishwas.memory.VendorMemory;
import com.vishwas.workflow.ReconcileService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** "What Vishwas remembered": recalled facts, observations and mental models behind the current view. */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final ReconcileService reconcile;
    private final AdviceService advice;
    private final VendorMemory vendorMemory;
    private final MentalModels mentalModels;
    private final MemoryPublisher publisher;
    private final MemoryBatchRepository batches;
    private final VendorRepository vendors;
    private final ObjectMapper json;
    private final ExecutorService io;

    public MemoryController(ReconcileService reconcile, AdviceService advice, VendorMemory vendorMemory, MentalModels mentalModels,
                            MemoryPublisher publisher, MemoryBatchRepository batches, VendorRepository vendors, ObjectMapper json,
                            ExecutorService ioExecutor) {
        this.reconcile = reconcile;
        this.advice = advice;
        this.vendorMemory = vendorMemory;
        this.mentalModels = mentalModels;
        this.publisher = publisher;
        this.batches = batches;
        this.vendors = vendors;
        this.json = json;
        this.io = ioExecutor;
    }

    @GetMapping("/drawer")
    public Map<String, Object> drawer(@RequestParam String period) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Mismatch> cases = reconcile.casesInView(period);
        Map<Long, Recommendation> recs = advice.latest(cases);
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Recommendation r : recs.values()) {
            for (Map<String, Object> f : parse(r.getMemoryFactsJson())) {
                if (seen.add(String.valueOf(f.get("id")))) {
                    Map<String, Object> copy = new LinkedHashMap<>(f);
                    copy.put("vendor", vendors.findById(r.getVendorGstin()).map(v -> v.getLegalName()).orElse(r.getVendorGstin()));
                    facts.add(copy);
                }
            }
        }
        out.put("facts", facts);
        out.put("batches", batches.findAllByOrderByStartedAtAsc());
        if (!publisher.enabled()) {
            out.put("available", false);
            return out;
        }
        List<String> gstins = cases.stream().filter(m -> m.getStatus().open()).map(Mismatch::getVendorGstin).distinct().toList();
        List<CompletableFuture<Map<String, Object>>> futures = gstins.stream().map(g -> CompletableFuture.supplyAsync(() -> {
            String name = vendors.findById(g).map(v -> v.getLegalName()).orElse(g);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("gstin", g);
            v.put("vendor", name);
            try {
                v.put("beliefs", vendorMemory.learned(g, name));
            } catch (RuntimeException e) {
                v.put("error", e.getMessage());
            }
            return v;
        }, io)).toList();
        List<Map<String, Object>> observations = new ArrayList<>();
        for (var f : futures) {
            try {
                observations.add(f.get(25, TimeUnit.SECONDS));
            } catch (Exception e) {
                observations.add(Map.of("error", "timed out"));
            }
        }
        out.put("observations", observations);
        out.put("mentalModels", mentalModels.all());
        out.put("available", true);
        return out;
    }

    @GetMapping("/mental-models")
    public Map<String, Object> models() {
        if (!publisher.enabled()) {
            return Map.of("available", false);
        }
        return Map.of("available", true, "models", mentalModels.all());
    }

    @PostMapping("/mental-models/refresh")
    public Map<String, Object> refresh() {
        return Map.of("operations", mentalModels.refreshAll());
    }

    private List<Map<String, Object>> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
