package net.jolabs40.tvslim.aide;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keys the phone holds down, their repeat, and everything that releases them. No Android types, so it runs in JVM
 * tests; {@link Output} does the injecting.
 *
 * The repeat is produced here, at the TV's own cadence, rather than by the phone: Wi-Fi jitter would make it uneven,
 * and a phone that goes silent must not leave anything running. An app that misses a release may repeat the key on
 * its own (Gboard did for hours), so every path out of a held key ends with a release.
 *
 * Releases decided here (silence, end of input, a key held too long) are canceled: a view does not click and an
 * activity does not go back on a canceled release.
 */
final class KeyHolder {

    interface Output {
        boolean key(int code, boolean down, int repeat, boolean longPress, boolean canceled, long downTime, long now);

        /** A key the helper released by itself, so the phone stops showing it as held. */
        void released(int code);
    }

    static final int OK = 0;
    static final int REFUSED = 1;
    static final int IGNORED = 2;
    static final int LATE = 3;

    /** While a key is held the phone pings every 100 ms; this much silence means it is gone. */
    static final long HOLD_SILENCE_MS = 500;

    /** The phone pings every second even when idle. */
    static final long IDLE_EXIT_MS = 5_000;

    /** A held key the phone never released, through a bug or a lost message, stops here. */
    static final long MAX_HOLD_MS = 30_000;

    /** An order delayed this much beyond the best recent trip is dropped: a late OK is worse than a lost one. */
    static final long LATE_MS = 500;

    /** Recent trips kept to find the best one; pings arrive every second. */
    static final int TRIP_WINDOW = 32;

    private static final class Held {
        final long downTime;
        int repeat;
        long nextRepeat;

        Held(long downTime, long nextRepeat) {
            this.downTime = downTime;
            this.nextRepeat = nextRepeat;
        }
    }

    private final Output output;
    private final long repeatTimeout;
    private final long repeatDelay;
    private final Map<Integer, Held> held = new LinkedHashMap<>();
    private final ArrayDeque<Long> trips = new ArrayDeque<>();

    /** Only the last key pressed repeats, as with a physical remote. */
    private int repeating = -1;
    private long lastHeard;

    KeyHolder(Output output, long repeatTimeout, long repeatDelay, long now) {
        this.output = output;
        this.repeatTimeout = repeatTimeout;
        this.repeatDelay = repeatDelay;
        this.lastHeard = now;
    }

    synchronized void heard(long now) {
        lastHeard = now;
    }

    /**
     * Whether an order stamped {@code sent} by the phone's clock arrives late. The two clocks differ by an unknown
     * offset, so "late" means slower than the fastest recent trip, which includes that offset.
     */
    synchronized boolean late(long sent, long now) {
        long trip = now - sent;
        trips.addLast(trip);
        if (trips.size() > TRIP_WINDOW) trips.removeFirst();
        long best = Long.MAX_VALUE;
        for (long t : trips) best = Math.min(best, t);
        return trip - best > LATE_MS;
    }

    synchronized int press(int code, long now) {
        if (held.containsKey(code)) return IGNORED;
        if (!output.key(code, true, 0, false, false, now, now)) return REFUSED;
        held.put(code, new Held(now, now + repeatTimeout));
        repeating = code;
        return OK;
    }

    /** The phone's release. A key not held is ignored: a stray release must not click. */
    synchronized int release(int code, long now) {
        Held key = held.remove(code);
        if (key == null) return IGNORED;
        if (repeating == code) repeating = -1;
        return output.key(code, false, key.repeat, false, false, key.downTime, now) ? OK : REFUSED;
    }

    /**
     * A canceled release. Sent even for a key not held here: a previous helper may have died holding it, and the
     * app that received its press is still waiting for the release.
     */
    synchronized int cancel(int code, long now) {
        Held key = held.remove(code);
        if (repeating == code) repeating = -1;
        long downTime = key != null ? key.downTime : now;
        int repeat = key != null ? key.repeat : 0;
        return output.key(code, false, repeat, false, true, downTime, now) ? OK : REFUSED;
    }

    /** Press and release in one order, so a tap can never lose its release on the way. */
    synchronized int tap(int code, long now) {
        if (held.containsKey(code)) return IGNORED;
        if (!output.key(code, true, 0, false, false, now, now)) return REFUSED;
        return output.key(code, false, 0, false, false, now, now) ? OK : REFUSED;
    }

    synchronized boolean holding() {
        return !held.isEmpty();
    }

    synchronized void releaseAll(long now) {
        for (Map.Entry<Integer, Held> entry : held.entrySet()) {
            Held key = entry.getValue();
            output.key(entry.getKey(), false, key.repeat, false, true, key.downTime, now);
            output.released(entry.getKey());
        }
        held.clear();
        repeating = -1;
    }

    /** Due repeats and watchdogs. Returns false once the phone has been silent long enough to stop. */
    synchronized boolean tick(long now) {
        if (!held.isEmpty() && now - lastHeard >= HOLD_SILENCE_MS) releaseAll(now);

        Iterator<Map.Entry<Integer, Held>> keys = held.entrySet().iterator();
        while (keys.hasNext()) {
            Map.Entry<Integer, Held> entry = keys.next();
            Held key = entry.getValue();
            if (now - key.downTime < MAX_HOLD_MS) continue;
            output.key(entry.getKey(), false, key.repeat, false, true, key.downTime, now);
            output.released(entry.getKey());
            keys.remove();
            if (repeating == entry.getKey()) repeating = -1;
        }

        Held key = held.get(repeating);
        if (key != null && now >= key.nextRepeat) {
            key.repeat++;
            output.key(repeating, true, key.repeat, key.repeat == 1, false, key.downTime, now);
            // After a stall, one repeat then the normal cadence, never a burst to catch up.
            key.nextRepeat += repeatDelay;
            if (key.nextRepeat <= now) key.nextRepeat = now + repeatDelay;
        }
        return now - lastHeard < IDLE_EXIT_MS;
    }

    /** When {@link #tick} has something to do next. */
    synchronized long nextDeadline() {
        long deadline = lastHeard + IDLE_EXIT_MS;
        if (!held.isEmpty()) deadline = Math.min(deadline, lastHeard + HOLD_SILENCE_MS);
        for (Held key : held.values()) deadline = Math.min(deadline, key.downTime + MAX_HOLD_MS);
        Held key = held.get(repeating);
        if (key != null) deadline = Math.min(deadline, key.nextRepeat);
        return deadline;
    }
}
