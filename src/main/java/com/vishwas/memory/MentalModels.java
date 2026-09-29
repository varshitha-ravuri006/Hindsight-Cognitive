package com.vishwas.memory;

import com.vishwas.config.VishwasProperties;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import com.vishwas.memory.hindsight.MentalModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The two curated mental models ("Money at risk briefing", "Vendor watchlist"). They are refreshed after
 * each processed month; the UI shows their content and when they were last refreshed.
 */
@Component
public class MentalModels {

    private static final Logger log = LoggerFactory.getLogger(MentalModels.class);

    public record View(String id, String name, String content, String lastRefreshedAt, boolean stale, String error) {
    }

    private final HindsightClient hindsight;
    private final String bankId;

    public MentalModels(HindsightClient hindsight, VishwasProperties props) {
        this.hindsight = hindsight;
        this.bankId = props.hindsight().bankId();
    }

    /** Submits a refresh of every model (asynchronous on Hindsight's side). Never throws. */
    public List<String> refreshAll() {
        List<String> ops = new ArrayList<>();
        for (var spec : MemoryDesign.MENTAL_MODELS) {
            try {
                String op = hindsight.refreshMentalModel(bankId, spec.id());
                if (op != null) {
                    ops.add(op);
                }
            } catch (HindsightException e) {
                log.warn("Refreshing mental model {} failed: {}", spec.id(), e.getMessage());
            }
        }
        return ops;
    }

    public List<View> all() {
        List<View> out = new ArrayList<>();
        for (var spec : MemoryDesign.MENTAL_MODELS) {
            try {
                MentalModel m = hindsight.getMentalModel(bankId, spec.id()).orElse(null);
                out.add(m == null
                        ? new View(spec.id(), spec.name(), null, null, false, "Not created yet")
                        : new View(m.id(), m.name(), m.content(), m.lastRefreshedAt(), Boolean.TRUE.equals(m.isStale()), null));
            } catch (HindsightException e) {
                out.add(new View(spec.id(), spec.name(), null, null, false, e.getMessage()));
            }
        }
        return out;
    }
}
