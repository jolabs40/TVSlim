package net.jolabs40.tvslim.aide;

import android.content.ClipData;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import java.lang.reflect.Method;

/**
 * Hidden APIs reached by reflection, the way scrcpy does it: the shell user may inject input events, and the
 * InputManager singleton moved to InputManagerGlobal in Android 14.
 */
final class Injection implements KeyHolder.Output {

    static final String TAG = "TVSlim/Aide";

    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC: returns once the event is queued. */
    private static final int MODE_ASYNC = 0;

    /** Owns uid 2000, so the clipboard accepts it as the caller. */
    private static final String SHELL_PACKAGE = "com.android.shell";

    private final Object manager;
    private final Method inject;
    private final String api;

    private Object clipboard;
    private Method setPrimaryClip;
    private Object power;
    private Method isInteractive;

    private Injection(Object manager, Method inject, String api) {
        this.manager = manager;
        this.inject = inject;
        this.api = api;
    }

    static Injection create() throws ReflectiveOperationException {
        Class<?> type;
        String api;
        try {
            type = Class.forName("android.hardware.input.InputManagerGlobal");
            api = "global";
        } catch (ClassNotFoundException e) {
            type = Class.forName("android.hardware.input.InputManager");
            api = "legacy";
        }
        Object manager = type.getMethod("getInstance").invoke(null);
        Method inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
        return new Injection(manager, inject, api);
    }

    String api() {
        return api;
    }

    static long repeatTimeout() {
        return Build.VERSION.SDK_INT >= 27 ? ViewConfiguration.getKeyRepeatTimeout() : ViewConfiguration.getLongPressTimeout();
    }

    static long repeatDelay() {
        return Build.VERSION.SDK_INT >= 27 ? ViewConfiguration.getKeyRepeatDelay() : 50;
    }

    /** Same device and source as {@code input keyevent}, which every app on these TVs accepts. */
    @Override
    public boolean key(int code, boolean down, int repeat, boolean longPress, boolean canceled, long downTime, long now) {
        int flags = (longPress ? KeyEvent.FLAG_LONG_PRESS : 0) | (canceled ? KeyEvent.FLAG_CANCELED : 0);
        KeyEvent event = new KeyEvent(downTime, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, repeat, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, flags, InputDevice.SOURCE_KEYBOARD);
        boolean injected = inject(event);
        Log.d(TAG, (down ? "down " : "up ") + code + " repeat=" + repeat + (canceled ? " canceled" : "") + (injected ? "" : " REFUSED"));
        return injected;
    }

    @Override
    public void released(int code) {
        Log.i(TAG, "released by the helper: " + code);
    }

    private boolean inject(InputEvent event) {
        try {
            return (Boolean) inject.invoke(manager, event, MODE_ASYNC);
        } catch (ReflectiveOperationException e) {
            Log.w(TAG, "injection failed", e);
            return false;
        }
    }

    /**
     * Types the text when the virtual keyboard map knows every character, otherwise pastes the whole of it. Pasting
     * piece by piece would race: the next clip could be set before the app has pasted the previous one.
     */
    boolean text(String text) {
        KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray());
        if (events == null) return paste(text);
        for (KeyEvent event : events) {
            if (!inject(event)) return false;
        }
        return true;
    }

    private boolean paste(String text) {
        if (!setClipboard(text)) return false;
        long now = SystemClock.uptimeMillis();
        return key(KeyEvent.KEYCODE_PASTE, true, 0, false, false, now, now)
                && key(KeyEvent.KEYCODE_PASTE, false, 0, false, false, now, now);
    }

    boolean clipboardAvailable() {
        try {
            resolveClipboard();
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "clipboard unavailable", e);
            return false;
        }
    }

    private void resolveClipboard() throws ReflectiveOperationException {
        if (setPrimaryClip != null) return;
        Object service = asInterface("android.content.IClipboard", service("clipboard"));
        for (Method method : service.getClass().getMethods()) {
            if (method.getName().equals("setPrimaryClip")) {
                clipboard = service;
                setPrimaryClip = method;
                return;
            }
        }
        throw new NoSuchMethodException("setPrimaryClip");
    }

    /**
     * Its signature changes with the version (calling package, then attribution tag, user, device): the first String
     * is the package, any other is null, ints are user 0 and the default device.
     */
    private boolean setClipboard(String text) {
        try {
            resolveClipboard();
            Class<?>[] types = setPrimaryClip.getParameterTypes();
            Object[] arguments = new Object[types.length];
            arguments[0] = ClipData.newPlainText(null, text);
            boolean packageGiven = false;
            for (int i = 1; i < types.length; i++) {
                if (types[i] == String.class) {
                    arguments[i] = packageGiven ? null : SHELL_PACKAGE;
                    packageGiven = true;
                } else if (types[i] == int.class) {
                    arguments[i] = 0;
                } else if (types[i] == boolean.class) {
                    arguments[i] = true;
                }
            }
            setPrimaryClip.invoke(clipboard, arguments);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "clipboard refused", e);
            return false;
        }
    }

    /** 1 when the screen is on, 0 when asleep, -1 if it cannot be read. */
    int screenOn() {
        try {
            if (isInteractive == null) {
                power = asInterface("android.os.IPowerManager", service("power"));
                isInteractive = power.getClass().getMethod("isInteractive");
            }
            return (Boolean) isInteractive.invoke(power) ? 1 : 0;
        } catch (Throwable e) {
            return -1;
        }
    }

    private static IBinder service(String name) throws ReflectiveOperationException {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new ClassNotFoundException("service " + name);
        return binder;
    }

    private static Object asInterface(String type, IBinder binder) throws ReflectiveOperationException {
        return Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
}
