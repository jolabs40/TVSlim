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
 * Lancée par TV Slim avec les droits du shell d'ADB :
 *
 * <pre>CLASSPATH=/data/local/tmp/tvslim-aide.apk app_process / net.jolabs40.tvslim.aide.Aide liste
 * CLASSPATH=… app_process / net.jolabs40.tvslim.aide.Aide details 96 paquet1 paquet2…</pre>
 *
 * La première ligne dit la version du protocole, que TV Slim vérifie ; chaque ligne suivante, une application, ses
 * champs séparés par des tabulations :
 *
 * <ul>
 *   <li>{@code A paquet versionCode systeme active lancement} — les applications du menu, et celles que la personne
 *   a installées ; {@code lancement} est l'activité qui l'ouvre, ou « - » ;</li>
 *   <li>{@code D paquet nom icône} — le nom affiché et l'icône en PNG, en base 64 ;</li>
 *   <li>{@code E paquet motif} — ce paquet-là n'a pas pu être lu.</li>
 * </ul>
 *
 * Tout ce qui passe ici est en lecture seule : rien n'est changé sur l'appareil.
 */
public final class Aide {

    /** À monter avec tout changement de format : TV Slim refuse une aide d'une autre version. */
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

    /** Le contexte du système, comme le fait scrcpy : sans lui, pas de PackageManager hors d'une application. */
    private static Context contexteSysteme() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
    }

    private static void liste(PackageManager pm, PrintStream sortie) {
        // L'activité qui ouvre chaque application du menu — celle du téléviseur d'abord, s'il en est un.
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
            // Ce que la personne appelle une application : une icône dans le menu, ou ce qu'elle a installé.
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
