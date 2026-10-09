package net.jolabs40.tvslim.aide;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Looper;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Run by TV Slim with the ADB shell's permissions:
 *
 * <pre>CLASSPATH=/data/local/tmp/tvslim-aide.apk app_process / net.jolabs40.tvslim.aide.Aide liste
 * CLASSPATH=... app_process / net.jolabs40.tvslim.aide.Aide details 96 package1 package2...</pre>
 *
 * The first line gives the protocol version, which TV Slim checks. Each following line is one app, with
 * tab-separated fields:
 *
 * <ul>
 *   <li>{@code A package versionCode system enabled launch}: launcher apps and user-installed apps;
 *   {@code launch} is the activity that opens the app, or "-";</li>
 *   <li>{@code D package label icon}: display name and icon as a base64 PNG;</li>
 *   <li>{@code E package reason}: this package could not be read.</li>
 * </ul>
 *
 * Read-only: nothing is changed on the device.
 */
public final class Aide {

    /** Bump on any format change: TV Slim refuses a helper with a different version. */
    static final int VERSION = 1;

    private static final int DRAPEAUX = PackageManager.MATCH_DISABLED_COMPONENTS;

    private Aide() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream sortie = System.out;
        sortie.println("TVSLIM_AIDE " + VERSION);
        if (args.length == 0) return;

        Looper.prepareMainLooper();
        PackageManager pm = contexteSysteme().getPackageManager();
        if ("liste".equals(args[0])) {
            liste(pm, sortie);
        } else if ("details".equals(args[0]) && args.length >= 2) {
            int taille = Integer.parseInt(args[1]);
            for (int i = 2; i < args.length; i++) details(pm, args[i], taille, sortie);
        }
        sortie.flush();
    }

    /** Gets the system context as scrcpy does; outside an app there is no other way to a PackageManager. */
    private static Context contexteSysteme() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    }

    private static void liste(PackageManager pm, PrintStream sortie) {
        // Launch activity of each launcher app, preferring the leanback one on a TV.
        boolean televiseur = pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        String[] categories = televiseur
                ? new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}
                : new String[]{Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER};
        Map<String, String> lancements = new LinkedHashMap<>();
        for (String categorie : categories) {
            Intent intention = new Intent(Intent.ACTION_MAIN).addCategory(categorie);
            for (ResolveInfo r : pm.queryIntentActivities(intention, DRAPEAUX)) {
                String paquet = r.activityInfo.packageName;
                if (!lancements.containsKey(paquet)) lancements.put(paquet, paquet + "/" + r.activityInfo.name);
            }
        }

        List<PackageInfo> paquets = pm.getInstalledPackages(DRAPEAUX);
        for (PackageInfo info : paquets) {
            ApplicationInfo app = info.applicationInfo;
            if (app == null) continue;
            boolean systeme = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            String lancement = lancements.get(info.packageName);
            // What a user calls an app: a launcher icon, or something they installed.
            if (lancement == null && systeme) continue;
            long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            sortie.println("A\t" + info.packageName + "\t" + version + "\t" + (systeme ? 1 : 0) + "\t"
                    + (app.enabled ? 1 : 0) + "\t" + (lancement != null ? lancement : "-"));
        }
    }

    private static void details(PackageManager pm, String paquet, int taille, PrintStream sortie) {
        try {
            ApplicationInfo app = pm.getApplicationInfo(paquet, DRAPEAUX);
            String nom = String.valueOf(pm.getApplicationLabel(app)).replaceAll("[\\t\\r\\n]+", " ").trim();
            Drawable icone = pm.getApplicationIcon(app);
            Bitmap image = Bitmap.createBitmap(taille, taille, Bitmap.Config.ARGB_8888);
            icone.setBounds(0, 0, taille, taille);
            icone.draw(new Canvas(image));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.PNG, 100, png);
            image.recycle();
            sortie.println("D\t" + paquet + "\t" + nom + "\t" + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP));
        } catch (Throwable erreur) {
            String motif = String.valueOf(erreur).replaceAll("[\\t\\r\\n]+", " ");
            sortie.println("E\t" + paquet + "\t" + motif);
        }
    }
}
