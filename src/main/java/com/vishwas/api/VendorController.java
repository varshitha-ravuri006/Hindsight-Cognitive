package com.vishwas.api;

import com.vishwas.advisor.VendorProfileService;
import com.vishwas.ingest.VendorRepository;
import com.vishwas.matching.Dimension;
import com.vishwas.memory.MemoryPublisher;
import com.vishwas.memory.VendorMemory;
import com.vishwas.memory.hindsight.HindsightException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Vendor intelligence: the database profile, and separately what memory learned and how that view changed. */
@RestController
@RequestMapping("/api/vendors")
public class VendorController {

    private final VendorProfileService profiles;
    private final VendorMemory memory;
    private final MemoryPublisher publisher;
    private final VendorRepository vendors;

    public VendorController(VendorProfileService profiles, VendorMemory memory, MemoryPublisher publisher, VendorRepository vendors) {
        this.profiles = profiles;
        this.memory = memory;
        this.publisher = publisher;
        this.vendors = vendors;
    }

    @GetMapping
    public List<VendorProfileService.VendorSummary> list() {
        return profiles.list();
    }

    @GetMapping("/{gstin}")
    public VendorProfileService.Profile profile(@PathVariable String gstin) {
        return profiles.profile(gstin);
    }

    /** "What Vishwas learned": observations scoped to this vendor only. */
    @GetMapping("/{gstin}/learned")
    public Map<String, Object> learned(@PathVariable String gstin) {
        String name = vendors.findById(gstin).orElseThrow(() -> new NoSuchElementException("Unknown vendor " + gstin)).getLegalName();
        return memoryCall(() -> Map.of("beliefs", memory.learned(gstin, name)));
    }

    /** Raw facts behind the beliefs, for the evidence drawer. */
    @GetMapping("/{gstin}/facts")
    public Map<String, Object> facts(@PathVariable String gstin, @RequestParam(defaultValue = "What happened with this vendor's mismatches?") String q) {
        return memoryCall(() -> Map.of("facts", memory.facts(gstin, q)));
    }

    /** "How Vishwas's view of this vendor changed", month by month, for one dimension. */
    @GetMapping("/{gstin}/belief-history")
    public Map<String, Object> beliefHistory(@PathVariable String gstin, @RequestParam(defaultValue = "TIMING") Dimension dimension) {
        return memoryCall(() -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("dimension", dimension.name());
            out.put("history", memory.beliefHistory(gstin, dimension).orElse(null));
            return out;
        });
    }

    private Map<String, Object> memoryCall(java.util.function.Supplier<Map<String, Object>> call) {
        if (!publisher.enabled()) {
            return Map.of("available", false, "message", "Memory is off: Hindsight is not configured or not reachable.");
        }
        try {
            Map<String, Object> out = new LinkedHashMap<>(call.get());
            out.put("available", true);
            return out;
        } catch (HindsightException e) {
            return Map.of("available", false, "message", "Memory did not answer: " + e.getMessage());
        }
    }
}
