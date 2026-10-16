package net.jolabs40.tvslim.aide;

import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Calls a content provider as the shell user, the way {@code content call} does, from this running process: a new
 * app_process per call costs 0.7 to 1 s on these TVs, a call from here a fraction of a millisecond. The provider sees
 * uid 2000 either way.
 *
 * <p>Calls run on a thread of their own: an app started cold can take a second to answer, and held keys must keep
 * repeating and releasing meanwhile. A call is never retried, since the method it reaches may not be idempotent.
 */
final class ProviderCalls {

    interface Answer {
        void done(String id, boolean succeeded, String payload);
    }

    /** Owns uid 2000; the provider can only trust the uid, the package name is declared by the caller. */
    private static final String SHELL_PACKAGE = "com.android.shell";

    private final ExecutorService thread = Executors.newSingleThreadExecutor(task -> {
        Thread worker = new Thread(task, "tvslim-call");
        worker.setDaemon(true);
        return worker;
    });

    /** IContentProvider by authority, acquired once and released when the helper stops. */
    private final Map<String, Object> providers = new ConcurrentHashMap<>();
    private final IBinder token = new Binder();
    private Object activityManager;

    void submit(String id, String authority, String method, String arg, Bundle extras, Answer answer) {
        thread.execute(() -> {
            try {
                answer.done(id, true, json(call(authority, method, arg, extras)));
            } catch (Throwable e) {
                Throwable cause = e instanceof InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                // The app died or was updated: acquire it again next time, without replaying this call.
                if (cause instanceof DeadObjectException) providers.remove(authority);
                Log.w(Injection.TAG, "call " + authority + " " + method + " failed", cause);
                answer.done(id, false, String.valueOf(cause));
            }
        });
    }

    private Bundle call(String authority, String method, String arg, Bundle extras) throws Exception {
        Object provider = providers.get(authority);
        if (provider == null) {
            provider = acquire(authority);
            providers.put(authority, provider);
        }
        int sdk = Build.VERSION.SDK_INT;
        Object[] arguments;
        if (sdk >= 31) {
            arguments = new Object[]{Api31.shell(), authority, method, arg, extras};
        } else if (sdk == 30) {
            arguments = new Object[]{SHELL_PACKAGE, null, authority, method, arg, extras};
        } else if (sdk == 29) {
            arguments = new Object[]{SHELL_PACKAGE, authority, method, arg, extras};
        } else {
            arguments = new Object[]{SHELL_PACKAGE, method, arg, extras};
        }
        return (Bundle) callMethod(provider, arguments.length, sdk >= 31).invoke(provider, arguments);
    }

    private Object acquire(String authority) throws Exception {
        if (activityManager == null) {
            activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null);
        }
        Method get = method(activityManager.getClass(), "getContentProviderExternal");
        Object holder = get.getParameterCount() == 4
                ? get.invoke(activityManager, authority, 0, token, "tvslim")
                : get.invoke(activityManager, authority, 0, token);
        if (holder == null) throw new IllegalStateException("no provider " + authority);
        return holder.getClass().getField("provider").get(holder);
    }

    /** IContentProvider has several {@code call}; the one used depends on the version (4, 5 or 6 parameters). */
    private static Method callMethod(Object provider, int parameters, boolean attribution) throws NoSuchMethodException {
        for (Method candidate : provider.getClass().getMethods()) {
            if (!candidate.getName().equals("call") || candidate.getParameterCount() != parameters) continue;
            boolean firstIsAttribution = candidate.getParameterTypes()[0] != String.class;
            if (firstIsAttribution == attribution) return candidate;
        }
        throw new NoSuchMethodException("call/" + parameters);
    }

    private static Method method(Class<?> type, String name) throws NoSuchMethodException {
        for (Method candidate : type.getMethods()) {
            if (candidate.getName().equals(name)) return candidate;
        }
        throw new NoSuchMethodException(name);
    }

    /** Releases the providers; the system would also drop them when this process dies. */
    void close() {
        thread.shutdownNow();
        if (activityManager == null) return;
        for (String authority : providers.keySet()) {
            try {
                method(activityManager.getClass(), "removeContentProviderExternal").invoke(activityManager, authority, token);
            } catch (Throwable e) {
                Log.w(Injection.TAG, "provider " + authority + " not released", e);
            }
        }
        providers.clear();
    }

    /** A JSON object of strings, numbers and booleans, as the phone sends extras. */
    static Bundle bundle(String json) throws JSONException {
        JSONObject object = new JSONObject(json);
        Bundle bundle = new Bundle();
        for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Object value = object.get(key);
            if (value instanceof Boolean) bundle.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) bundle.putInt(key, (Integer) value);
            else if (value instanceof Long) bundle.putLong(key, (Long) value);
            else if (value instanceof Number) bundle.putDouble(key, ((Number) value).doubleValue());
            else if (value != JSONObject.NULL) bundle.putString(key, String.valueOf(value));
        }
        return bundle;
    }

    /** The provider's answer as JSON, whatever it holds, so the helper needs to know nothing about the app. */
    static String json(Bundle bundle) throws JSONException {
        return bundle == null ? "null" : object(bundle).toString();
    }

    @SuppressWarnings("deprecation")
    private static JSONObject object(Bundle bundle) throws JSONException {
        JSONObject object = new JSONObject();
        for (String key : bundle.keySet()) object.put(key, value(bundle.get(key)));
        return object;
    }

    private static Object value(Object value) throws JSONException {
        if (value == null) return JSONObject.NULL;
        if (value instanceof Bundle) return object((Bundle) value);
        if (value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value instanceof String[]) {
            JSONArray array = new JSONArray();
            for (String item : (String[]) value) array.put(item);
            return array;
        }
        if (value instanceof List) {
            JSONArray array = new JSONArray();
            for (Object item : (List<?>) value) array.put(value(item));
            return array;
        }
        return String.valueOf(value);
    }

    /** Apart, so that Android 9 to 11 never resolve a class they do not have. */
    private static final class Api31 {
        static Object shell() {
            return new android.content.AttributionSource.Builder(2000).setPackageName(SHELL_PACKAGE).build();
        }
    }
}
