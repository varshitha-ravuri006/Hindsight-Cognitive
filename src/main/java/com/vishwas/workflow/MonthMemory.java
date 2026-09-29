package com.vishwas.workflow;

import com.vishwas.ingest.Fmt;
import com.vishwas.memory.MemoryWriter;
import com.vishwas.memory.hindsight.MemoryItem;
import com.vishwas.outcomes.VendorCommunication;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/** Everything memory should learn from one processed month, as memory items in time order. */
@Component
public class MonthMemory {

    private final MemoryWriter writer;

    public MonthMemory(MemoryWriter writer) {
        this.writer = writer;
    }

    public List<MemoryItem> items(MonthProcessor.Result r) {
        List<MemoryItem> items = new ArrayList<>();
        r.judged().forEach(m -> items.add(writer.outcome(m)));
        r.judgedRecommendations().forEach(j -> items.add(writer.judged(j)));
        for (VendorCommunication c : r.promisesSettled()) {
            String detail = c.getPromiseStatus() == VendorCommunication.PromiseStatus.KEPT
                    ? "Confirmed in the " + Fmt.month(r.period()) + " GSTR-2B."
                    : "The invoices were still not resolved in the " + Fmt.month(r.period()) + " GSTR-2B generated on "
                    + Fmt.day(YearMonth.parse(r.period()).plusMonths(1).atDay(14)) + ".";
            items.add(writer.promiseResult(c, detail));
        }
        r.detected().forEach(m -> items.add(writer.detected(m)));
        r.vendorMonths().forEach(v -> items.add(writer.vendorMonth(v)));
        return items;
    }
}
