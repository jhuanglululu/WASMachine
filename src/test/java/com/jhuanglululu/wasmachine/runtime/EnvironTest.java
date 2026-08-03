package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.ENVIRON_LEN;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.ENVIRON_READ;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SCRATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.wasm.Module;
import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The environ blob, checked against a byte sequence written out by hand here — never against
 * anything the engine computed. The guest reads the blob with the len/fill pair and logs it
 * verbatim; the test compares that against the literal bytes the wire format calls for.
 *
 * <p>The fixtures keep every key and value short ASCII on purpose: then every length field is a
 * single non-zero byte followed by three NULs, and the whole blob is valid UTF-8, so it can
 * cross the {@code log} import unchanged and be compared as a string.
 */
class EnvironTest {

    private static final long BUDGET = 10_000_000L;

    /** Reads the blob into {@code SCRATCH} with the two-call idiom and logs it verbatim. */
    private static P readAndLogBlob() {
        return new P()
                .call(ENVIRON_LEN).set(1)
                .i32(SCRATCH).call(ENVIRON_READ)
                .logBytes(SCRATCH, 1);
    }

    private static SyncRun.Result run(P main, Map<String, String> environ) {
        List<String> logs = new ArrayList<>();
        MachineInstance inst = new MachineInstance(Module.parse(SyncWasm.module(main)),
                new MachineInstance.Config("env", RuntimeWasm.ENGINE_MODULE, "_engine_main",
                        List.of(SyncRun.ENGINE_ABI), 1 << 20, 0L, environ,
                        MachineInstance.Config.DEFAULT_TASK_STACK_BYTES),
                (name, message) -> logs.add(message), Map.of());
        return SyncRun.drive((tick, fuel) -> SyncRun.outcomeOf(inst.tick(tick, fuel)),
                logs, 10, BUDGET);
    }

    @Test
    void theBlobIsExactlyTheWireFormat() {
        // Written out by hand from the spec: u32 count, then per entry u32 key_len, key,
        // u32 value_len, value — little-endian, sorted by raw key bytes. Insertion order here
        // is deliberately the reverse of the required order, so the sort has to do real work.
        Map<String, String> environ = new LinkedHashMap<>();
        environ.put("b", "two");
        environ.put("alpha", "one");

        byte[] expected = {
            2, 0, 0, 0,                       // 2 entries
            5, 0, 0, 0, 'a', 'l', 'p', 'h', 'a',
            3, 0, 0, 0, 'o', 'n', 'e',
            1, 0, 0, 0, 'b',
            3, 0, 0, 0, 't', 'w', 'o',
        };

        SyncRun.Result result = run(readAndLogBlob(), environ);
        result.assertFinished();
        assertEquals(new String(expected, StandardCharsets.UTF_8), result.trace());
    }

    @Test
    void environLenIsTheBlobLength() {
        // 4 (count) + 4 + 5 + 4 + 3 (alpha/one) + 4 + 1 + 4 + 3 (b/two) = 32, counted by hand.
        Map<String, String> environ = new LinkedHashMap<>();
        environ.put("b", "two");
        environ.put("alpha", "one");

        P main = new P().call(ENVIRON_LEN).ifEq(32, new P().log(0));

        assertEquals("A", run(main, environ).assertFinished().trace());
    }

    @Test
    void anEmptyEnvironIsZeroBytesNotAZeroCount() {
        // 0 is the guest's whole emptiness test: it must never have to read four bytes to
        // discover there is nothing there.
        P main = new P().call(ENVIRON_LEN).ifEq(0, new P().log(0));

        assertEquals("A", run(main, Map.of()).assertFinished().trace());
    }

    @Test
    void everyByteOfAValueSurvivesIncludingSpacesAndPunctuation() {
        // A single entry, so the whole blob is hand-checkable in one literal.
        byte[] expected = {
            1, 0, 0, 0,
            3, 0, 0, 0, 'k', 'e', 'y',
            7, 0, 0, 0, 'a', ' ', 'b', '=', 'c', ',', 'd',
        };

        SyncRun.Result result = run(readAndLogBlob(), Map.of("key", "a b=c,d"));
        result.assertFinished();
        assertEquals(new String(expected, StandardCharsets.UTF_8), result.trace());
    }

    @Test
    void anEmptyValueIsKeptAsAZeroLengthEntry() {
        byte[] expected = {
            1, 0, 0, 0,
            1, 0, 0, 0, 'k',
            0, 0, 0, 0,
        };

        SyncRun.Result result = run(readAndLogBlob(), Map.of("k", ""));
        result.assertFinished();
        assertEquals(new String(expected, StandardCharsets.UTF_8), result.trace());
    }

    @Test
    void keysAreOrderedByRawByteValueNotByCase() {
        // 'Z' is 0x5A and 'a' is 0x61, so byte order puts the capital first — the point being
        // that nothing locale-aware is involved.
        Map<String, String> environ = new LinkedHashMap<>();
        environ.put("a", "1");
        environ.put("Z", "2");

        byte[] expected = {
            2, 0, 0, 0,
            1, 0, 0, 0, 'Z',
            1, 0, 0, 0, '2',
            1, 0, 0, 0, 'a',
            1, 0, 0, 0, '1',
        };

        SyncRun.Result result = run(readAndLogBlob(), environ);
        result.assertFinished();
        assertEquals(new String(expected, StandardCharsets.UTF_8), result.trace());
    }

    @Test
    void everyTaskSeesTheSameEnviron() {
        // The blob is instance state, not task state: a spawned task reads the same bytes.
        Map<String, String> environ = Map.of("k", "v");
        byte[] expected = {
            1, 0, 0, 0,
            1, 0, 0, 0, 'k',
            1, 0, 0, 0, 'v',
        };
        String blob = new String(expected, StandardCharsets.UTF_8);

        P main = new P().child(0, readAndLogBlob()).append(readAndLogBlob()).sleep(2);

        assertEquals(blob + blob, run(main, environ).assertFinished().trace());
    }
}
