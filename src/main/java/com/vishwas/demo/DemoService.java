package com.vishwas.demo;

import com.vishwas.config.VishwasProperties;
import com.vishwas.memory.BankSetup;
import com.vishwas.memory.HistoryMemoryLoader;
import com.vishwas.memory.MemoryHealth;
import com.vishwas.memory.hindsight.HindsightClient;
import com.vishwas.memory.hindsight.HindsightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * One-click "Reset demo": clears every table and the configured Hindsight bank (deleted and set up again with
 * missions, directives and mental models). The seed is idempotent, so Step 1 can run again straight after.
 */
@Service
public class DemoService {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    /** Children before parents. The vendor master is re-seeded by the history loader. */
    static final List<String> TABLES = List.of("recommendation", "advice_run", "accountant_action", "vendor_communication",
            "mismatch", "invoice_record", "import_batch", "memory_batch", "vendor");

    public record ResetResult(boolean databaseCleared, boolean memoryCleared, String bankId, String message) {
    }

    private final JdbcTemplate jdbc;
    private final HindsightClient hindsight;
    private final BankSetup bankSetup;
    private final MemoryHealth health;
    private final HistoryMemoryLoader memoryLoader;
    private final VishwasProperties props;

    public DemoService(JdbcTemplate jdbc, HindsightClient hindsight, BankSetup bankSetup, MemoryHealth health,
                       HistoryMemoryLoader memoryLoader, VishwasProperties props) {
        this.jdbc = jdbc;
        this.hindsight = hindsight;
        this.bankSetup = bankSetup;
        this.health = health;
        this.memoryLoader = memoryLoader;
        this.props = props;
    }

    public ResetResult reset() {
        if (!props.demo().resetEnabled()) {
            throw new IllegalStateException("Reset is disabled on this deployment (VISHWAS_RESET_ENABLED=false)");
        }
        memoryLoader.cancel();
        clearDatabase();
        String bank = props.hindsight().bankId();
        if (!hindsight.configured()) {
            return new ResetResult(true, false, bank, "Database cleared. Memory is not configured.");
        }
        try {
            try {
                hindsight.deleteBank(bank);
            } catch (HindsightException e) {
                if (!e.notFound()) {
                    throw e;
                }
            }
            bankSetup.setup(bank);
            health.set(MemoryHealth.State.READY, null);
            log.info("Demo reset: database cleared and bank '{}' recreated", bank);
            return new ResetResult(true, true, bank, "Database and memory bank cleared.");
        } catch (HindsightException e) {
            health.set(MemoryHealth.State.UNREACHABLE, e.getMessage());
            log.warn("Demo reset could not clear memory: {}", e.getMessage());
            return new ResetResult(true, false, bank, "Database cleared, but the memory bank could not be cleared: " + e.getMessage());
        }
    }

    @Transactional
    public void clearDatabase() {
        TABLES.forEach(t -> jdbc.update("DELETE FROM " + t));
    }
}
