package com.jhuanglululu.wasmachine.runtime;

/**
 * Receives guest {@code log} output. The plugin routes this to the configured
 * {@code log_viewers}; tests record it. {@code fail} does not come here — it ends the
 * animation and surfaces as an errored {@link TickResult}.
 */
@FunctionalInterface
public interface LogSink {

    void log(String animationName, String message);
}
