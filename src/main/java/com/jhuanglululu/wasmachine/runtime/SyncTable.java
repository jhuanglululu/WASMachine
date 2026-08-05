package com.jhuanglululu.wasmachine.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The per-instance table of host-side sync objects — signals, barriers, and/or composites,
 * and channels — all in one {@code i32} id space (so ids are plain integers that survive a
 * fork's memory copy for free). This is the whole of the engine's sync semantics; it is pure
 * headless data structure work, and it never touches the interpreter: parking is expressed
 * by returning {@link SyncOutcome#PARK}, releasing by calling the {@link Waker}.
 *
 * <p><b>Error philosophy.</b> Nothing here is ever a silent no-op: an unknown id, a
 * wrong-kind operation, an out-of-range mode, or exceeding a cap throws
 * {@link GuestAbort} (or {@link MemoryCapExceededException}) and kills the animation
 * with a message naming the id and the kind. The single exception is {@code signal_notify}
 * on a signal with no waiters, which is defined as a no-op — signals are edge-triggered.
 *
 * <p><b>Waiting.</b> A task waits on one <em>root</em> waitable. The root's leaves (the
 * signals and barriers underneath its composite tree, computed once at composite creation)
 * each get a reference to the same waiter record. A leaf firing <em>latches</em> on that
 * waiter; the waiter is released when the boolean tree over its latched leaves evaluates
 * true. Latching is therefore per waiter, and a leaf that fires while a waiter is parked is
 * never lost.
 *
 * <p><b>Barriers inside composites.</b> Parking on a tree containing a barrier counts as an
 * arrival on that barrier. If the waiter is released through a different arm, the arrival is
 * given back ({@code arrivals--}); if the barrier itself completed, the waiter latched it and
 * passed it legitimately, so nothing is given back. A completed barrier resets its count and
 * is reusable.
 *
 * <p><b>Channels.</b> Bounded FIFO byte queues, whose buffered bytes are charged to the instance's
 * {@link MemoryBudget} — the same allowance the guest heaps draw on, so heap and queues together
 * stay under the one configured cap. {@code send} parks when full,
 * {@code recv_len}/{@code peek_len} park when empty, and admission is in park order. The
 * {@code *_len} then copy call pairs are race-free because the length call is the only
 * blocking point: it reserves the message it measured (a parked receiver's message is
 * removed from the queue at release time, so two receivers released by two sends can never
 * read the same bytes), and the copy call reads that reservation.
 *
 * <p>Not thread-safe: one animation instance runs one task at a time.
 */
public final class SyncTable {

    /** Receives a released task id plus the value its blocking host call must return. */
    public interface Waker {
        void release(int taskId, long resumeValue);
    }

    /**
     * The result of a potentially blocking channel operation: either it completed inline
     * with {@code value}, or the calling task must park (and will be handed its result
     * through the {@link Waker} when released).
     */
    public record SyncOutcome(boolean parked, long value) {

        /** The caller must park. */
        public static final SyncOutcome PARK = new SyncOutcome(true, 0L);

        /** Completed inline, returning {@code value} to the guest. */
        public static SyncOutcome of(long value) {
            return new SyncOutcome(false, value);
        }
    }

    /** Per-instance ceiling on live sync objects; generous, but a runaway loop still dies. */
    public static final int MAX_OBJECTS = 65536;

    /** Ceiling on composite nesting depth, so tree walks cannot exhaust the Java stack. */
    public static final int MAX_COMPOSITE_DEPTH = 256;

    private sealed interface Obj {}

    private static final class Signal implements Obj {
        final List<Waiter> waiters = new ArrayList<>(); // in park order
    }

    private static final class Barrier implements Obj {
        final int n;
        int arrivals;
        final List<Waiter> waiters = new ArrayList<>(); // in park order

        Barrier(int n) {
            this.n = n;
        }
    }

    private static final class Composite implements Obj {
        final boolean all;
        final int left;
        final int right;
        final int depth;
        final Set<Integer> leaves; // deterministic iteration order

        Composite(boolean all, int left, int right, int depth, Set<Integer> leaves) {
            this.all = all;
            this.left = left;
            this.right = right;
            this.depth = depth;
            this.leaves = leaves;
        }
    }

    private static final class Channel implements Obj {
        final int capacity;
        final Deque<byte[]> queue = new ArrayDeque<>();
        final List<ChannelWaiter> senders = new ArrayList<>();   // in park order
        final List<ChannelWaiter> receivers = new ArrayList<>(); // in park order

        Channel(int capacity) {
            this.capacity = capacity;
        }
    }

    private static final class Waiter {
        final int taskId;
        final int rootId;
        final Set<Integer> latched = new HashSet<>();
        boolean released;

        Waiter(int taskId, int rootId) {
            this.taskId = taskId;
            this.rootId = rootId;
        }
    }

    private static final class ChannelWaiter {
        final int taskId;
        final byte[] payload; // senders only
        final boolean peek;   // receivers only

        ChannelWaiter(int taskId, byte[] payload, boolean peek) {
            this.taskId = taskId;
            this.payload = payload;
            this.peek = peek;
        }
    }

    /** A message reserved for one task by a {@code *_len} call; {@code detached} = already dequeued. */
    private static final class Pending {
        final byte[] bytes;
        final boolean detached;

        Pending(byte[] bytes, boolean detached) {
            this.bytes = bytes;
            this.detached = detached;
        }

        byte[] bytes() {
            return bytes;
        }

        boolean detached() {
            return detached;
        }
    }

    private final Waker waker;
    private final SplitMix64 schedulingRandom;
    private final MemoryBudget budget;

    private final Map<Integer, Obj> objects = new LinkedHashMap<>();
    private final Map<Integer, Pending> reserved = new HashMap<>();

    private int nextId = 1; // 0 is never a valid id

    /**
     * @param waker            releases parked tasks back into the scheduler
     * @param schedulingSeed   seeds the {@code notify_one(Random)} stream; must be derived
     *                         from the instance seed and never shared with guest randomness
     * @param budget           the instance memory budget channel buffers charge against — the same
     *                         one the guest heaps use, because the cap is per instance
     */
    public SyncTable(Waker waker, long schedulingSeed, MemoryBudget budget) {
        this.waker = waker;
        this.schedulingRandom = new SplitMix64(schedulingSeed);
        this.budget = budget;
    }

    // --- construction ---

    /** {@code signal_new}. */
    public int newSignal() {
        return put(new Signal());
    }

    /** {@code barrier_new}. */
    public int newBarrier(int n) {
        if (n < 1) {
            throw new GuestAbort("barrier_new(" + n + "): the arrival count must be at least 1");
        }
        return put(new Barrier(n));
    }

    /** {@code wait_all} / {@code wait_any}: a binary composite over two waitables. */
    public int newComposite(boolean all, int left, int right) {
        String op = all ? "wait_all" : "wait_any";
        int depth = 1 + Math.max(depthOf(left, op), depthOf(right, op));
        if (depth > MAX_COMPOSITE_DEPTH) {
            throw new GuestAbort(op + ": composite nesting deeper than " + MAX_COMPOSITE_DEPTH);
        }
        Set<Integer> leaves = new LinkedHashSet<>(leavesOf(left, op));
        leaves.addAll(leavesOf(right, op));
        return put(new Composite(all, left, right, depth, leaves));
    }

    /** {@code channel_new}. */
    public int newChannel(int capacity) {
        if (capacity < 1) {
            // A zero-capacity channel could never admit a parked sender: deadlock, not rendezvous.
            throw new GuestAbort("channel_new(" + capacity + "): the capacity must be at least 1");
        }
        return put(new Channel(capacity));
    }

    private int put(Obj o) {
        if (objects.size() >= MAX_OBJECTS) {
            throw new GuestAbort("sync-object cap of " + MAX_OBJECTS + " per animation exceeded");
        }
        int id = nextId++;
        objects.put(id, o);
        return id;
    }

    // --- waiting ---

    /**
     * {@code wait}: registers the calling task on every leaf of {@code rootId} and counts
     * its arrival at every barrier leaf. The caller always parks; if the wait was satisfied
     * immediately (a barrier completed on this very arrival) the {@link Waker} has already
     * been told, so the task resumes in the same tick.
     */
    public void park(int taskId, int rootId) {
        Obj root = require(rootId);
        if (root instanceof Channel) {
            throw new GuestAbort("wait(" + rootId + "): a channel is not a waitable"
                    + " (use channel_send / channel_recv_len)");
        }
        Set<Integer> leaves = leavesOf(rootId, "wait");
        Waiter w = new Waiter(taskId, rootId);
        for (int leaf : leaves) {
            waitersOf(leaf).add(w);
        }
        // Two passes on purpose: every arrival is counted before any completion runs, so an
        // or-cancelled decrement can never take back an arrival that has not happened yet.
        List<Integer> barriers = new ArrayList<>();
        for (int leaf : leaves) {
            if (objects.get(leaf) instanceof Barrier b) {
                b.arrivals++;
                barriers.add(leaf);
            }
        }
        for (int leaf : barriers) {
            Barrier b = (Barrier) objects.get(leaf);
            if (b.arrivals >= b.n) {
                completeBarrier(leaf, b);
            }
        }
    }

    /** {@code signal_notify}: mode 0 all, 1 oldest, 2 newest, 3 random (scheduling stream). */
    public void notifySignal(int id, int mode) {
        if (mode < 0 || mode > 3) {
            throw new GuestAbort("signal_notify(" + id + ", " + mode + "): mode out of range 0..3");
        }
        Signal s = requireSignal(id);
        if (mode == 0) {
            for (Waiter w : List.copyOf(s.waiters)) {
                if (!w.released) {
                    fire(w, id);
                }
            }
            return;
        }
        // A one-waiter notify has to pick a waiter it can actually affect. A composite waiter that
        // has already latched this leaf would swallow the notify — fire() would re-add a latch it
        // already holds and release nobody — and a task parked on the bare signal behind it would
        // starve. So choose among the waiters this signal has not already fired for.
        List<Waiter> eligible = new ArrayList<>();
        for (Waiter w : s.waiters) {
            if (!w.released && !w.latched.contains(id)) {
                eligible.add(w);
            }
        }
        if (eligible.isEmpty()) {
            return; // edge-triggered: nobody this notify could move, so it is defined as nothing
        }
        switch (mode) {
            case 1 -> fire(eligible.get(0), id);                       // oldest by park order
            case 2 -> fire(eligible.get(eligible.size() - 1), id);      // newest by park order
            default -> fire(eligible.get(schedulingRandom.nextBounded(eligible.size())), id);
        }
    }

    private void completeBarrier(int id, Barrier b) {
        b.arrivals = 0; // reusable, like Rust's Barrier
        for (Waiter w : List.copyOf(b.waiters)) {
            if (!w.released) {
                fire(w, id);
            }
        }
    }

    private void fire(Waiter w, int leafId) {
        w.latched.add(leafId);
        if (satisfied(w, w.rootId)) {
            unregister(w);
            waker.release(w.taskId, 0L);
        }
    }

    private boolean satisfied(Waiter w, int nodeId) {
        if (objects.get(nodeId) instanceof Composite c) {
            return c.all
                    ? satisfied(w, c.left) && satisfied(w, c.right)
                    : satisfied(w, c.left) || satisfied(w, c.right);
        }
        return w.latched.contains(nodeId);
    }

    /** Removes a waiter from every leaf it is registered on, giving back unearned arrivals. */
    private void unregister(Waiter w) {
        w.released = true;
        for (int leaf : leavesOf(w.rootId, "wait")) {
            if (!waitersOf(leaf).remove(w)) {
                continue;
            }
            if (objects.get(leaf) instanceof Barrier b && !w.latched.contains(leaf)) {
                b.arrivals--; // or-cancelled: this arm won, so the arrival never counted
            }
        }
    }

    // --- channels ---

    /** {@code channel_send}: parks when the queue is full; admission is in park order. */
    public SyncOutcome send(int taskId, int id, byte[] payload) {
        Channel c = requireChannel(id, "channel_send");
        hold(payload);
        if (c.queue.size() < c.capacity && c.senders.isEmpty()) {
            c.queue.addLast(payload);
            service(c);
            return SyncOutcome.of(0L);
        }
        // Either the queue is full or earlier senders are still parked (which itself means the
        // queue was full), so nothing can admit this payload before a dequeue happens.
        c.senders.add(new ChannelWaiter(taskId, payload, false));
        return SyncOutcome.PARK;
    }

    /**
     * {@code channel_recv_len} / {@code channel_peek_len}: parks when empty, otherwise
     * reserves the front message for this task and returns its byte length.
     */
    public SyncOutcome receiveLength(int taskId, int id, boolean peek) {
        Channel c = requireChannel(id, peek ? "channel_peek_len" : "channel_recv_len");
        byte[] front = c.queue.peekFirst();
        if (front == null) {
            c.receivers.add(new ChannelWaiter(taskId, null, peek));
            return SyncOutcome.PARK;
        }
        reserved.put(taskId, new Pending(front, false));
        return SyncOutcome.of(front.length);
    }

    /** {@code channel_try_len}: {@code -1} when empty, else the reserved front length. */
    public int tryLength(int taskId, int id) {
        Channel c = requireChannel(id, "channel_try_len");
        byte[] front = c.queue.peekFirst();
        if (front == null) {
            return -1;
        }
        reserved.put(taskId, new Pending(front, false));
        return front.length;
    }

    /** {@code channel_recv}: the bytes reserved by the paired length call, popped. */
    public byte[] receive(int taskId, int id) {
        Channel c = requireChannel(id, "channel_recv");
        Pending p = take(taskId, id, "channel_recv", "channel_recv_len");
        if (p.detached()) {
            drop(p.bytes()); // dequeued when this task was released
            return p.bytes();
        }
        byte[] front = c.queue.pollFirst();
        if (front == null) {
            throw new GuestAbort("channel_recv(" + id + "): the reserved message is gone");
        }
        drop(front);
        service(c);
        return front;
    }

    /** {@code channel_peek}: the bytes reserved by the paired length call, left in place. */
    public byte[] peek(int taskId, int id) {
        requireChannel(id, "channel_peek");
        Pending p = take(taskId, id, "channel_peek", "channel_peek_len");
        if (p.detached()) {
            drop(p.bytes());
        }
        return p.bytes();
    }

    /** {@code channel_clear}: drops every queued message and admits parked senders. */
    public void clear(int id) {
        Channel c = requireChannel(id, "channel_clear");
        for (byte[] m : c.queue) {
            drop(m);
        }
        c.queue.clear();
        service(c);
    }

    private Pending take(int taskId, int id, String call, String lengthCall) {
        Pending p = reserved.remove(taskId);
        if (p == null) {
            throw new GuestAbort(call + "(" + id + "): no message reserved; " + lengthCall
                    + " must immediately precede it");
        }
        return p;
    }

    /**
     * Moves a channel forward until nothing more can happen: parked senders fill free slots,
     * then parked receivers take (or peek at) queued messages, both in park order. A receiver
     * released by {@code recv} has its message dequeued here, which is what makes two
     * releases from two sends read two different messages.
     */
    private void service(Channel c) {
        boolean progress = true;
        while (progress) {
            progress = false;
            while (c.queue.size() < c.capacity && !c.senders.isEmpty()) {
                ChannelWaiter s = c.senders.remove(0);
                c.queue.addLast(s.payload); // already counted against the buffer cap
                waker.release(s.taskId, 0L);
                progress = true;
            }
            while (!c.queue.isEmpty() && !c.receivers.isEmpty()) {
                ChannelWaiter r = c.receivers.remove(0);
                byte[] message = r.peek ? c.queue.peekFirst() : c.queue.pollFirst();
                reserved.put(r.taskId, new Pending(message, !r.peek));
                waker.release(r.taskId, message.length);
                progress = true;
            }
        }
    }

    private void hold(byte[] payload) {
        budget.reserve(payload.length, "channel buffers");
    }

    private void drop(byte[] payload) {
        budget.release(payload.length);
    }

    // --- task lifecycle ---

    /**
     * Forgets everything a task owns: it stops being a waiter everywhere (giving back any
     * barrier arrival it contributed) and its reserved message is dropped. Called when a task
     * is killed while parked, so no dead task can hold a barrier or a channel slot.
     */
    public void removeTask(int taskId) {
        Set<Waiter> mine = new LinkedHashSet<>();
        for (Obj o : objects.values()) {
            List<Waiter> waiters = switch (o) {
                case Signal s -> s.waiters;
                case Barrier b -> b.waiters;
                case Composite ignored -> List.of();
                case Channel ignored -> List.of();
            };
            for (Waiter w : waiters) {
                if (w.taskId == taskId) {
                    mine.add(w);
                }
            }
        }
        for (Waiter w : mine) {
            unregister(w);
        }
        for (Obj o : objects.values()) {
            if (o instanceof Channel c) {
                for (ChannelWaiter s : List.copyOf(c.senders)) {
                    if (s.taskId == taskId) {
                        c.senders.remove(s);
                        drop(s.payload);
                    }
                }
                c.receivers.removeIf(r -> r.taskId == taskId);
                service(c);
            }
        }
        Pending p = reserved.remove(taskId);
        if (p != null && p.detached()) {
            drop(p.bytes());
        }
    }

    // --- validation ---

    private Obj require(int id) {
        Obj o = objects.get(id);
        if (o == null) {
            throw new GuestAbort("unknown sync object id " + id);
        }
        return o;
    }

    private Signal requireSignal(int id) {
        Obj o = require(id);
        if (o instanceof Signal s) {
            return s;
        }
        throw new GuestAbort("signal_notify(" + id + "): that id is a " + kindOf(o) + ", not a signal");
    }

    private Channel requireChannel(int id, String call) {
        Obj o = require(id);
        if (o instanceof Channel c) {
            return c;
        }
        throw new GuestAbort(call + "(" + id + "): that id is a " + kindOf(o) + ", not a channel");
    }

    private Set<Integer> leavesOf(int id, String call) {
        Obj o = require(id);
        return switch (o) {
            case Composite c -> c.leaves;
            case Signal ignored -> Set.of(id);
            case Barrier ignored -> Set.of(id);
            case Channel ignored -> throw new GuestAbort(call + "(" + id
                    + "): a channel is not a waitable");
        };
    }

    private int depthOf(int id, String call) {
        Obj o = require(id);
        if (o instanceof Composite c) {
            return c.depth;
        }
        if (o instanceof Channel) {
            throw new GuestAbort(call + "(" + id + "): a channel is not a waitable");
        }
        return 0;
    }

    private List<Waiter> waitersOf(int leafId) {
        return switch (objects.get(leafId)) {
            case Signal s -> s.waiters;
            case Barrier b -> b.waiters;
            default -> throw new IllegalStateException("leaf " + leafId + " is not a signal or barrier");
        };
    }

    private static String kindOf(Obj o) {
        return switch (o) {
            case Signal ignored -> "signal";
            case Barrier ignored -> "barrier";
            case Composite c -> c.all ? "wait_all composite" : "wait_any composite";
            case Channel ignored -> "channel";
        };
    }
}
