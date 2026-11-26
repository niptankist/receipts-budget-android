package com.receipts.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private Camera camera;
    private final MultiFormatReader reader = new MultiFormatReader();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler();
    private TextView hint;
    private Viewfinder frame;
    private volatile boolean busy, done;
    private int rotation = 90;
    private int frames;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        List<BarcodeFormat> formats = new ArrayList<>();
        formats.add(BarcodeFormat.QR_CODE);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, formats);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        FrameLayout root = new FrameLayout(this);
        SurfaceView view = new SurfaceView(this);
        view.getHolder().addCallback(this);
        root.addView(view, new FrameLayout.LayoutParams(-1, -1));

        frame = new Viewfinder(this);
        root.addView(frame, new FrameLayout.LayoutParams(-1, -1));

        hint = new TextView(this);
        hint.setText("Наведите рамку на QR-код внизу чека");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setPadding(32, 40, 32, 40);
        hint.setBackgroundColor(0xAA000000);
        root.addView(hint, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP));

        setContentView(root);
        root.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }
        try {
            camera = Camera.open();
            Camera.CameraInfo info = new Camera.CameraInfo();
            Camera.getCameraInfo(0, info);
            rotation = info.orientation;
        } catch (Exception e) {
            hint.setText("Камера занята другим приложением");
        }
    }

    @Override
    protected void onPause() {
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }
        super.onPause();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        if (camera == null) return;
        try {
            camera.setPreviewDisplay(h);
            Camera.Parameters p = camera.getParameters();
            Camera.Size best = null;
            for (Camera.Size s : p.getSupportedPreviewSizes()) {
                if (best == null || Math.abs(s.width * s.height - 1280 * 720)
                        < Math.abs(best.width * best.height - 1280 * 720)) best = s;
            }
            p.setPreviewSize(best.width, best.height);
            for (String m : p.getSupportedFocusModes())
                if (Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE.equals(m)) {
                    p.setFocusMode(m);
                    break;
                }
            camera.setParameters(p);
            try { camera.setDisplayOrientation(90); } catch (Exception ignored) { }
            camera.setPreviewCallback(this);
            camera.startPreview();
        } catch (Exception e) {
            hint.setText("Ошибка камеры: " + e.getMessage());
        }
    }

    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { }
    @Override public void surfaceDestroyed(SurfaceHolder h) { }

    @Override
    public void onPreviewFrame(byte[] data, Camera cam) {
        if (busy || done || data == null || camera == null) return;
        busy = true;
        final Camera.Size s;
        try {
            s = camera.getParameters().getPreviewSize();
        } catch (Exception e) {
            busy = false;
            return;
        }
        pool.execute(() -> {
            String text = null;
            try {
                text = decode(data, s.width, s.height);
            } catch (Exception ignored) {
            }
            final String found = text;
            ui.post(() -> {
                busy = false;
                frames++;
                if (found == null) {
                    if (frames % 15 == 0)
                        hint.setText("Ищу QR… держите ровнее, ближе к чеку");
                    return;
                }
                Receipt rc = Receipt.parse(found);
                if (rc == null) {
                    hint.setText("Это не QR чека. Пробуйте другой");
                    return;
                }
                done = true;
                Intent out = new Intent();
                out.putExtra("qr", found);
                setResult(RESULT_OK, out);
                finish();
            });
        });
    }

    /** Пробует кроп-центр, затем весь кадр, в двух поворотах. */
    private String decode(byte[] data, int w, int h) {
        byte[] rot = Receipt.rotate(data, w, h, rotation);
        int rw = (rotation % 180 == 0) ? w : h;
        int rh = (rotation % 180 == 0) ? h : w;

        String r = tryCrop(rot, rw, rh, 0.55f);
        if (r == null) r = tryCrop(rot, rw, rh, 0.80f);
        if (r == null) r = tryCrop(rot, rw, rh, 1.0f);
        return r;
    }

    private String tryCrop(byte[] data, int w, int h, float frac) {
        int cw = (int) (w * frac), ch = (int) (h * frac);
        int left = (w - cw) / 2, top = (h - ch) / 2;
        try {
            PlanarYUVLuminanceSource src = new PlanarYUVLuminanceSource(
                    data, w, h, left, top, cw, ch, false);
            Result res = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
            return res == null ? null : res.getText();
        } catch (Exception e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    /** Затемнение вокруг квадратной рамки-прицела. */
    static class Viewfinder extends View {
        private final Paint dim = new Paint();
        private final Paint border = new Paint();
        private final Rect box = new Rect();

        Viewfinder(android.content.Context c) {
            super(c);
            dim.setColor(0x66000000);
            border.setColor(0xFF4CAF50);
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(6);
        }

        @Override
        protected void onDraw(Canvas c) {
            int size = (int) (Math.min(getWidth(), getHeight()) * 0.68);
            int left = (getWidth() - size) / 2;
            int top = (getHeight() - size) / 2;
            box.set(left, top, left + size, top + size);
            c.drawRect(0, 0, getWidth(), top, dim);
            c.drawRect(0, box.bottom, getWidth(), getHeight(), dim);
            c.drawRect(0, top, left, box.bottom, dim);
            c.drawRect(box.right, top, getWidth(), box.bottom, dim);
            c.drawRect(box, border);
        }
    }
}
