package com.adrian.dualstampcamera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Menggambar timestamp permanen ke bitmap. Algoritma layout sama dengan preview di app.js. */
final class StampRenderer {
    private StampRenderer() {}

    /** WIB / WITA / WIT untuk zona Indonesia, selain itu GMT+x. */
    static String zone(long ms) {
        TimeZone tz = TimeZone.getDefault();
        String id = tz.getID();
        if (id.equals("Asia/Jakarta") || id.equals("Asia/Pontianak")) return "WIB";
        if (id.equals("Asia/Makassar") || id.equals("Asia/Ujung_Pandang")) return "WITA";
        if (id.equals("Asia/Jayapura")) return "WIT";
        int m = tz.getOffset(ms) / 60000;
        return String.format(Locale.US, "GMT%s%d%s", m < 0 ? "-" : "+", Math.abs(m) / 60,
                Math.abs(m) % 60 == 0 ? "" : String.format(Locale.US, ":%02d", Math.abs(m) % 60));
    }

    /** Urutan: tanggal+jam, lokasi, lalu teks custom. */
    static void draw(Bitmap bmp, JSONObject s, long timeMs, List<String> gpsLines, Typeface tf) {
        List<String> raw = new ArrayList<>();
        Date d = new Date(timeMs);
        String dt = "";
        if (s.optBoolean("showDate", true)) {
            try {
                dt = new SimpleDateFormat(s.optString("dateFormat", "dd MMMM yyyy"), new Locale("id", "ID")).format(d);
            } catch (Exception e) {
                dt = new SimpleDateFormat("dd/MM/yyyy", Locale.US).format(d);
            }
        }
        if (s.optBoolean("showTime", true)) {
            String sep = ":".equals(s.optString("timeSep", ".")) ? ":" : ".";
            dt += (dt.isEmpty() ? "" : " ") + new SimpleDateFormat("HH" + sep + "mm" + sep + "ss", Locale.US).format(d);
            if (s.optBoolean("showZone", true)) dt += " " + zone(timeMs);
        }
        if (!dt.isEmpty()) raw.add(dt);
        if (s.optBoolean("showGps", true) && gpsLines != null) raw.addAll(gpsLines);
        for (String l : s.optString("text", "").split("\n")) {
            if (!l.trim().isEmpty()) raw.add(l);
        }
        if (raw.isEmpty()) return;

        int w = bmp.getWidth(), h = bmp.getHeight();
        boolean bg = s.optBoolean("bg", false);
        int pos = s.optInt("pos", 8);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setTypeface(tf);
        p.setColor(parse(s.optString("color", "#ffffff"), Color.WHITE));

        float ts = (float) (s.optDouble("size", 3.6) / 100.0 * Math.min(w, h));
        float margin = 0, pad = 0, bw = 0, lh;
        Paint.FontMetrics fm = null;
        List<String> lines = raw;
        for (int i = 0; i < 3; i++) {
            p.setTextSize(ts);
            fm = p.getFontMetrics();
            margin = ts * 0.6f;
            pad = bg ? ts * 0.4f : 0f;
            lines = wrap(raw, p, (w - 2 * margin - 2 * pad) * 0.8f);
            float maxW = 0;
            for (String l : lines) maxW = Math.max(maxW, p.measureText(l));
            bw = maxW + 2 * pad;
            float avail = w - 2 * margin;
            if (bw <= avail) break;
            ts *= avail / bw;
        }
        lh = (fm.descent - fm.ascent) * 1.1f;
        float bh = lines.size() * lh + 2 * pad;

        int col = pos % 3, row = pos / 3;
        float x0 = col == 0 ? margin : (col == 1 ? (w - bw) / 2f : w - margin - bw);
        float y0 = row == 0 ? margin : (row == 1 ? (h - bh) / 2f : h - margin - bh);

        Canvas c = new Canvas(bmp);
        if (bg) {
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            int base = parse(s.optString("bgColor", "#000000"), Color.BLACK);
            int alpha = Math.round(s.optInt("bgOpacity", 45) * 255f / 100f);
            bp.setColor(Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base)));
            c.drawRoundRect(new RectF(x0, y0, x0 + bw, y0 + bh), ts * 0.25f, ts * 0.25f, bp);
        } else {
            p.setShadowLayer(ts * 0.1f, ts * 0.04f, ts * 0.04f, 0xCC000000);
        }
        p.setTextAlign(col == 0 ? Paint.Align.LEFT : (col == 1 ? Paint.Align.CENTER : Paint.Align.RIGHT));
        float tx = col == 0 ? x0 + pad : (col == 1 ? x0 + bw / 2f : x0 + bw - pad);
        for (int i = 0; i < lines.size(); i++) {
            float by = y0 + pad + i * lh - fm.ascent + (lh - (fm.descent - fm.ascent)) / 2f;
            c.drawText(lines.get(i), tx, by, p);
        }
    }

    private static List<String> wrap(List<String> in, Paint p, float limit) {
        List<String> out = new ArrayList<>();
        for (String l : in) {
            if (p.measureText(l) <= limit) { out.add(l); continue; }
            StringBuilder cur = new StringBuilder();
            for (String wd : l.split(" ")) {
                if (wd.isEmpty()) continue;
                String t = cur.length() == 0 ? wd : cur + " " + wd;
                if (cur.length() > 0 && p.measureText(t) > limit) {
                    out.add(cur.toString());
                    cur = new StringBuilder(wd);
                } else {
                    cur = new StringBuilder(t);
                }
            }
            if (cur.length() > 0) out.add(cur.toString());
        }
        return out;
    }

    private static int parse(String c, int def) {
        try { return Color.parseColor(c); } catch (Exception e) { return def; }
    }
}
