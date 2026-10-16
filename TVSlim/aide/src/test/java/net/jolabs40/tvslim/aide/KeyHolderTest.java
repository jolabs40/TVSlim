package net.jolabs40.tvslim.aide;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class KeyHolderTest {

    private static final int RIGHT = 22;
    private static final int OK = 23;
    private static final long TIMEOUT = 400;
    private static final long DELAY = 50;

    /** Records what would be injected, as "down 22 r0", "up 22 r3 canceled"... */
    private static final class Recorder implements KeyHolder.Output {
        final List<String> events = new ArrayList<>();
        final List<Integer> released = new ArrayList<>();
        boolean accept = true;

        @Override
        public boolean key(int code, boolean down, int repeat, boolean longPress, boolean canceled, long downTime, long now) {
            events.add((down ? "down " : "up ") + code + " r" + repeat + (longPress ? " long" : "") + (canceled ? " canceled" : ""));
            return accept;
        }

        @Override
        public void released(int code) {
            released.add(code);
        }

        String last() {
            return events.get(events.size() - 1);
        }
    }

    private final Recorder output = new Recorder();
    private final KeyHolder keys = new KeyHolder(output, TIMEOUT, DELAY, 0);

    /** Runs the helper's loop up to [until], with the phone pinging every 100 ms while it holds a key. */
    private void run(long from, long until, boolean phoneTalks) {
        long now = from;
        while (now <= until) {
            if (phoneTalks && now % 100 == 0) keys.heard(now);
            keys.tick(now);
            now += 10;
        }
    }

    @Test
    public void heldKeyRepeatsAtTheTvCadenceThenReleases() {
        assertEquals(KeyHolder.OK, keys.press(RIGHT, 0));
        run(0, 600, true);
        // First repeat after the timeout, flagged as a long press, then one every 50 ms.
        assertEquals("down 22 r1 long", output.events.get(1));
        assertEquals("down 22 r2", output.events.get(2));
        assertEquals(1 + 5, output.events.size());

        assertEquals(KeyHolder.OK, keys.release(RIGHT, 610));
        assertEquals("up 22 r5", output.last());
        int count = output.events.size();
        run(620, 2_000, true);
        assertEquals("nothing repeats after the release", count, output.events.size());
    }

    @Test
    public void silentPhoneWhileHoldingReleasesWithCancel() {
        keys.heard(0);
        keys.press(RIGHT, 0);
        run(0, 300, true);
        // The phone goes quiet at 300 ms: Wi-Fi cut.
        run(310, 2_000, false);
        // Released 500 ms after the last ping at 300: the repeats at 400, 450... 750, then the release at 800.
        assertEquals("up 22 r8 canceled", output.last());
        assertEquals(1 + 8 + 1, output.events.size());
        assertEquals(List.of(RIGHT), output.released);
        assertFalse(keys.holding());
    }

    @Test
    public void releaseAllCancelsEveryHeldKey() {
        keys.press(RIGHT, 0);
        keys.press(OK, 10);
        keys.releaseAll(20);
        assertEquals(List.of("down 22 r0", "down 23 r0", "up 22 r0 canceled", "up 23 r0 canceled"), output.events);
        assertFalse(keys.holding());
    }

    @Test
    public void onlyTheLastKeyPressedRepeats() {
        keys.press(RIGHT, 0);
        keys.press(OK, 100);
        run(100, 600, true);
        assertTrue(output.events.stream().noneMatch(e -> e.startsWith("down 22 r1")));
        assertTrue(output.events.contains("down 23 r1 long"));
    }

    @Test
    public void keyHeldTooLongIsReleased() {
        keys.press(RIGHT, 0);
        run(0, KeyHolder.MAX_HOLD_MS + 100, true);
        assertTrue(output.last().startsWith("up 22") && output.last().endsWith("canceled"));
        assertEquals(List.of(RIGHT), output.released);
    }

    @Test
    public void strayReleaseIsIgnoredButCancelIsAlwaysSent() {
        assertEquals(KeyHolder.IGNORED, keys.release(RIGHT, 0));
        assertTrue(output.events.isEmpty());
        // A previous helper may have died holding the key: its cancel goes out regardless.
        assertEquals(KeyHolder.OK, keys.cancel(RIGHT, 0));
        assertEquals("up 22 r0 canceled", output.last());
    }

    @Test
    public void tapPressesAndReleasesInOneOrder() {
        assertEquals(KeyHolder.OK, keys.tap(OK, 0));
        assertEquals(List.of("down 23 r0", "up 23 r0"), output.events);
    }

    @Test
    public void refusedPressIsNotHeld() {
        output.accept = false;
        assertEquals(KeyHolder.REFUSED, keys.press(RIGHT, 0));
        output.accept = true;
        run(0, 1_000, true);
        assertEquals(1, output.events.size());
        assertFalse(keys.holding());
    }

    @Test
    public void lateOrdersAreThoseSlowerThanTheBestRecentTrip() {
        // Phone clock 10 000 ms ahead of the TV's: only the difference between trips matters.
        assertFalse(keys.late(10_000, 20));
        assertFalse(keys.late(11_000, 1_015));
        assertFalse(keys.late(12_000, 2_400));
        assertTrue(keys.late(13_000, 3_600));
    }

    @Test
    public void silenceStopsTheHelper() {
        assertTrue(keys.tick(KeyHolder.IDLE_EXIT_MS - 1));
        assertFalse(keys.tick(KeyHolder.IDLE_EXIT_MS));
    }

    @Test
    public void stalledLoopDoesNotBurstRepeats() {
        keys.press(RIGHT, 0);
        keys.heard(1_000);
        keys.tick(1_000);
        keys.tick(1_001);
        assertEquals(2, output.events.size());
        assertEquals(1_050, keys.nextDeadline());
    }
}
