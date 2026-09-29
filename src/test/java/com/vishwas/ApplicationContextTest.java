package com.vishwas;

import com.vishwas.memory.MemoryHealth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/** The app boots with no keys at all: memory is reported as not configured instead of failing. */
@SpringBootTest
class ApplicationContextTest {

    @Autowired
    MemoryHealth health;

    @Test
    void bootsWithoutExternalKeys() {
        assertThat(health.state()).isEqualTo(MemoryHealth.State.NOT_CONFIGURED);
    }
}
