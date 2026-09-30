package org.mistermonitor.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** A LAN-only, dependency-free Android display for the MiSTer Monitor API. */
public class MonitorActivity extends Activity {
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final long CACHE_LIMIT = 100L * 1024 * 1024;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();
    private SharedPreferences prefs;
    private Dashboard dashboard;
    private ScheduledExecutorService poller;
    private ExecutorService artworkWorker, detailWorker;
    private volatile String server, identity = "";
    private volatile long sequence = -1;
    private volatile int generation;
    private volatile boolean running, artworkBusy, detailBusy;
    private String core = "Connecting", rawGame = "", game = "Waiting for MiSTer", connectionText = "Connecting…";
    private String artworkText = "Load a game on your MiSTer";
    private String systemName = "";
    private JSONObject stats, storage, network, achievements;
    private Bitmap artwork;
    private boolean online, dimmed, immersiveArtwork, settingsOpen;
    private long lastOnline, wakeUntil, nextArtworkAt, nextDetailsAt;
    private int artworkAttempt, page;
    private String lastAchievement = "";
    private long popupUntil;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            long now = SystemClock.elapsedRealtime();
            boolean standby = prefs.getBoolean("standby", true) && !online && !settingsOpen
                    && now - lastOnline >= 180000 && now >= wakeUntil;
            if (standby != dimmed) {
                dimmed = standby;
                WindowManager.LayoutParams params = getWindow().getAttributes();
                params.screenBrightness = dimmed ? 0.04f : -1f;
                getWindow().setAttributes(params);
            }
            dashboard.invalidate();
            ui.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("monitor", MODE_PRIVATE);
        server = prefs.getString("server", "http://192.168.100.133:8081");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        dashboard = new Dashboard();
        setContentView(dashboard);
        lastOnline = SystemClock.elapsedRealtime();
        fullscreen();
    }

    private void fullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (focused) fullscreen();
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) {
            page = 0;
            immersiveArtwork = false;
            dashboard.invalidate();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        startWorkers();
    }

    private void startWorkers() {
        running = true;
        final int epoch = ++generation;
        artworkBusy = detailBusy = false;
        nextArtworkAt = nextDetailsAt = 0;
        poller = Executors.newSingleThreadScheduledExecutor();
        artworkWorker = Executors.newSingleThreadExecutor();
        detailWorker = Executors.newSingleThreadExecutor();
        poller.scheduleWithFixedDelay(() -> poll(epoch), 0, 2, TimeUnit.SECONDS);
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
    }

    @Override protected void onPause() {
        stopWorkers();
        super.onPause();
    }

    private void stopWorkers() {
        running = false;
        ++generation;
        ui.removeCallbacks(clockTick);
        if (poller != null) poller.shutdownNow();
        if (artworkWorker != null) artworkWorker.shutdownNow();
        if (detailWorker != null) detailWorker.shutdownNow();
        for (HttpURLConnection c : connections) c.disconnect();
        connections.clear();
    }

    private boolean active(int epoch) { return running && generation == epoch; }

    private void poll(int epoch) {
        String base = server;
        try {
            JSONObject snapshot = json(base, "/status/snapshot", 5000);
            if (!snapshot.has("seq") || !snapshot.has("core")) throw new IOException("Unexpected server response");
            ui.post(() -> {
                if (!active(epoch) || !base.equals(server)) return;
                online = true;
                lastOnline = SystemClock.elapsedRealtime();
                connectionText = "Connected · v" + snapshot.optString("server_version", "?");
                String nextCore = snapshot.optString("core", "Menu");
                String nextGame = snapshot.optString("game", "");
                systemName = snapshot.optString("game_system", "");
                if (systemName.isEmpty()) systemName = snapshot.optString("core_raw", nextCore);
                String nextIdentity = ClientPolicy.identity(base, nextCore, nextGame, snapshot.optString("game_path", ""));
                long nextSeq = snapshot.optLong("seq", -1);
                // A seq reset can mean a server restart. Revalidate both identity and generation.
                if (!nextIdentity.equals(identity) || nextSeq != sequence) {
                    identity = nextIdentity;
                    sequence = nextSeq;
                    core = nextCore;
                    rawGame = nextGame;
                    game = nextGame.isEmpty() ? "Choose a game on your MiSTer" : ClientPolicy.cleanTitle(nextGame);
                    if (game.isEmpty()) game = nextGame;
                    artwork = null;
                    artworkText = nextGame.isEmpty() ? "Ready when you are" : "Looking for artwork…";
                    artworkAttempt = 0;
                    nextArtworkAt = nextDetailsAt = 0;
                    achievements = null;
                    lastAchievement = "";
                    popupUntil = 0;
                }
                requestArtwork(epoch, false);
                requestDetails(epoch);
                dashboard.invalidate();
            });
        } catch (Exception e) {
            ui.post(() -> {
                if (!active(epoch)) return;
                online = false;
                connectionText = "Offline · retrying";
                dashboard.invalidate();
            });
        }
    }

    private void requestArtwork(int epoch, boolean force) {
        if (!online || artworkBusy || rawGame.isEmpty()) return;
        if (!force && (artwork != null || SystemClock.elapsedRealtime() < nextArtworkAt)) return;
        final String wanted = identity, base = server;
        final long wantedSeq = sequence;
        artworkBusy = true;
        artworkWorker.execute(() -> {
            Bitmap image = null;
            String message = "Artwork unavailable · tap to retry";
            boolean validated = true;
            File cache = new File(getCacheDir(), ClientPolicy.cacheName(wanted));
            try {
                if (!force && cache.exists()) {
                    image = decode(cache);
                    if (image != null) cache.setLastModified(System.currentTimeMillis());
                }
                if (image == null) {
                    byte[] bytes = get(base + "/media/artwork", 20000, MAX_IMAGE_BYTES);
                    // Artwork API serves the current game; verify it still matches our request.
                    JSONObject after = json(base, "/status/snapshot", 5000);
                    String afterId = ClientPolicy.identity(base, after.optString("core"), after.optString("game"), after.optString("game_path"));
                    validated = ClientPolicy.acceptsArtwork(wanted, wantedSeq, afterId, after.optLong("seq", -1));
                    if (validated && active(epoch)) {
                        File temporary = new File(getCacheDir(), cache.getName() + "." + epoch + ".tmp");
                        try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(bytes); }
                        image = decode(temporary);
                        if (image == null) { temporary.delete(); throw new IOException("Unsupported image"); }
                        if (!active(epoch)) { temporary.delete(); throw new IOException("Cancelled"); }
                        if (!temporary.renameTo(cache)) {
                            cache.delete();
                            if (!temporary.renameTo(cache)) temporary.delete();
                        }
                        trimCache();
                    }
                }
            } catch (HttpFailure e) {
                message = e.code == 404 ? "No artwork in the MiSTer pack · tap to retry" : "Artwork server error · tap to retry";
            } catch (Exception e) {
                message = "Artwork lookup is slow · tap to retry";
            }
            final Bitmap loaded = image;
            final String status = message;
            final boolean safe = validated;
            ui.post(() -> {
                if (!active(epoch)) return;
                artworkBusy = false;
                if (!ClientPolicy.acceptsArtwork(wanted, wantedSeq, identity, sequence)) {
                    requestArtwork(epoch, false);
                    return;
                }
                if (safe && loaded != null) {
                    artwork = loaded;
                    artworkText = "MiSTer artwork pack";
                } else {
                    artworkText = safe ? status : "Game changed · refreshing artwork";
                    nextArtworkAt = SystemClock.elapsedRealtime() + ClientPolicy.retryDelay(++artworkAttempt);
                }
                dashboard.invalidate();
            });
        });
    }

    private Bitmap decode(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (bounds.outWidth / options.inSampleSize > 1600 || bounds.outHeight / options.inSampleSize > 1600)
            options.inSampleSize *= 2;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void trimCache() {
        File[] files = getCacheDir().listFiles((dir, name) -> name.endsWith(".img"));
        if (files == null) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        long total = 0;
        for (File f : files) total += f.length();
        for (File f : files) {
            if (total <= CACHE_LIMIT) break;
            long length = f.length();
            if (f.delete()) total -= length;
        }
    }

    private void requestDetails(int epoch) {
        if (detailBusy || SystemClock.elapsedRealtime() < nextDetailsAt) return;
        detailBusy = true;
        nextDetailsAt = SystemClock.elapsedRealtime() + 10000;
        final String base = server, wanted = identity;
        final long wantedSeq = sequence;
        detailWorker.execute(() -> {
            JSONObject sys = tryJson(base, "/status/system");
            JSONObject disk = tryJson(base, "/status/storage");
            JSONObject net = tryJson(base, "/status/network");
            JSONObject ra = tryJson(base, "/status/retroachievements");
            // Achievement payloads are current-state APIs too. Reject old-game responses.
            JSONObject after = tryJson(base, "/status/snapshot");
            boolean matched = after != null && ClientPolicy.acceptsArtwork(wanted, wantedSeq,
                    ClientPolicy.identity(base, after.optString("core"), after.optString("game"), after.optString("game_path")), after.optLong("seq", -1));
            ui.post(() -> {
                if (!active(epoch)) return;
                detailBusy = false;
                if (!base.equals(server)) return;
                stats = sys;
                storage = disk;
                network = net;
                if (matched && ClientPolicy.acceptsArtwork(wanted, wantedSeq, identity, sequence)) {
                    int oldCounter = achievements == null ? -1 : achievements.optInt("event_counter", 0);
                    achievements = ra;
                    if (ra != null && oldCounter >= 0 && ra.optInt("event_counter", 0) > oldCounter) {
                        lastAchievement = ra.optString("last_unlock_title", "Achievement unlocked");
                        popupUntil = SystemClock.elapsedRealtime() + 8000;
                    }
                }
                dashboard.invalidate();
            });
        });
    }

    private JSONObject tryJson(String base, String path) {
        try { return json(base, path, 5000); } catch (Exception e) { return null; }
    }

    private JSONObject json(String base, String path, int timeout) throws Exception {
        return new JSONObject(new String(get(base + path, timeout, 1024 * 1024), java.nio.charset.StandardCharsets.UTF_8));
    }

    private byte[] get(String url, int timeout, int limit) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        connections.add(c);
        try {
            c.setConnectTimeout(4000);
            c.setReadTimeout(timeout);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("User-Agent", "MiSTerMonitorAndroid/0.1.2");
            int code = c.getResponseCode();
            if (code != 200) throw new HttpFailure(code);
            if (c.getContentLengthLong() > limit) throw new IOException("Response too large");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[16384];
                int size;
                while ((size = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
                    if (out.size() + size > limit) throw new IOException("Response too large");
                    out.write(buffer, 0, size);
                }
                return out.toByteArray();
            }
        } finally { connections.remove(c); c.disconnect(); }
    }

    private static class HttpFailure extends IOException {
        final int code;
        HttpFailure(int code) { super("HTTP " + code); this.code = code; }
    }

    private void settings() {
        settingsOpen = true;
        wakeUntil = SystemClock.elapsedRealtime() + 30000;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, padding / 2, padding, 0);
        TextView label = new TextView(this);
        label.setText("MiSTer address (IP or hostname, optional :port)");
        EditText address = new EditText(this);
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(server.replace("http://", ""));
        Switch standby = new Switch(this);
        standby.setText("Dim to clock after 3 minutes offline");
        standby.setChecked(prefs.getBoolean("standby", true));
        TextView help = new TextView(this);
        help.setText(connectionText + "\n\nArtwork comes from your MiSTer artwork packs. Tap artwork to fill the screen; tap again to restore controls. ScreenScraper is planned for a later version.");
        help.setPadding(0, padding / 2, 0, padding / 2);
        form.addView(label); form.addView(address); form.addView(standby); form.addView(help);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("MiSTer Monitor · 0.1.4").setView(form)
                .setNegativeButton("Cancel", null).setPositiveButton("Save", null)
                .setNeutralButton("Clear artwork cache", null).create();
        dialog.setOnDismissListener(d -> { settingsOpen = false; fullscreen(); });
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String normalized = ClientPolicy.normalizeAddress(address.getText().toString());
                    prefs.edit().putString("server", normalized).putBoolean("standby", standby.isChecked()).apply();
                    stopWorkers();
                    server = normalized;
                    identity = ""; sequence = -1;
                    online = false; artwork = null;
                    stats = storage = network = achievements = null;
                    core = "Connecting"; game = "Waiting for MiSTer"; rawGame = "";
                    connectionText = "Connecting…"; artworkText = "Load a game on your MiSTer";
                    lastOnline = SystemClock.elapsedRealtime();
                    dialog.dismiss();
                    startWorkers();
                } catch (Exception e) { address.setError(e.getMessage()); }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                // Invalidate in-flight cache writes before clearing.
                stopWorkers();
                File[] files = getCacheDir().listFiles();
                if (files != null) for (File f : files) if (f.getName().endsWith(".img") || f.getName().endsWith(".tmp")) f.delete();
                artwork = null; artworkAttempt = 0;
                artworkText = "Looking for artwork…";
                dialog.dismiss();
                startWorkers();
            });
        });
        dialog.show();
    }

    @Override public void onBackPressed() {
        if (immersiveArtwork) { immersiveArtwork = false; dashboard.invalidate(); }
        else if (page != 0) { page = 0; dashboard.invalidate(); }
        else if (isHomeApp()) { dashboard.invalidate(); }
        else new AlertDialog.Builder(this).setMessage("Close MiSTer Monitor?")
                .setNegativeButton("Stay", null).setPositiveButton("Close", (d, w) -> finish()).show();
    }

    private boolean isHomeApp() {
        if (getIntent().hasCategory(android.content.Intent.CATEGORY_HOME)) return true;
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            android.app.role.RoleManager roles = getSystemService(android.app.role.RoleManager.class);
            return roles != null && roles.isRoleHeld(android.app.role.RoleManager.ROLE_HOME);
        }
        return false;
    }

    private void navigation() {
        String[] destinations = {"Now playing", "System", "Achievements", "Settings", "Artwork only", "Choose Home app", "Android settings"};
        new AlertDialog.Builder(this).setItems(destinations, (dialog, which) -> {
            if (which == 5 || which == 6) {
                startActivity(new android.content.Intent(which == 5
                        ? android.provider.Settings.ACTION_HOME_SETTINGS : android.provider.Settings.ACTION_SETTINGS));
                return;
            }
            if (which == 3) { settings(); return; }
            if (which == 4) { page = 0; immersiveArtwork = true; }
            else { page = which; immersiveArtwork = false; }
            nextDetailsAt = 0;
            if (online) requestDetails(generation);
            dashboard.invalidate();
        }).show();
    }

    private class Dashboard extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final int background = Color.rgb(12, 18, 27), panel = Color.rgb(21, 31, 44);
        private final int muted = Color.rgb(146, 164, 181), accent = Color.rgb(83, 217, 181);
        private float scale, ox, oy, artworkWidth = 360;
        private Bitmap backdropSource, backdrop, systemLogo;
        private String logoKey = "";
        Dashboard() { super(MonitorActivity.this); setContentDescription("MiSTer Monitor dashboard"); }

        @Override protected void onDraw(Canvas c) {
            c.drawColor(background);
            scale = Math.min(getWidth() / 960f, getHeight() / 480f);
            ox = (getWidth() - 960 * scale) / 2; oy = (getHeight() - 480 * scale) / 2;
            c.save(); c.translate(ox, oy); c.scale(scale, scale);
            if (dimmed) {
                text(c, new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()), 40, 240, 116, Color.WHITE, true);
                text(c, new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date()), 46, 300, 26, muted, false);
                text(c, "MiSTer offline · reconnecting automatically", 46, 353, 22, accent, false);
                text(c, "Touch to wake", 46, 425, 18, muted, false);
                c.restore(); return;
            }
            if (immersiveArtwork && artwork != null) {
                drawImage(c, artwork, new RectF(0, 0, 960, 480));
            } else if (page == 0) nowPlaying(c);
            else {
                text(c, page == 1 ? "System" : "Achievements", 24, 40, 26, Color.WHITE, true);
                if (page == 1) system(c); else achievements(c);
            }
            cornerMenu(c);
            if (popupUntil > SystemClock.elapsedRealtime()) {
                box(c, 170, 70, 790, 154, accent);
                text(c, "ACHIEVEMENT UNLOCKED", 190, 99, 17, background, true);
                fitted(c, lastAchievement, 190, 134, 570, 24, background, true);
            }
            c.restore();
        }

        private void nowPlaying(Canvas c) {
            if (backdropSource != artwork) {
                backdropSource = artwork;
                if (backdrop != null) backdrop.recycle();
                backdrop = artwork == null ? null : blurredBackdrop(artwork);
            }
            if (backdrop != null) {
                drawCover(c, backdrop, new RectF(0, 0, 960, 480));
                paint.setColor(Color.argb(190, 0, 0, 0)); c.drawRect(0, 0, 960, 480, paint);
            }
            float size = 40;
            java.util.List<String> lines = titleLines(game, 840, size);
            while (size > 22 && lines.size() > 2) { size -= 1; lines = titleLines(game, 840, size); }
            int count = Math.min(3, lines.size());
            float titleHeight = count * size * 1.12f;
            float coverBottom = 480 - 24 - titleHeight - 22;
            if (artwork != null) {
                paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.SCREEN));
                drawImage(c, artwork, new RectF(180, 12, 780, coverBottom));
                paint.setXfermode(null);
            }
            else {
                centered(c, rawGame.isEmpty() ? "Ready when you are" : "Your artwork goes here", 480, 200, 28, Color.WHITE, false);
                centered(c, artworkBusy ? "Finding a cover…" : artworkText, 480, 236, 18, muted, false);
            }
            for (int i = 0; i < count; i++) {
                String line = lines.get(i);
                if (i == count - 1 && lines.size() > count) line += "…";
                centered(c, line, 480, coverBottom + 22 + size * .85f + i * size * 1.12f, size, Color.WHITE, true);
            }
            systemBadge(c);
            if (!online) text(c, "Reconnecting…", 24, 465, 15, muted, false);
        }

        private Bitmap blurredBackdrop(Bitmap source) {
            // A small cached blur keeps the always-on renderer inexpensive on the Echo Show.
            Bitmap small = Bitmap.createBitmap(96, 48, Bitmap.Config.ARGB_8888);
            drawCover(new Canvas(small), source, new RectF(0, 0, 96, 48));
            int[] input = new int[96 * 48], output = new int[input.length];
            small.getPixels(input, 0, 96, 0, 0, 96, 48);
            for (int pass = 0; pass < 3; pass++) {
                for (int y = 0; y < 48; y++) for (int x = 0; x < 96; x++) {
                    int r = 0, g = 0, b = 0, n = 0;
                    for (int yy = Math.max(0, y - 2); yy <= Math.min(47, y + 2); yy++)
                        for (int xx = Math.max(0, x - 2); xx <= Math.min(95, x + 2); xx++) {
                            int pixel = input[yy * 96 + xx];
                            r += Color.red(pixel); g += Color.green(pixel); b += Color.blue(pixel); n++;
                        }
                    output[y * 96 + x] = Color.rgb(r / n, g / n, b / n);
                }
                int[] swap = input; input = output; output = swap;
            }
            small.setPixels(input, 0, 96, 0, 0, 96, 48);
            return small;
        }

        private void systemBadge(Canvas c) {
            String key = ClientPolicy.systemLogo(systemName.isEmpty() ? core : systemName);
            if (!key.equals(logoKey)) {
                logoKey = key;
                if (systemLogo != null) systemLogo.recycle();
                int resource = getResources().getIdentifier("system_" + key, "drawable", getPackageName());
                systemLogo = resource == 0 ? null : BitmapFactory.decodeResource(getResources(), resource);
            }
            if (systemLogo != null) drawImage(c, systemLogo, new RectF(24, 20, 176, 58));
            else fitted(c, core, 24, 43, 230, 22, muted, true);
        }

        private void cornerMenu(Canvas c) {
            box(c, 900, 14, 946, 60, Color.argb(210, 21, 31, 44));
            paint.setColor(Color.WHITE);
            c.drawCircle(923, 27, 2, paint);
            c.drawCircle(923, 37, 2, paint);
            c.drawCircle(923, 47, 2, paint);
        }

        private java.util.List<String> titleLines(String value, float width, float size) {
            paint.setTextSize(size); paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            java.util.List<String> lines = new java.util.ArrayList<>();
            String remaining = value;
            while (!remaining.isEmpty()) {
                int end = paint.breakText(remaining, true, width, null);
                if (end <= 0) break;
                if (end < remaining.length()) {
                    int space = remaining.lastIndexOf(' ', end);
                    if (space > 0) end = space;
                }
                lines.add(remaining.substring(0, end));
                remaining = remaining.substring(end).trim();
            }
            return lines;
        }

        private void largeTitle(Canvas c, String value, float x, float width) {
            float size = 84;
            java.util.List<String> lines = titleLines(value, width, size);
            while (size > 28 && (lines.size() > 5 || lines.size() * size * 1.12f > 310)) {
                size -= 2;
                lines = titleLines(value, width, size);
            }
            int count = Math.min(8, lines.size());
            float height = count * size * 1.12f;
            float baseline = 232 - height / 2 + size * 0.85f;
            for (int i = 0; i < count; i++) {
                String line = i == count - 1 && lines.size() > count ? lines.get(i) + "…" : lines.get(i);
                fitted(c, line, x, baseline + i * size * 1.12f, width, size, Color.WHITE, true);
            }
        }

        private void drawCover(Canvas c, Bitmap bitmap, RectF area) {
            float factor = Math.max(area.width() / bitmap.getWidth(), area.height() / bitmap.getHeight());
            float w = bitmap.getWidth() * factor, h = bitmap.getHeight() * factor;
            RectF target = new RectF(area.centerX() - w / 2, area.centerY() - h / 2,
                    area.centerX() + w / 2, area.centerY() + h / 2);
            c.save(); c.clipRect(area);
            paint.setColor(Color.WHITE); c.drawBitmap(bitmap, null, target, paint);
            c.restore();
        }

        private void system(Canvas c) {
            card(c, 24, "CPU", stats == null ? "—" : percent(stats.optDouble("cpu_usage")), "MiSTer processor usage");
            card(c, 334, "MEMORY", stats == null ? "—" : percent(stats.optDouble("memory_usage")), "MiSTer memory usage");
            JSONObject sd = storage == null ? null : storage.optJSONObject("sd_card");
            card(c, 644, "SD FREE", sd == null ? "—" : String.format(Locale.US, "%.1f GB", sd.optDouble("free_gb")), "SD card space remaining");
            box(c, 24, 239, 936, 388, panel);
            long seconds = stats == null ? 0 : stats.optLong("uptime_seconds");
            text(c, "Uptime", 44, 277, 18, muted, false);
            text(c, stats == null ? "Waiting for stats" : String.format(Locale.US, "%dh %02dm", seconds / 3600, seconds / 60 % 60), 190, 277, 21, Color.WHITE, true);
            text(c, "Network", 44, 318, 18, muted, false);
            fitted(c, network == null ? "Waiting for stats" : network.optString("interface") + " · " + network.optString("ip_address"), 190, 318, 650, 21, Color.WHITE, false);
            fitted(c, online ? "Updates every 10 seconds" : "Connection lost · values may be stale", 44, 366, 840, 17, online ? accent : muted, false);
        }

        private void achievements(Canvas c) {
            box(c, 24, 76, 936, 388, panel);
            text(c, "RETROACHIEVEMENTS", 48, 116, 18, accent, true);
            if (achievements == null) {
                text(c, "Waiting for achievement data", 48, 187, 31, Color.WHITE, true);
                text(c, "Load a game and allow a few seconds to connect.", 48, 241, 22, muted, false);
                return;
            }
            if (!achievements.optBoolean("enabled")) {
                text(c, "RetroAchievements is not configured", 48, 185, 30, Color.WHITE, true);
                wrapped(c, "Add your RA username and Web API key to ra_credentials.ini on the MiSTer. Artwork and system stats work without it.", 48, 241, 825, 23, 34, 3, muted, false);
                return;
            }
            if (!achievements.optBoolean("game_matched")) {
                text(c, "No matched achievement set yet", 48, 185, 31, Color.WHITE, true);
                wrapped(c, "This game may have no supported set, or the server may still be matching it.", 48, 241, 825, 23, 34, 2, muted, false);
                return;
            }
            int total = achievements.optInt("total"), unlocked = achievements.optInt("unlocked");
            fitted(c, achievements.optString("game_title", game), 48, 163, 825, 26, Color.WHITE, true);
            text(c, unlocked + " / " + total, 48, 226, 42, Color.WHITE, true);
            text(c, achievements.optInt("points_earned") + " points", 380, 222, 26, muted, false);
            box(c, 48, 252, 906, 268, background);
            if (total > 0) box(c, 48, 252, 48 + 858 * Math.min(1f, unlocked / (float) total), 268, accent);
            String mode = achievements.optBoolean("unlocks_tracked") ? "Live achievement tracking" : "View-only progress · RA cores are needed to earn unlocks";
            fitted(c, mode, 48, 311, 825, 20, accent, false);
            fitted(c, "Hardcore: " + achievements.optInt("unlocked_hardcore") + " unlocked", 48, 357, 825, 19, muted, false);
        }

        private String percent(double value) { return String.format(Locale.US, "%.0f%%", value); }
        private void card(Canvas c, float x, String label, String value, String note) {
            box(c, x, 76, x + 292, 221, panel);
            text(c, label, x + 20, 108, 17, accent, true);
            fitted(c, value, x + 20, 164, 252, 40, Color.WHITE, true);
            fitted(c, note, x + 20, 200, 252, 17, muted, false);
        }
        private void box(Canvas c, float x1, float y1, float x2, float y2, int color) {
            paint.setColor(color); c.drawRoundRect(new RectF(x1, y1, x2, y2), 14, 14, paint);
        }
        private void text(Canvas c, String value, float x, float y, float size, int color, boolean bold) {
            paint.setColor(color); paint.setTextSize(size);
            paint.setTypeface(bold ? Typeface.create("sans-serif", Typeface.BOLD) : Typeface.create("sans-serif", Typeface.NORMAL));
            c.drawText(value, x, y, paint);
        }
        private void centered(Canvas c, String value, float centerX, float y, float size, int color, boolean bold) {
            paint.setTextSize(size);
            paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            text(c, value, centerX - paint.measureText(value) / 2, y, size, color, bold);
        }
        private void fitted(Canvas c, String value, float x, float y, float width, float size, int color, boolean bold) {
            paint.setTextSize(size); paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            if (paint.measureText(value) > width) {
                int count = paint.breakText(value, true, width - paint.measureText("…"), null);
                value = value.substring(0, Math.max(0, count)) + "…";
            }
            text(c, value, x, y, size, color, bold);
        }
        private void wrapped(Canvas c, String value, float x, float y, float width, float size, float lineHeight, int maxLines, int color, boolean bold) {
            paint.setTextSize(size); paint.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
            String remaining = value;
            for (int line = 0; line < maxLines && !remaining.isEmpty(); line++) {
                int end = paint.breakText(remaining, true, width, null);
                if (end < remaining.length() && line < maxLines - 1) {
                    int space = remaining.lastIndexOf(' ', end);
                    if (space > 0) end = space;
                }
                if (end <= 0) break;
                if (line == maxLines - 1) { fitted(c, remaining, x, y + line * lineHeight, width, size, color, bold); break; }
                text(c, remaining.substring(0, end), x, y + line * lineHeight, size, color, bold);
                remaining = remaining.substring(end).trim();
            }
        }
        private void drawImage(Canvas c, Bitmap bitmap, RectF area) {
            float factor = Math.min(area.width() / bitmap.getWidth(), area.height() / bitmap.getHeight());
            float w = bitmap.getWidth() * factor, h = bitmap.getHeight() * factor;
            RectF target = new RectF(area.centerX() - w / 2, area.centerY() - h / 2, area.centerX() + w / 2, area.centerY() + h / 2);
            paint.setColor(Color.WHITE); c.drawBitmap(bitmap, null, target, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            performClick();
            wakeUntil = SystemClock.elapsedRealtime() + 30000;
            if (dimmed) { ui.removeCallbacks(clockTick); ui.post(clockTick); return true; }
            float x = (event.getX() - ox) / scale, y = (event.getY() - oy) / scale;
            // The visible button is small, with a larger invisible touch target.
            if (x >= 884 && x <= 960 && y >= 0 && y <= 76) { navigation(); return true; }
            if (immersiveArtwork) { immersiveArtwork = false; invalidate(); return true; }
            if (page == 0 && x >= 180 && x <= 780 && y >= 12 && y <= 390) {
                if (artwork != null) immersiveArtwork = true;
                else { artworkText = "Looking for artwork…"; requestArtwork(generation, true); }
            }
            invalidate(); return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
