package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.BARRIER_NEW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_NEW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_SEND;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SIGNAL_NEW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SIGNAL_NOTIFY;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.WAIT;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.WAIT_ANY;

import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * The sync half of the error philosophy: an unknown id, a wrong-kind operation, a nonsense
 * argument or an exceeded cap kills the animation with a message that names the id and the kind.
 * Nothing is ever a silent no-op (the sole exception, notifying a signal nobody waits on, is
 * covered in {@link SyncWaitableTest}).
 */
class SyncErrorPathsTest {

    @Test
    void waitOnUnknownIdKills() {
        SyncRun.run(new P().i32(999).call(WAIT)).assertKilled("unknown sync object id 999");
    }

    @Test
    void notifyOnABarrierKills() {
        P main = new P().i32(2).call(BARRIER_NEW).set(0).get(0).i32(0).call(SIGNAL_NOTIFY);
        SyncRun.run(main).assertKilled("signal_notify", "is a barrier, not a signal");
    }

    @Test
    void waitOnAChannelKills() {
        P main = new P().i32(4).call(CHANNEL_NEW).set(0).get(0).call(WAIT);
        SyncRun.run(main).assertKilled("wait(1)", "not a waitable");
    }

    @Test
    void compositeOverAChannelKills() {
        P main = new P()
                .call(SIGNAL_NEW).set(0)
                .i32(4).call(CHANNEL_NEW).set(1)
                .get(0).get(1).call(WAIT_ANY).drop();
        SyncRun.run(main).assertKilled("wait_any", "not a waitable");
    }

    @Test
    void sendOnASignalKills() {
        P main = new P().call(SIGNAL_NEW).set(0).get(0).i32(0).i32(1).call(CHANNEL_SEND);
        SyncRun.run(main).assertKilled("channel_send", "is a signal, not a channel");
    }

    @Test
    void notifyModeOutOfRangeKills() {
        P main = new P().call(SIGNAL_NEW).set(0).get(0).i32(4).call(SIGNAL_NOTIFY);
        SyncRun.run(main).assertKilled("mode out of range 0..3");
    }

    @Test
    void barrierWithoutArrivalsKills() {
        SyncRun.run(new P().i32(0).call(BARRIER_NEW).drop())
                .assertKilled("barrier_new(0)", "at least 1");
    }

    @Test
    void channelWithoutCapacityKills() {
        SyncRun.run(new P().i32(0).call(CHANNEL_NEW).drop())
                .assertKilled("channel_new(0)", "at least 1");
    }

    @Test
    void compositeNestingDepthCapKills() {
        // Each pass wraps the accumulated composite in another wait_any, so depth grows by one.
        P main = new P()
                .call(SIGNAL_NEW).set(0)
                .get(0).set(1)
                .raw(0x03, 0x40)          // loop
                .get(1).get(0).call(WAIT_ANY).set(1)
                .raw(0x0C, 0x00, 0x0B);   // br 0, end

        SyncRun.run(main, 1 << 20, 0L, 2, 10_000_000L)
                .assertKilled("nesting deeper than " + SyncTable.MAX_COMPOSITE_DEPTH);
    }

    @Test
    void syncObjectCapKills() {
        // A runaway loop creating signals: the cap, not the fuel budget, is what stops it.
        P main = new P()
                .raw(0x03, 0x40)          // loop
                .call(SIGNAL_NEW).drop()
                .raw(0x0C, 0x00, 0x0B);   // br 0, end

        SyncRun.run(main, 1 << 20, 0L, 2, 10_000_000L)
                .assertKilled("sync-object cap of " + SyncTable.MAX_OBJECTS);
    }
}
