package com.receipts.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Разбор QR-кода чека РФ: t=20240101T1200&s=1234.56&fn=...&i=...&fp=...&n=1 */
class Receipt {
    String raw;
    long kopecks = -1;
    Date when;
    String fn, fd, fp;
    int opType = 1;

    static Receipt parse(String qr) {
        if (qr == null) return null;
        Map<String, String> f = new HashMap<>();
        for (String part : qr.split("[&\\n\\r]+")) {
            int eq = part.indexOf('=');
            if (eq > 0) f.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        String s = f.get("s");
        if (s == null) return null;
        Receipt r = new Receipt();
        r.raw = qr;
        r.kopecks = parseMoney(s);
        if (r.kopecks < 0) return null;
        r.fn = f.get("fn");
        r.fd = f.get("i");
        r.fp = f.get("fp");
        r.opType = "2".equals(f.get("n")) ? -1 : 1;
        r.when = parseDate(f.get("t"));
        if (r.when == null) r.when = new Date();
        return r;
    }

    static long parseMoney(String s) {
        try {
            String t = s.trim().replace(',', '.').replace(" ", "");
            int dot = t.indexOf('.');
            if (dot < 0) return Long.parseLong(t) * 100;
            String whole = t.substring(0, dot);
            String frac = (t.substring(dot + 1) + "00").substring(0, 2);
            return Long.parseLong(whole) * 100 + Long.parseLong(frac);
        } catch (Exception e) {
            return -1;
        }
    }

    static Date parseDate(String t) {
        if (t == null) return null;
        String v = t.replace("T", " ");
        String[] patterns = {"yyyyMMdd HHmmss", "yyyyMMdd HHmm", "yyyyMMdd"};
        for (String p : patterns) {
            try {
                String cut = v.length() > p.length() ? v.substring(0, p.length()) : v;
                return new SimpleDateFormat(p, Locale.US).parse(cut);
            } catch (Exception ignored) { }
        }
        return null;
    }

    /** Поворот YUV-кадра на кратный 90° угол (0/90/180/270). */
    static byte[] rotate(byte[] src, int w, int h, int deg) {
        int d = ((deg % 360) + 360) % 360;
        if (d == 0) return src;
        byte[] dst = new byte[src.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                byte v = src[row + x];
                int idx;
                switch (d) {
                    case 90: idx = x * h + (h - 1 - y); break;
                    case 180: idx = (h - 1 - y) * w + (w - 1 - x); break;
                    default: idx = (w - 1 - x) * h + y; break;
                }
                dst[idx] = v;
            }
        }
        return dst;
    }

    /** Уникальный ключ фискального документа для дедупликации. */
    String key() {
        if (fn == null || fd == null || fp == null) return null;
        return fn + ":" + fd + ":" + fp + ":" + opType;
    }
}
