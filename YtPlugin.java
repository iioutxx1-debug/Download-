package app.safaa.downloader;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@CapacitorPlugin(name = "Yt")
public class YtPlugin extends Plugin {
    private final Map<String, JSObject> jobs = new ConcurrentHashMap<>();
    private boolean ready = false;

    private synchronized void init() throws Exception {
        if (ready) return;
        android.content.Context c = getContext().getApplicationContext();
        YoutubeDL.getInstance().init(c);
        FFmpeg.getInstance().init(c);
        tryUpdate(c);
        ready = true;
    }

    private void tryUpdate(android.content.Context c) {
        try {
            YoutubeDL y = YoutubeDL.getInstance();
            for (java.lang.reflect.Method m : y.getClass().getMethods()) {
                if (!m.getName().equals("updateYoutubeDL")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 2) continue;
                Object ch = null;
                if (pt[1].isEnum()) {
                    for (Object e : pt[1].getEnumConstants()) if (e.toString().equals("STABLE")) ch = e;
                } else {
                    for (java.lang.reflect.Field f : pt[1].getFields()) {
                        if (f.getName().equals("_STABLE") || f.getName().equals("STABLE")) ch = f.get(null);
                    }
                }
                if (ch != null) m.invoke(y, c, ch);
                break;
            }
        } catch (Throwable t) {
            // التحديث اختياري، نكمل بدونه
        }
    }

    @PluginMethod
    public void openFile(PluginCall call) {
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(call.getString("uri", "")), call.getString("mime", "*/*"));
            i.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("الملف محذوف أو لا يوجد مشغّل مناسب");
        }
    }

    @PluginMethod
    public void getShared(PluginCall call) {
        JSObject r = new JSObject();
        r.put("text", MainActivity.pending == null ? "" : MainActivity.pending);
        MainActivity.pending = null;
        call.resolve(r);
    }

    private YoutubeDLRequest mk(String url, JSArray x, String ck, int s) throws Exception {
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("--no-playlist");
        boolean hasEA = false;
        if (x != null) for (int i = 0; i < x.length(); i++) {
            String t = x.getString(i);
            if (t.startsWith("--extractor-args")) hasEA = true;
            if (t.startsWith("--") && !t.contains("=") && i + 1 < x.length() && !x.getString(i + 1).startsWith("-")) {
                r.addOption(t, x.getString(i + 1));
                i++;
            } else {
                r.addOption(t);
            }
        }
        if (ck != null && !ck.isEmpty()) {
            File f = new File(getContext().getCacheDir(), "ck.txt");
            try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(ck); }
            r.addOption("--cookies", f.getAbsolutePath());
        }
        if (s == 1 && !hasEA) r.addOption("--extractor-args", "youtube:player_client=android,ios");
        if (s == 2) { r.addOption("--force-ipv4"); if (!hasEA) r.addOption("--extractor-args", "youtube:player_client=tv,web_safari"); }
        return r;
    }

    @PluginMethod
    public void info(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("لا يوجد رابط"); return; }
        Exception last = null, first = null;
        try { init(); } catch (Exception e) { call.reject("تعذر تجهيز المحرك: " + shorten(e)); return; }
        for (int s = 0; s < 3; s++) {
            try {
                YoutubeDLRequest r = mk(url, call.getArray("opts"), call.getString("cookies"), s);
                r.addOption("--dump-single-json");
                JSONObject d = new JSONObject(YoutubeDL.getInstance().execute(r, null, null).getOut());
                TreeSet<Integer> hs = new TreeSet<>();
                JSONArray fm = d.optJSONArray("formats");
                if (fm != null) for (int i = 0; i < fm.length(); i++) {
                    JSONObject f = fm.getJSONObject(i);
                    int h = f.optInt("height", 0);
                    if (h > 0 && !"none".equals(f.optString("vcodec", ""))) hs.add(h);
                }
                JSArray arr = new JSArray();
                for (int h : hs) arr.put(h);
                JSObject res = new JSObject();
                res.put("title", d.optString("title", ""));
                res.put("duration", d.optDouble("duration", 0));
                res.put("thumbnail", d.optString("thumbnail", ""));
                res.put("site", d.optString("extractor_key", ""));
                res.put("heights", arr);
                String pv = "";
                int bh = -1;
                if (fm != null) for (int i = 0; i < fm.length(); i++) {
                    JSONObject f = fm.getJSONObject(i);
                    String u = f.optString("url", ""), pr = f.optString("protocol", "");
                    int h = f.optInt("height", 0);
                    if (!u.isEmpty() && (pr.equals("https") || pr.equals("http"))
                            && !"none".equals(f.optString("vcodec", "none")) && !"none".equals(f.optString("acodec", "none"))
                            && h <= 480 && h > bh) { bh = h; pv = u; }
                }
                if (pv.isEmpty() && d.optString("protocol", "").startsWith("http") && !d.optString("protocol", "").contains("m3u8")) pv = d.optString("url", "");
                res.put("preview", pv);
                call.resolve(res);
                return;
            } catch (Exception e) { last = e; if (first == null) first = e; }
        }
        call.reject("تعذر تحليل الرابط: " + shorten(first));
    }

    @PluginMethod
    public void start(PluginCall call) {
        final String url = call.getString("url");
        final String kind = call.getString("kind", "video");
        final String fmt = call.getString("fmt", "mp4");
        final int height = call.getInt("height", 720);
        final int abr = call.getInt("abr", 192);
        final int up = call.getInt("up", 0);
        final JSArray x = call.getArray("opts");
        final String ck = call.getString("cookies");
        if (url == null || url.isEmpty()) { call.reject("لا يوجد رابط"); return; }
        final String id = UUID.randomUUID().toString().substring(0, 12);
        JSObject st = new JSObject();
        st.put("s", "queued");
        st.put("p", 0);
        jobs.put(id, st);
        new Thread(() -> run(id, url, kind, fmt, height, abr, up, x, ck)).start();
        JSObject res = new JSObject();
        res.put("id", id);
        call.resolve(res);
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject st = jobs.get(call.getString("id", ""));
        if (st == null) { call.reject("المهمة غير موجودة"); return; }
        call.resolve(st);
    }

    private void run(String id, String url, String kind, String fmt, int height, int abr, int upH, JSArray x, String ck) {
        JSObject st = jobs.get(id);
        try {
            init();
            st.put("s", "downloading");
            File dir = new File(getContext().getCacheDir(), "dl/" + id);
            dir.mkdirs();
            boolean up = upH > 0 && !"audio".equals(kind);
            Exception last = null, first = null;
            for (int s = 0; s < 3; s++) {
                try {
                    YoutubeDLRequest r = mk(url, x, ck, s);
                    r.addOption("-o", dir.getAbsolutePath() + "/%(title).80s.%(ext)s");
                    if ("audio".equals(kind)) {
                        r.addOption("-x");
                        r.addOption("--audio-format", fmt);
                        r.addOption("--audio-quality", abr + "K");
                    } else {
                        r.addOption("-f", "bestvideo[height<=" + height + "]+bestaudio/best[height<=" + height + "]/best");
                        r.addOption("--merge-output-format", fmt);
                        if (up) {
                            r.addOption("--recode-video", "mp4");
                            r.addOption("--postprocessor-args", "VideoConvertor:-vf scale=-2:" + upH + ":flags=lanczos,unsharp=5:5:0.7");
                        }
                    }
                    exec(r, st, "audio".equals(kind), id);
                    last = null;
                    break;
                } catch (Exception e) {
                    last = e;
                    if (!up && first == null) first = e;
                    if (up) { up = false; st.put("note", "تعذر التكبير فحُمّل بالجودة الأصلية"); s--; }
                }
            }
            if (last != null) throw (first != null ? first : last);
            File best = null;
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) {
                if (f.getName().endsWith(".part")) continue;
                if (best == null || f.lastModified() > best.lastModified()) best = f;
            }
            if (best == null) throw new Exception("لم يتم إنشاء ملف");
            st.put("s", "saving");
            st.put("saved", save(best, st));
            st.put("p", 100);
            st.put("s", "done");
            notif(id.hashCode(), "اكتمل التحميل", 100, false);
        } catch (Exception e) {
            st.put("err", shorten(e));
            st.put("s", "error");
            notif(id.hashCode(), "فشل التحميل", 0, false);
        }
    }

    private void exec(YoutubeDLRequest r, JSObject st, boolean audio, String jid) throws Exception {
        final int total = audio ? 1 : 2;
        final int[] stream = {0};
        final float[] prev = {0};
        final int[] lastN = {-1};
        java.lang.reflect.InvocationHandler hd = (proxy, m, a) -> {
            if (m.getDeclaringClass() == Object.class)
                return m.getName().equals("equals") ? Boolean.FALSE : m.getName().equals("hashCode") ? (Object) Integer.valueOf(0) : "cb";
            if (a != null && a.length == 3 && a[0] instanceof Number) {
                float p = ((Number) a[0]).floatValue();
                if (p < prev[0] - 20 && stream[0] < total - 1) stream[0]++;
                prev[0] = p;
                int pct = (int) Math.min(95, (stream[0] * 100 + Math.max(0, p)) / total);
                st.put("p", pct);
                if (pct != lastN[0]) { lastN[0] = pct; notif(jid.hashCode(), "جارٍ التحميل", pct, true); }
            }
            return m.getReturnType() == void.class ? null : Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        };
        YoutubeDL y = YoutubeDL.getInstance();
        try {
            for (java.lang.reflect.Method m : y.getClass().getMethods()) {
                if (!m.getName().equals("execute")) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length != 3 || pt[0] != YoutubeDLRequest.class || !pt[2].isInterface()) continue;
                Object cb = java.lang.reflect.Proxy.newProxyInstance(pt[2].getClassLoader(), new Class<?>[]{pt[2]}, hd);
                m.invoke(y, r, null, cb);
                return;
            }
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable c = e.getCause();
            throw (c instanceof Exception) ? (Exception) c : e;
        }
        y.execute(r, null, null);
    }

    private void notif(int nid, String title, int p, boolean ongoing) {
        try {
            android.content.Context c = getContext();
            android.app.NotificationManager nm = (android.app.NotificationManager) c.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            android.app.Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new android.app.NotificationChannel("dl", "التحميلات", android.app.NotificationManager.IMPORTANCE_LOW));
                b = new android.app.Notification.Builder(c, "dl");
            } else {
                b = new android.app.Notification.Builder(c);
            }
            b.setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title).setOnlyAlertOnce(true).setOngoing(ongoing);
            if (ongoing) b.setProgress(100, p, false).setContentText(p + "%");
            nm.notify(nid, b.build());
        } catch (Exception e) { }
    }

    private String save(File f, JSObject st) throws Exception {
        if (Build.VERSION.SDK_INT < 29) throw new Exception("يتطلب أندرويد 10 أو أحدث");
        String name = f.getName();
        ContentResolver cr = getContext().getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime(name));
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Safaa");
        Uri u = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new Exception("تعذر الحفظ في التنزيلات");
        try (OutputStream o = cr.openOutputStream(u); InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
        }
        st.put("uri", u.toString());
        st.put("mime", mime(name));
        f.delete();
        return name;
    }

    private String mime(String n) {
        n = n.toLowerCase();
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".opus")) return "audio/ogg";
        return "application/octet-stream";
    }

    private String shorten(Exception e) {
        String m = String.valueOf(e.getMessage());
        return m.length() > 160 ? m.substring(0, 160) : m;
    }
}
