package com.aurora.launcher.data;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Game-data installer for the Aurora client.
 *
 * Config is fetched from the Kallzzxa/hehehaha JSON (branch main):
 *   data_url      -> MediaFire file page for files.zip (resolved to a direct link)
 *   data_version  -> installed-version pin
 *   data_size     -> expected archive size (0 = unknown)
 *   data_sentinel -> relative file that must exist after extraction
 *
 * Extraction goes to getExternalFilesDir(null) - the engine storage root.
 * Everything runs off the main thread; the Activity supplies progress UI
 * through the Progress callback.
 */
public final class GameDataManager {

    public static final String APP_CONFIG =
            "https://raw.githubusercontent.com/Kallzzxa/hehehaha/main/app.json";

    /** Used when the remote config is unreachable: keeps the launcher usable. */
    public static final String BUNDLED_CONFIG = "{"
            + "\"data_url\":\"https://www.mediafire.com/file/rhkel93l3frrgtn/files.zip/file\","
            + "\"data_version\":\"1\","
            + "\"data_size\":666478908,"
            + "\"data_sentinel\":\"SAMP/SAMP.ide\""
            + "}";

    private static final String PREFS = "aurora_game_data";
    private static final String KEY_DATA_VERSION = "data_version";
    private static final String MARKER = "SAMP/.data_ready";

    public interface Progress {
        void phase(String text);

        void percent(int p);

        void detail(String text);

        void done(boolean success, String error);
    }

    public static class DataInfo {
        public String url;
        public String version;
        public long size;
        public String sentinel;
    }

    private GameDataManager() {
    }

    public static DataInfo fetchConfig() throws Exception {
        JSONObject cfg;
        try {
            cfg = new JSONObject(getText(APP_CONFIG));
        } catch (Exception e) {
            cfg = new JSONObject(BUNDLED_CONFIG);
        }
        DataInfo info = new DataInfo();
        info.url = cfg.optString("data_url", "");
        if (info.url.isEmpty()) throw new Exception("data_url kosong di config");
        info.version = cfg.optString("data_version", "1");
        info.size = cfg.optLong("data_size", 0);
        info.sentinel = cfg.optString("data_sentinel", "SAMP/SAMP.ide");
        return info;
    }

    public static String installedVersion(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DATA_VERSION, "");
    }

    public static boolean isInstalled(Context ctx, DataInfo info) {
        File root = ctx.getExternalFilesDir(null);
        if (root == null) return false;
        if (!new File(root, MARKER).isFile()) return false;
        if (info.sentinel != null && !info.sentinel.isEmpty()
                && !new File(root, info.sentinel).exists()) return false;
        String[] list = root.list();
        return list != null && list.length > 0;
    }

    /** Runs on a worker thread. Reports progress through the callback. */
    public static void install(final Activity activity, final DataInfo info,
                               final Progress cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                File zipFile = null;
                try {
                    cb.phase("Menyiapkan unduhan...");

                    String direct = resolveDownloadUrl(info.url);
                    File cache = activity.getExternalCacheDir();
                    if (cache == null) cache = activity.getCacheDir();
                    zipFile = new File(cache, "aurora_data_" + info.version + ".zip");
                    if (zipFile.exists()) zipFile.delete();

                    download(direct, zipFile, info.size, cb);
                    if (info.size > 0 && zipFile.length() != info.size)
                        throw new Exception("Ukuran file tidak sesuai (" + zipFile.length()
                                + " != " + info.size + "). Download corrupt, coba lagi.");

                    cb.phase("Mengekstrak data...");
                    extract(activity, zipFile, info, cb);

                    File root = activity.getExternalFilesDir(null);
                    if (root == null) throw new Exception("Penyimpanan tidak tersedia");
                    if (info.sentinel != null && !info.sentinel.isEmpty()
                            && !new File(root, info.sentinel).exists())
                        throw new Exception("Data tidak lengkap setelah ekstrak (sentinel hilang)");

                    markInstalled(activity, info.version);

                    // LoadGameActivity gates on files/version.txt ("ver: x.y.z")
                    try (OutputStream os = new FileOutputStream(new File(root, "version.txt"))) {
                        os.write(("ver: " + info.version).getBytes("UTF-8"));
                    } catch (Exception ignored) {
                    }

                    try {
                        zipFile.delete();
                    } catch (Exception ignored) {
                    }
                    cb.done(true, null);
                } catch (final Exception e) {
                    if (zipFile != null && zipFile.exists())
                        try {
                            zipFile.delete();
                        } catch (Exception ignored) {
                        }
                    cb.done(false, e.getMessage() == null ? e.toString() : e.getMessage());
                }
            }
        }).start();
    }

    // ------------------------------------------------------------ internals --

    private static String resolveDownloadUrl(String url) throws Exception {
        if (url.contains("mediafire.com/file/")) {
            String html = getText(url);
            Matcher m = Pattern.compile("https://download\\d*\\.mediafire\\.com/[^\\s\"'<>]+")
                    .matcher(html);
            if (m.find()) return m.group(0);
            throw new Exception("Tidak menemukan link langsung di halaman MediaFire");
        }
        return url;
    }

    private static void download(String url, File out, long expected, Progress cb)
            throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("User-Agent", "AuroraLauncher");
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code / 100 != 2) throw new Exception("Server menolak download (HTTP " + code + ")");
        long total = c.getContentLengthLong();
        if (total <= 0) total = expected;

        final long totalF = total;
        cb.percent(0);
        byte[] buf = new byte[128 * 1024];
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             OutputStream fos = new BufferedOutputStream(new FileOutputStream(out))) {
            long done = 0;
            int n;
            int lastPct = -1;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                done += n;
                if (totalF > 0) {
                    int pct = (int) Math.min(100, (done * 100) / totalF);
                    if (pct != lastPct) {
                        lastPct = pct;
                        cb.percent(pct);
                        cb.detail(fmtBytes(done) + " / " + fmtBytes(totalF));
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    private static void extract(Activity a, File zipFile, DataInfo info, Progress cb)
            throws Exception {
        File root = a.getExternalFilesDir(null);
        if (root == null) throw new Exception("Penyimpanan tidak tersedia");
        final String rootCanon = root.getCanonicalPath() + File.separator;
        try (ZipFile zip = new ZipFile(zipFile)) {
            int totalEntries = zip.size();
            if (totalEntries <= 0) throw new Exception("Zip kosong / rusak");

            // strip a single wrapping folder ("files/...") if present
            java.util.ArrayList<String> names = new java.util.ArrayList<>(totalEntries);
            Enumeration<? extends ZipEntry> scan = zip.entries();
            while (scan.hasMoreElements()) names.add(scan.nextElement().getName());
            String stripPrefix = detectStripPrefix(names);

            int done = 0;
            int lastPct = -1;
            byte[] buf = new byte[128 * 1024];
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (!stripPrefix.isEmpty()) {
                    if (!name.startsWith(stripPrefix)) {
                        done++;
                        continue;
                    }
                    name = name.substring(stripPrefix.length());
                }
                if (name.isEmpty()) {
                    done++;
                    continue;
                }
                File target = new File(root, name);
                String canon = target.getCanonicalPath();
                if (!canon.startsWith(rootCanon) && !canon.equals(root.getCanonicalPath()))
                    throw new Exception("Entri zip tidak valid: " + name);

                if (e.isDirectory()) {
                    target.mkdirs();
                } else {
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (InputStream in = new BufferedInputStream(zip.getInputStream(e));
                         OutputStream os = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
                    }
                }
                done++;
                int pct = (done * 100) / totalEntries;
                if (pct != lastPct) {
                    lastPct = pct;
                    cb.percent(pct);
                    String shortName = name.length() > 40
                            ? "..." + name.substring(name.length() - 37) : name;
                    cb.detail(done + " / " + totalEntries + "  " + shortName);
                }
            }
        }
    }

    private static String detectStripPrefix(java.util.List<String> names) {
        String first = null;
        for (String n : names) {
            int slash = n.indexOf('/');
            if (slash <= 0) return "";
            String seg = n.substring(0, slash + 1);
            if (first == null) first = seg;
            else if (!first.equals(seg)) return "";
        }
        return first == null ? "" : first;
    }

    private static void markInstalled(Context ctx, String version) {
        try {
            File root = ctx.getExternalFilesDir(null);
            if (root == null) return;
            File marker = new File(root, MARKER);
            File parent = marker.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStream os = new FileOutputStream(marker)) {
                os.write(version.getBytes("UTF-8"));
            }
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_DATA_VERSION, version).apply();
        } catch (Exception ignored) {
        }
    }

    /** Writes SAMP/settings.ini the native engine reads at boot. */
    public static void writeSettingsIni(Context ctx, String nick, String host,
                                        int port, String version) {
        try {
            File root = ctx.getExternalFilesDir(null);
            if (root == null) return;
            File dir = new File(root, "SAMP");
            if (!dir.exists()) dir.mkdirs();
            File ini = new File(dir, "settings.ini");

            // Preserve an existing nickname: the user typed it on the home
            // screen and saveNickname() already wrote it. Only default when
            // the file (or the name entry) is not there yet.
            String existingNick = null;
            if (ini.exists()) {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(new FileInputStream(ini), "UTF-8"))) {
                    String line;
                    boolean inClient = false;
                    while ((line = r.readLine()) != null) {
                        String t = line.trim();
                        if (t.startsWith("[") && t.endsWith("]"))
                            inClient = t.equalsIgnoreCase("[client]");
                        else if (inClient && t.toLowerCase().startsWith("name="))
                            existingNick = t.substring(5).trim();
                    }
                } catch (Exception ignored) {
                }
            }
            if (existingNick != null && !existingNick.isEmpty()
                    && !"Nick_Name".equals(existingNick))
                nick = existingNick;

            if (nick == null) nick = "";
            nick = nick.trim();
            if (nick.isEmpty()) nick = "Player";
            if (nick.length() > 24) nick = nick.substring(0, 24);

            StringBuilder sb = new StringBuilder();
            sb.append("[client]\n");
            sb.append("name=").append(nick).append("\n");
            sb.append("host=").append(host).append("\n");
            sb.append("port=").append(port).append("\n");
            sb.append("password=\n");
            sb.append("version=").append(version).append("\n\n");
            sb.append("[gui]\n");
            sb.append("androidkeyboard=true\n");
            sb.append("VoiceChatEnable=true\n");
            sb.append("fps=true\n");
            sb.append("ChatMaxMessages=6\n");
            sb.append("FPSLimit=60\n");

            try (FileOutputStream fos = new FileOutputStream(ini)) {
                fos.write(sb.toString().getBytes("UTF-8"));
            }
        } catch (Exception ignored) {
        }
    }

    private static String fmtBytes(long n) {
        if (n < 1024) return n + " B";
        String[] u = {"B", "KB", "MB", "GB"};
        int i = 0;
        double v = n;
        while (v >= 1024 && i < u.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format(java.util.Locale.US, i == 0 ? "%.0f %s" : "%.2f %s", v, u[i]);
    }

    private static String getText(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("User-Agent", "AuroraLauncher");
        c.setInstanceFollowRedirects(true);
        try (InputStream in = new BufferedInputStream(c.getInputStream());
             BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            StringBuilder b = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) != -1) b.append(buf, 0, n);
            return b.toString();
        } finally {
            c.disconnect();
        }
    }
}
