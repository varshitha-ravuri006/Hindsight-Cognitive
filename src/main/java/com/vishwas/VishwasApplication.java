package com.vishwas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Vishwas ("trust"): a GST reconciliation workspace whose signature feature is memory.
 * Reconciliation tools find the mismatch. Vishwas remembers what it turned out to be.
 */
@SpringBootApplication
public class VishwasApplication {

    public static void main(String[] args) {
        SpringApplication.run(VishwasApplication.class, args);
    }
}
