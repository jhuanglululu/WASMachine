package com.jhuanglululu.wasmachine.runtime;

import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_CLEAR;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_NEW;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_PEEK;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_PEEK_LEN;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_RECV;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_RECV_LEN;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.CHANNEL_TRY_LEN;
import static com.jhuanglululu.wasmachine.runtime.SyncWasm.SCRATCH;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.jhuanglululu.wasmachine.runtime.SyncWasm.P;
import org.junit.jupiter.api.Test;

/**
 * Channels through real WASM: parking on both ends, admission by park order, the race-free
 * length-then-copy pairs, and the byte budget. Payloads are slices of the module's letter table,
 * so a received payload shows up verbatim in the log trace.
 */
class SyncChannelTest {

    private static final int B = 1;
    private static final int Y = 24;
    private static final int Z = 25;

    @Test
    void parkedReceiversAreServedInParkOrderWithTheirOwnMessage() {
        // Task 2 parks first (task 1 sleeps a tick), so it gets "A" and task 1 gets "B" — even
        // though they resume in spawn order, which is why each release must reserve its message.
        P main = new P()
                .i32(4).call(CHANNEL_NEW).set(0)
                .child(0, new P().sleep(1).recvAndLog(0, 1, SCRATCH))
                .child(0, new P().recvAndLog(0, 1, SCRATCH + 8))
                .sleep(2)
                .send(0, 0, 1)
                .send(0, 1, 1)
                .log(Z)
                .sleep(5);

        assertEquals("ZBA", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void sendParksWhenFullAndIsAdmittedOnTheNextDequeue() {
        // Capacity 1: the second send parks, so "Z" can only be logged after the receive at
        // tick 3 frees the slot.
        P main = new P()
                .i32(1).call(CHANNEL_NEW).set(0)
                .child(0, new P().sleep(3).recvAndLog(0, 1, SCRATCH))
                .send(0, 0, 1)
                .send(0, 1, 1)
                .log(Z)
                .sleep(5);

        assertEquals("AZ", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void peekDoesNotPopAndTryLenNeverParks() {
        // try_len is -1 on an empty channel ("X"), 1 while "A" is queued ("Y"), and -1 again once
        // the receive popped it — with a peek in between that must leave the message in place.
        P main = new P()
                .i32(4).call(CHANNEL_NEW).set(0)
                .get(0).call(CHANNEL_TRY_LEN).ifEq(-1, new P().log(23))
                .send(0, 0, 1)
                .get(0).call(CHANNEL_PEEK_LEN).set(1)
                .get(0).i32(SCRATCH).call(CHANNEL_PEEK).logBytes(SCRATCH, 1)
                .get(0).call(CHANNEL_TRY_LEN).ifEq(1, new P().log(Y))
                .recvAndLog(0, 1, SCRATCH)
                .get(0).call(CHANNEL_TRY_LEN).ifEq(-1, new P().log(23));

        assertEquals("XAYAX", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void queueIsFifoAcrossSeveralMessages() {
        P main = new P()
                .i32(4).call(CHANNEL_NEW).set(0)
                .send(0, 0, 1)
                .send(0, 1, 1)
                .send(0, 2, 1)
                .recvAndLog(0, 1, SCRATCH)
                .recvAndLog(0, 1, SCRATCH)
                .recvAndLog(0, 1, SCRATCH);

        assertEquals("ABC", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void clearDropsQueuedMessagesAndAdmitsParkedSenders() {
        // "A" is queued and a parked sender holds the two bytes "BC". After clear the only
        // message in flight is "BC" (proving "A" was dropped, and that the sender was admitted).
        P main = new P()
                .i32(1).call(CHANNEL_NEW).set(0)
                .send(0, 0, 1)
                .child(0, new P().send(0, 1, 2).log(B))
                .sleep(1)
                .get(0).call(CHANNEL_CLEAR)
                .recvAndLog(0, 1, SCRATCH)
                .log(Z)
                .sleep(5);

        assertEquals("BCZB", SyncRun.run(main).assertFinished().trace());
    }

    @Test
    void receiveWithoutTheLengthCallKills() {
        P main = new P()
                .i32(4).call(CHANNEL_NEW).set(0)
                .send(0, 0, 1)
                .get(0).i32(SCRATCH).call(CHANNEL_RECV);

        SyncRun.run(main).assertKilled("channel_recv", "no message reserved");
    }

    @Test
    void bufferedChannelBytesCountTowardTheMemoryCap() {
        // A 16-byte cap and one-byte messages: the send that would hold a 17th byte is over the
        // instance's single memory budget and kills.
        P main = new P()
                .i32(1000).call(CHANNEL_NEW).set(0)
                .raw(0x03, 0x40)          // loop
                .send(0, 0, 1)
                .raw(0x0C, 0x00, 0x0B);   // br 0, end

        SyncRun.run(main, 16, 0L, 2, 10_000_000L)
                .assertKilled("memory cap of 16", "channel buffers");
    }
}
