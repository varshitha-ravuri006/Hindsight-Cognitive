package com.vishwas.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.vishwas.config.VishwasProperties;
import com.vishwas.demo.SnapshotStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;

/** Save and serve the recording of a real run for the "Offline replay of a recorded run" mode. */
@RestController
@RequestMapping("/api/snapshot")
public class SnapshotController {

    private final SnapshotStore store;
    private final VishwasProperties props;

    public SnapshotController(SnapshotStore store, VishwasProperties props) {
        this.store = store;
        this.props = props;
    }

    @GetMapping("/info")
    public SnapshotStore.Info info() {
        return store.info();
    }

    @GetMapping
    public JsonNode get() {
        return store.load().orElseThrow(() -> new NoSuchElementException("No recording yet. Record a run first."));
    }

    @PostMapping
    public SnapshotStore.Info save(@RequestBody JsonNode recording) {
        return store.save(recording, props.hindsight().bankId());
    }
}
