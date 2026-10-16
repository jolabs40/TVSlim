package net.jolabs40.tvslim.aide;

import android.os.Build;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The remote control: one order per line on standard input, one answer per line on standard output, fields
 * separated by tabs. Standard input belongs to the ADB session that started the helper, so nothing else on the TV
 * can send it keys, and nothing listens on the network.
 *
 * <p>Orders ({@code id} is chosen by the phone, {@code t} is the phone's clock in ms):
 * <ul>
 *   <li>{@code P id t}: ping, answered and nothing else;</li>
 *   <li>{@code D id t code}, {@code U id t code}, {@code C id t code}: press, release, canceled release;</li>
 *   <li>{@code T id t code}: press and release;</li>
 *   <li>{@code X id t text}: text, UTF-8 in base64;</li>
 *   <li>{@code W id t}, {@code S id t}: wake up, go to sleep (only if the screen is on);</li>
 *   <li>{@code A id t authority method arg extras}: call a content provider ({@link ProviderCalls}); {@code arg}
 *   in base64, {@code extras} a JSON object in base64, both possibly empty;</li>
 *   <li>{@code Q}: release everything and stop.</li>
 * </ul>
 *
 * <p>Answers: {@code R sdk api repeatTimeout repeatDelay screen clipboard} once ready, {@code K id status} for each
 * order ({@code ok}, {@code late}, {@code refused}, {@code ignored}), {@code L code} for a key released by the
 * helper, {@code V screen} when the screen turns on or off, {@code Y id status payload} when a call returns
 * ({@code ok} and the provider's Bundle as JSON, or {@code error} and the exception, both in base64), {@code E reason},
 * and {@code B reason} when stopping.
 *
 * <p>Orders are never retried by the phone, and an order that arrives late is dropped: a lost key is better than a
 * key pressed twice, or pressed seconds after the person gave up on it.
 */
final class RemoteControl {

    private static final long SCREEN_POLL_MS = 1_000;

    private static final String END = new String("end");

    private final PrintStream out;
    private final Injection injection;
    private final KeyHolder keys;
    private final ProviderCalls calls = new ProviderCalls();
    private int screen;

    private RemoteControl(PrintStream out, Injection injection, long now) {
        this.out = out;
        this.injection = injection;
        this.keys = new KeyHolder(injection, Injection.repeatTimeout(), Injection.repeatDelay(), now);
    }

    static void run(PrintStream out) {
        Injection injection;
        try {
            injection = Injection.create();
        } catch (Throwable e) {
            send(out, "E\tno injection: " + e);
            send(out, "B\terror");
            System.exit(1);
            return;
        }
        RemoteControl control = new RemoteControl(out, injection, now());
        // Also on SIGTERM. A SIGKILL cannot be caught; the phone then cancels the held keys through the next helper.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            control.keys.releaseAll(now());
            control.calls.close();
        }));

        String reason;
        try {
            reason = control.serve();
        } catch (Throwable e) {
            Log.e(Injection.TAG, "remote control stopped", e);
            send(out, "E\t" + flat(String.valueOf(e)));
            reason = "error";
        }
        control.keys.releaseAll(now());
        control.calls.close();
        Log.i(Injection.TAG, "remote control stopped: " + reason);
        send(out, "B\t" + reason);
        System.exit(0);
    }

    private String serve() throws InterruptedException {
        screen = injection.screenOn();
        send(out, "R\t" + Build.VERSION.SDK_INT + "\t" + injection.api() + "\t" + Injection.repeatTimeout() + "\t"
                + Injection.repeatDelay() + "\t" + screen + "\t" + (injection.clipboardAvailable() ? 1 : 0));
        deleteOwnApk();

        BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) lines.add(line);
            } catch (IOException e) {
                Log.i(Injection.TAG, "input closed: " + e);
            }
            lines.add(END);
        }, "tvslim-input");
        reader.setDaemon(true);
        reader.start();

        long nextScreenPoll = now() + SCREEN_POLL_MS;
        while (true) {
            long now = now();
            if (!keys.tick(now)) return "silence";
            if (now >= nextScreenPoll) {
                pollScreen();
                nextScreenPoll = now + SCREEN_POLL_MS;
            }
            long wait = Math.max(0, Math.min(keys.nextDeadline(), nextScreenPoll) - now);
            String line = lines.poll(wait, TimeUnit.MILLISECONDS);
            if (line == null) continue;
            if (line == END) return "eof";
            keys.heard(now());
            if (!handle(line)) return "quit";
            if (out.checkError()) return "output closed";
        }
    }

    /** Returns false on {@code Q}. */
    private boolean handle(String line) {
        String[] fields = line.split("\t", -1);
        String order = fields[0];
        if (order.equals("Q")) return false;
        if (fields.length < 3) {
            send(out, "E\tunreadable: " + flat(line));
            return true;
        }
        String id = fields[1];
        try {
            long sent = Long.parseLong(fields[2]);
            long now = now();
            boolean late = keys.late(sent, now);
            switch (order) {
                case "P":
                    answer(id, KeyHolder.OK);
                    break;
                case "D":
                    answer(id, late ? KeyHolder.LATE : keys.press(code(fields), now));
                    break;
                case "U":
                    answer(id, keys.release(code(fields), now));
                    break;
                case "C":
                    answer(id, keys.cancel(code(fields), now));
                    break;
                case "T":
                    answer(id, late ? KeyHolder.LATE : keys.tap(code(fields), now));
                    break;
                case "W":
                    answer(id, late ? KeyHolder.LATE : keys.tap(KeyEvent.KEYCODE_WAKEUP, now));
                    break;
                case "S":
                    // KEYCODE_SLEEP rather than POWER: POWER toggles, and on a sleeping screen it would wake it up.
                    answer(id, late ? KeyHolder.LATE : injection.screenOn() == 0 ? KeyHolder.IGNORED : keys.tap(KeyEvent.KEYCODE_SLEEP, now));
                    break;
                case "A":
                    if (late) {
                        answer(id, KeyHolder.LATE);
                        break;
                    }
                    String arg = fields.length > 5 && !fields[5].isEmpty() ? decode(fields[5]) : null;
                    android.os.Bundle extras = fields.length > 6 && !fields[6].isEmpty() ? ProviderCalls.bundle(decode(fields[6])) : null;
                    calls.submit(id, fields[3], fields[4], arg, extras, (callId, succeeded, payload) ->
                            send(out, "Y\t" + callId + "\t" + (succeeded ? "ok" : "error") + "\t" + encode(payload)));
                    answer(id, KeyHolder.OK);
                    break;
                case "X":
                    answer(id, injection.text(decode(fields[3])) ? KeyHolder.OK : KeyHolder.REFUSED);
                    break;
                default:
                    answer(id, KeyHolder.REFUSED);
            }
        } catch (RuntimeException | org.json.JSONException e) {
            send(out, "E\tunreadable: " + flat(line));
            answer(id, KeyHolder.REFUSED);
        }
        return true;
    }

    private static String decode(String base64) {
        return new String(Base64.decode(base64, Base64.DEFAULT), StandardCharsets.UTF_8);
    }

    private static String encode(String text) {
        return Base64.encodeToString(text.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    private static int code(String[] fields) {
        return Integer.parseInt(fields[3]);
    }

    private void answer(String id, int status) {
        String word;
        switch (status) {
            case KeyHolder.OK:
                word = "ok";
                break;
            case KeyHolder.IGNORED:
                word = "ignored";
                break;
            case KeyHolder.LATE:
                word = "late";
                break;
            default:
                word = "refused";
        }
        send(out, "K\t" + id + "\t" + word);
    }

    private void pollScreen() {
        int current = injection.screenOn();
        if (current != screen) {
            screen = current;
            send(out, "V\t" + current);
        }
    }

    /** Like scrcpy's server: once loaded, the APK is no longer needed, and nothing is left behind if the phone vanishes. */
    private static void deleteOwnApk() {
        String path = System.getenv("CLASSPATH");
        if (path != null && path.startsWith("/data/local/tmp/") && path.endsWith(".apk") && !path.contains(":")) {
            if (!new File(path).delete()) Log.w(Injection.TAG, "could not delete " + path);
        }
    }

    private static void send(PrintStream out, String line) {
        synchronized (out) {
            out.println(line);
            out.flush();
        }
    }

    private static String flat(String text) {
        return text.replaceAll("[\\t\\r\\n]+", " ");
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }
}
