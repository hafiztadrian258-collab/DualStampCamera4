package com.adrian.dualstampcamera;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Looper;
import android.util.Base64;
import android.provider.MediaStore;
import android.util.Rational;
import android.util.Size;
import android.view.KeyEvent;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends ComponentActivity {
    private static final int REQ_PERM = 21;

    private WebView web;
    private PreviewView preview;
    private ProcessCameraProvider provider;
    private ImageCapture imageCapture;
    private Camera camera;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private boolean cameraWanted = false;
    private boolean canGoBack = false;
    private volatile boolean busy = false;
    private String ratio = "4:3";
    private String res = "max";
    private int quality = 95;

    private boolean gpsOn = true;
    private boolean listening = false;
    private LocationManager lm;
    private volatile Location lastLoc;

    private final ExecutorService geo = Executors.newSingleThreadExecutor();
    private volatile String lastAddr = null;
    private volatile Location addrLoc = null;
    private volatile boolean geoBusy = false;
    private volatile long lastGeoTry = 0;

    private OrientationEventListener orient;
    private volatile int extraRot = 0;
    private Typeface notoRegular, notoBold;

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("dsc", MODE_PRIVATE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new PreviewView(this);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FIT_CENTER);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));

        web = new WebView(this);
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        web.setLongClickable(false);
        web.setOnLongClickListener(v -> true);
        web.setHapticFeedbackEnabled(false);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowContentAccess(false);
        s.setBuiltInZoomControls(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Android");
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (canGoBack) web.evaluateJavascript("window.goBack&&window.goBack()", null);
                else finish();
            }
        });

        orient = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int snapped = ((o + 45) / 90 * 90) % 360;
                extraRot = (360 - snapped) % 360;
            }
        };

        web.loadUrl("file:///android_asset/index.html");
        requestNeededPerms();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orient.canDetectOrientation()) orient.enable();
        if (gpsOn) startLocation();
    }

    @Override
    protected void onPause() {
        orient.disable();
        stopLocation();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        io.shutdown();
        geo.shutdown();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        preview.postDelayed(this::bindCamera, 200);
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && cameraWanted) {
            if (e.getRepeatCount() == 0) js("window.shoot&&window.shoot()");
            return true;
        }
        return super.onKeyDown(k, e);
    }

    @Override
    public boolean onKeyUp(int k, KeyEvent e) {
        if ((k == KeyEvent.KEYCODE_VOLUME_UP || k == KeyEvent.KEYCODE_VOLUME_DOWN) && cameraWanted) return true;
        return super.onKeyUp(k, e);
    }

    // ---------------------------------------------------------------- permissions

    private boolean has(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasLoc() {
        return has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    private void requestNeededPerms() {
        List<String> need = new ArrayList<>();
        if (!has(Manifest.permission.CAMERA)) need.add(Manifest.permission.CAMERA);
        if (!hasLoc()) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (gpsOn) startLocation();
        bindCamera();
        js("window.onPerm&&window.onPerm()");
    }

    // ---------------------------------------------------------------- location

    private final LocationListener locListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location l) {
            Location cur = lastLoc;
            if (cur == null || l.getAccuracy() <= cur.getAccuracy() || l.getTime() - cur.getTime() > 20000) lastLoc = l;
            maybeGeocode(lastLoc);
        }
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
    };

    @SuppressLint("MissingPermission")
    private void startLocation() {
        if (listening || !hasLoc()) return;
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                if (lm.isProviderEnabled(p)) {
                    lm.requestLocationUpdates(p, 2000, 0f, locListener, Looper.getMainLooper());
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (lastLoc == null || l.getTime() > lastLoc.getTime())) lastLoc = l;
                    listening = true;
                }
            }
        } catch (Exception ignored) {}
    }

    private void stopLocation() {
        if (lm != null && listening) {
            try { lm.removeUpdates(locListener); } catch (Exception ignored) {}
        }
        listening = false;
    }

    private static String fmtCoords(Location l) {
        return String.format(Locale.US, "%.5f %.5f", l.getLatitude(), l.getLongitude());
    }

    private void maybeGeocode(final Location l) {
        if (l == null || geoBusy || !Geocoder.isPresent()) return;
        Location ref = addrLoc;
        long now = System.currentTimeMillis();
        if (lastAddr != null && ref != null && ref.distanceTo(l) < 25f) return;
        if (now - lastGeoTry < 8000) return;
        lastGeoTry = now;
        geoBusy = true;
        geo.execute(() -> {
            try {
                String a = lookupAddress(l);
                if (a != null) { lastAddr = a; addrLoc = l; }
            } finally {
                geoBusy = false;
            }
        });
    }

    @SuppressWarnings("deprecation")
    private String lookupAddress(Location l) {
        try {
            if (!Geocoder.isPresent()) return null;
            List<Address> r = new Geocoder(this, new Locale("id", "ID")).getFromLocation(l.getLatitude(), l.getLongitude(), 1);
            if (r == null || r.isEmpty()) return null;
            Address a = r.get(0);
            String line = a.getMaxAddressLineIndex() >= 0 ? a.getAddressLine(0) : null;
            if (line == null || line.trim().isEmpty()) {
                StringBuilder sb = new StringBuilder();
                String[] parts = {a.getThoroughfare(), a.getSubLocality(), a.getLocality(), a.getSubAdminArea(), a.getAdminArea()};
                for (String x : parts) if (x != null && !x.isEmpty()) sb.append(sb.length() > 0 ? ", " : "").append(x);
                line = sb.toString();
            }
            line = line.replaceFirst("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]*[, ]*", "");
            line = line.replaceFirst(",?\\s*Indonesia$", "").trim();
            return line.isEmpty() ? null : line;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- font

    private File sysFont(boolean bold) {
        String[] dirs = {"/system/fonts", "/product/fonts", "/system_ext/fonts", "/vendor/fonts"};
        for (String d : dirs) {
            File[] fs = new File(d).listFiles();
            if (fs == null) continue;
            for (File f : fs) {
                String n = f.getName().toLowerCase(Locale.US);
                if (!n.contains("naskharabicui") || !n.endsWith(".ttf")) continue;
                boolean isBold = n.contains("bold");
                if (bold == isBold) return f;
            }
        }
        return null;
    }

    private byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    /** Data font Noto Naskh Arabic UI: assets/fonts dulu, lalu font sistem. Kosong jika tidak ada. */
    private byte[] notoBytes(boolean bold) {
        try {
            AssetManager am = getAssets();
            return readAll(am.open("fonts/NotoNaskhArabicUI-" + (bold ? "Bold" : "Regular") + ".ttf"));
        } catch (Exception ignored) {}
        try {
            File f = sysFont(bold);
            if (f != null) return readAll(new FileInputStream(f));
        } catch (Exception ignored) {}
        return null;
    }

    private Typeface notoTypeface(boolean bold) {
        try {
            if (notoRegular == null) {
                byte[] reg = notoBytes(false);
                if (reg == null) return null;
                File tmp = new File(getCacheDir(), "noto_r.ttf");
                try (OutputStream os = new java.io.FileOutputStream(tmp)) { os.write(reg); }
                notoRegular = Typeface.createFromFile(tmp);
                byte[] b = notoBytes(true);
                if (b != null) {
                    File tb = new File(getCacheDir(), "noto_b.ttf");
                    try (OutputStream os = new java.io.FileOutputStream(tb)) { os.write(b); }
                    notoBold = Typeface.createFromFile(tb);
                } else {
                    notoBold = Typeface.create(notoRegular, Typeface.BOLD);
                }
            }
            return bold ? notoBold : notoRegular;
        } catch (Throwable e) {
            return null;
        }
    }

    private Typeface resolveFont(String id, boolean bold) {
        int st = bold ? Typeface.BOLD : Typeface.NORMAL;
        if ("noto-naskh".equals(id)) {
            Typeface t = notoTypeface(bold);
            return t != null ? t : Typeface.create("sans-serif", st);
        }
        try { return Typeface.create(id, st); } catch (Exception e) { return Typeface.create("sans-serif", st); }
    }

    // ---------------------------------------------------------------- camera

    private void bindCamera() {
        if (!cameraWanted || !has(Manifest.permission.CAMERA)) return;
        final ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(this);
        f.addListener(() -> {
            try {
                provider = f.get();
                doBind();
            } catch (Exception e) {
                toast("Kamera gagal dibuka");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressWarnings("deprecation")
    private void doBind() {
        if (provider == null) return;
        provider.unbindAll();
        imageCapture = null;
        camera = null;
        if (!cameraWanted) return;

        int rot = getWindowManager().getDefaultDisplay().getRotation();
        boolean portrait = rot == Surface.ROTATION_0 || rot == Surface.ROTATION_180;

        int vw, vh;
        if ("full".equals(ratio)) {
            vw = Math.max(1, preview.getWidth());
            vh = Math.max(1, preview.getHeight());
        } else {
            String[] r = ratio.split(":");
            int a = Integer.parseInt(r[0]), c = Integer.parseInt(r[1]);
            int lg = Math.max(a, c), sh = Math.min(a, c);
            vw = portrait ? sh : lg;
            vh = portrait ? lg : sh;
        }
        double lgS = Math.max(vw, vh), shS = Math.min(vw, vh);
        boolean wide = lgS / shS > 1.55;

        AspectRatioStrategy ars = wide
                ? AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
                : AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY;

        ResolutionSelector.Builder rb = new ResolutionSelector.Builder().setAspectRatioStrategy(ars);
        if ("mid".equals(res)) {
            rb.setResolutionStrategy(new ResolutionStrategy(wide ? new Size(3840, 2160) : new Size(3264, 2448),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER));
        } else if ("std".equals(res)) {
            rb.setResolutionStrategy(new ResolutionStrategy(wide ? new Size(1920, 1080) : new Size(1440, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER));
        } else {
            rb.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY);
        }

        Preview pv = new Preview.Builder()
                .setResolutionSelector(new ResolutionSelector.Builder().setAspectRatioStrategy(ars).build())
                .setTargetRotation(rot).build();
        imageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setResolutionSelector(rb.build())
                .setTargetRotation(rot).build();

        ViewPort vp = new ViewPort.Builder(new Rational(vw, vh), rot).build();
        UseCaseGroup g = new UseCaseGroup.Builder().setViewPort(vp).addUseCase(pv).addUseCase(imageCapture).build();
        try {
            pv.setSurfaceProvider(preview.getSurfaceProvider());
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, g);
        } catch (Exception e) {
            toast("Kamera gagal dibuka");
        }
    }

    // ---------------------------------------------------------------- capture

    private void capture(String json) {
        if (busy) return;
        if (imageCapture == null) { js("window.onSaved&&window.onSaved(false,'Kamera belum siap')"); return; }
        final JSONObject cfg;
        try { cfg = new JSONObject(json); } catch (Exception e) { return; }

        final Location capLoc;
        final List<String> manual = new ArrayList<>();
        if (cfg.optBoolean("gpsOn", true)) {
            capLoc = lastLoc;
            if (capLoc == null) {
                js("window.onSaved&&window.onSaved(false,'GPS belum dapat sinyal. Tunggu sebentar atau matikan GPS.')");
                return;
            }
        } else {
            capLoc = null;
            JSONArray arr = cfg.optJSONArray("manualLines");
            if (arr != null) for (int i = 0; i < arr.length(); i++) manual.add(arr.optString(i));
        }
        final int extra = extraRot;

        busy = true;
        imageCapture.takePicture(io, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(ImageProxy img) {
                long t = System.currentTimeMillis();
                try {
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    int rotation = img.getImageInfo().getRotationDegrees();
                    Rect crop = new Rect(img.getCropRect());
                    img.close();
                    process(bytes, rotation + extra, crop, cfg, t, capLoc, manual);
                } catch (Throwable e) {
                    try { img.close(); } catch (Exception ignored) {}
                    done(false, "Gagal menyimpan foto");
                }
            }

            @Override
            public void onError(ImageCaptureException e) {
                done(false, "Gagal mengambil gambar");
            }
        });
    }

    private void process(byte[] bytes, int rotation, Rect crop, JSONObject cfg, long t, Location capLoc, List<String> manual) throws Exception {
        Bitmap src = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (src == null) throw new Exception("decode");
        crop.intersect(0, 0, src.getWidth(), src.getHeight());
        if (crop.width() < 2 || crop.height() < 2) crop.set(0, 0, src.getWidth(), src.getHeight());
        Matrix m = new Matrix();
        m.postRotate(rotation);
        Bitmap base = Bitmap.createBitmap(src, crop.left, crop.top, crop.width(), crop.height(), m, true);
        if (base != src) src.recycle();

        if ("std".equals(res)) {
            float k = 1080f / Math.min(base.getWidth(), base.getHeight());
            if (Math.abs(k - 1f) > 0.02f) {
                Bitmap sc = Bitmap.createScaledBitmap(base, Math.round(base.getWidth() * k), Math.round(base.getHeight() * k), true);
                if (sc != base) base.recycle();
                base = sc;
            }
        }
        if (!base.isMutable()) {
            Bitmap mb = base.copy(Bitmap.Config.ARGB_8888, true);
            base.recycle();
            base = mb;
        }

        int q = Math.max(60, Math.min(100, cfg.optInt("quality", quality)));
        int delay = Math.max(0, cfg.optInt("delay", 0));
        long tA = t, tB = t + delay * 1000L;
        String name = "DualStamp_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(tA));

        List<String> gl = new ArrayList<>();
        if (capLoc != null) {
            String mode = cfg.optString("gpsFormat", "addr");
            String coords = fmtCoords(capLoc);
            String addr = null;
            if (!"coord".equals(mode)) {
                String ca = lastAddr;
                Location cl = addrLoc;
                addr = (ca != null && cl != null && cl.distanceTo(capLoc) < 150f) ? ca : lookupAddress(capLoc);
            }
            if ("coord".equals(mode)) gl.add(coords);
            else if ("both".equals(mode)) { if (addr != null) gl.add(addr); gl.add(coords); }
            else gl.add(addr != null ? addr : coords);
        } else {
            gl.addAll(manual);
        }

        JSONObject ja = cfg.getJSONObject("a"), jb = cfg.getJSONObject("b");
        Bitmap copy = base.copy(Bitmap.Config.ARGB_8888, true);
        StampRenderer.draw(copy, ja, tA, gl, resolveFont(ja.optString("font", "sans-serif"), ja.optBoolean("bold", true)));
        save(copy, name + "_A.jpg", tA, q);
        copy.recycle();

        StampRenderer.draw(base, jb, tB, gl, resolveFont(jb.optString("font", "sans-serif"), jb.optBoolean("bold", true)));
        Uri ub = save(base, name + "_B.jpg", tB, q);
        base.recycle();

        prefs.edit().putString("last", ub.toString()).apply();
        done(true, "Tersimpan: 2 foto (A & B)");
    }

    private Uri save(Bitmap bmp, String name, long taken, int q) throws Exception {
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DualStampCamera");
        v.put(MediaStore.Images.Media.DATE_TAKEN, taken);
        v.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri u = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        if (u == null) throw new Exception("insert");
        try (OutputStream os = getContentResolver().openOutputStream(u)) {
            if (os == null || !bmp.compress(Bitmap.CompressFormat.JPEG, q, os)) throw new Exception("write");
        }
        v.clear();
        v.put(MediaStore.Images.Media.IS_PENDING, 0);
        getContentResolver().update(u, v, null, null);
        return u;
    }

    private void done(boolean ok, String msg) {
        busy = false;
        js("window.onSaved&&window.onSaved(" + ok + "," + JSONObject.quote(msg) + ")");
    }

    // ---------------------------------------------------------------- helpers

    private void js(final String code) {
        web.post(() -> web.evaluateJavascript(code, null));
    }

    private void toast(String m) {
        runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
    }

    // ---------------------------------------------------------------- JS bridge

    class Bridge {
        @JavascriptInterface
        public long now() { return System.currentTimeMillis(); }

        @JavascriptInterface
        public String fmt(double ms, String pattern) {
            try { return new SimpleDateFormat(pattern, new Locale("id", "ID")).format(new Date((long) ms)); }
            catch (Exception e) { return ""; }
        }

        @JavascriptInterface
        public void focus(final double fx, final double fy) {
            runOnUiThread(() -> {
                try {
                    if (camera == null || preview.getWidth() == 0) return;
                    MeteringPoint pt = preview.getMeteringPointFactory()
                            .createPoint((float) (fx * preview.getWidth()), (float) (fy * preview.getHeight()));
                    FocusMeteringAction act = new FocusMeteringAction.Builder(pt,
                            FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                            .setAutoCancelDuration(4, TimeUnit.SECONDS).build();
                    camera.getCameraControl().startFocusAndMetering(act);
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public String zone(double ms) { return StampRenderer.zone((long) ms); }

        @JavascriptInterface
        public int getExtra() { return extraRot; }

        @JavascriptInterface
        public String fontData(boolean bold) {
            try {
                byte[] b = notoBytes(bold);
                if (b == null) return "";
                if (bold) {
                    byte[] r = notoBytes(false);
                    if (r != null && java.util.Arrays.equals(r, b)) return "";
                }
                return Base64.encodeToString(b, Base64.NO_WRAP);
            } catch (Throwable e) { return ""; }
        }

        @JavascriptInterface
        public String loadSettings() { return prefs.getString("settings", "{}"); }

        @JavascriptInterface
        public void saveSettings(String json) { prefs.edit().putString("settings", json).apply(); }

        @JavascriptInterface
        public void setConfig(String json) {
            try {
                JSONObject o = new JSONObject(json);
                final String r = o.optString("ratio", "4:3"), rs = o.optString("res", "max");
                final int q = o.optInt("quality", 95);
                runOnUiThread(() -> {
                    boolean changed = !r.equals(ratio) || !rs.equals(res);
                    ratio = r; res = rs; quality = q;
                    if (changed) bindCamera();
                });
            } catch (Exception ignored) {}
        }

        @JavascriptInterface
        public void setCameraActive(final boolean on) {
            runOnUiThread(() -> {
                cameraWanted = on;
                if (on) bindCamera();
                else if (provider != null) { provider.unbindAll(); imageCapture = null; }
            });
        }

        @JavascriptInterface
        public void capture(final String json) { runOnUiThread(() -> MainActivity.this.capture(json)); }

        @JavascriptInterface
        public void setGps(final boolean on) {
            runOnUiThread(() -> {
                gpsOn = on;
                if (on) startLocation(); else stopLocation();
            });
        }

        @JavascriptInterface
        public String getLocation() {
            try {
                JSONObject o = new JSONObject();
                if (!hasLoc()) { o.put("state", "noperm"); return o.toString(); }
                Location l = lastLoc;
                if (l == null) {
                    boolean en = lm != null && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                            || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
                    o.put("state", en ? "searching" : "off");
                } else {
                    o.put("state", "ok");
                    o.put("lat", l.getLatitude());
                    o.put("lon", l.getLongitude());
                    maybeGeocode(l);
                    String ca = lastAddr;
                    Location cl = addrLoc;
                    o.put("addr", (ca != null && cl != null && cl.distanceTo(l) < 150f) ? ca : "");
                    o.put("acc", Math.round(l.getAccuracy()));
                }
                return o.toString();
            } catch (Exception e) { return "{\"state\":\"off\"}"; }
        }

        @JavascriptInterface
        public String perms() {
            return "{\"camera\":" + has(Manifest.permission.CAMERA) + ",\"loc\":" + hasLoc() + "}";
        }

        @JavascriptInterface
        public void requestPerms() { runOnUiThread(MainActivity.this::requestNeededPerms); }

        @JavascriptInterface
        public void setCanGoBack(boolean v) { canGoBack = v; }

        @JavascriptInterface
        public void openGallery() {
            runOnUiThread(() -> {
                try {
                    String last = prefs.getString("last", null);
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(last != null ? Uri.parse(last) : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(i);
                } catch (Exception e) {
                    try {
                        startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY));
                    } catch (Exception e2) {
                        toast("Aplikasi galeri tidak ditemukan");
                    }
                }
            });
        }
    }
}
