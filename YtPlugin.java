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
        ready = true;
    }

    @PluginMethod
    public void info(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.isEmpty()) { call.reject("لا يوجد رابط"); return; }
        try {
            init();
            YoutubeDLRequest r = new YoutubeDLRequest(url);
            r.addOption("--dump-single-json");
            r.addOption("--no-playlist");
            String out = YoutubeDL.getInstance().execute(r, null, null).getOut();
            JSONObject d = new JSONObject(out);
            TreeSet<Integer> hs = new TreeSet<>();
            JSONArray fm = d.optJSONArray("formats");
            if (fm != null) {
                for (int i = 0; i < fm.length(); i++) {
                    JSONObject f = fm.getJSONObject(i);
                    int h = f.optInt("height", 0);
                    if (h > 0 && !"none".equals(f.optString("vcodec", ""))) hs.add(h);
                }
            }
            JSArray arr = new JSArray();
            for (int h : hs) arr.put(h);
            JSObject res = new JSObject();
            res.put("title", d.optString("title", ""));
            res.put("duration", d.optDouble("duration", 0));
            res.put("thumbnail", d.optString("thumbnail", ""));
            res.put("site", d.optString("extractor_key", ""));
            res.put("heights", arr);
            call.resolve(res);
        } catch (Exception e) {
            call.reject("تعذر تحليل الرابط: " + shorten(e));
        }
    }

    @PluginMethod
    public void start(PluginCall call) {
        final String url = call.getString("url");
        final String kind = call.getString("kind", "video");
        final String fmt = call.getString("fmt", "mp4");
        final int height = call.getInt("height", 720);
        final int abr = call.getInt("abr", 192);
        if (url == null || url.isEmpty()) { call.reject("لا يوجد رابط"); return; }
        final String id = UUID.randomUUID().toString().substring(0, 12);
        JSObject st = new JSObject();
        st.put("s", "queued");
        st.put("p", 0);
        jobs.put(id, st);
        new Thread(() -> run(id, url, kind, fmt, height, abr)).start();
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

    private void run(String id, String url, String kind, String fmt, int height, int abr) {
        JSObject st = jobs.get(id);
        try {
            init();
            st.put("s", "downloading");
            File dir = new File(getContext().getCacheDir(), "dl/" + id);
            dir.mkdirs();
            YoutubeDLRequest r = new YoutubeDLRequest(url);
            r.addOption("--no-playlist");
            r.addOption("-o", dir.getAbsolutePath() + "/%(title).80s.%(ext)s");
            if ("audio".equals(kind)) {
                r.addOption("-x");
                r.addOption("--audio-format", fmt);
                r.addOption("--audio-quality", abr + "K");
            } else {
                r.addOption("-f", "bestvideo[height<=" + height + "]+bestaudio/best[height<=" + height + "]/best");
                r.addOption("--merge-output-format", fmt);
            }
            YoutubeDL.getInstance().execute(r, null, null);
            File best = null;
            File[] fs = dir.listFiles();
            if (fs != null) {
                for (File f : fs) {
                    if (f.getName().endsWith(".part")) continue;
                    if (best == null || f.lastModified() > best.lastModified()) best = f;
                }
            }
            if (best == null) throw new Exception("لم يتم إنشاء ملف");
            st.put("s", "saving");
            st.put("saved", save(best));
            st.put("p", 100);
            st.put("s", "done");
        } catch (Exception e) {
            st.put("err", shorten(e));
            st.put("s", "error");
        }
    }

    private String save(File f) throws Exception {
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
