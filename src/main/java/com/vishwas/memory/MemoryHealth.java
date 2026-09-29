package com.vishwas.memory;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/** Remembers whether bank setup succeeded so the header chip and /health tell the truth. */
@Component
public class MemoryHealth {

    public enum State { NOT_CONFIGURED, CONNECTING, READY, UNREACHABLE }

    private final AtomicReference<State> state = new AtomicReference<>(State.CONNECTING);
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public void set(State s, String error) {
        state.set(s);
        lastError.set(error);
    }

    public State state() {
        return state.get();
    }

    public boolean ready() {
        return state.get() == State.READY;
    }

    public String lastError() {
        return lastError.get();
    }
}
