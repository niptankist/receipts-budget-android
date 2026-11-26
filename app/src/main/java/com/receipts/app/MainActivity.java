package com.receipts.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_SCAN = 1, REQ_PERM = 2;
    private Db db;
    private TextView total, month, empty;
    private LinearLayout list, table;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        db = new Db(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        Button scan = new Button(this);
        scan.setText("Сканировать QR чека");
        scan.setOnClickListener(v -> startScan());
        root.addView(scan);

        Button add = new Button(this);
        add.setText("+ Добавить расход вручную");
        add.setOnClickListener(v -> showAdd(null));
        root.addView(add);

        Button export = new Button(this);
        export.setText("Выгрузить в файл (.txt)");
        export.setOnClickListener(v -> exportTxt());
        root.addView(export);

        total = new TextView(this);
        total.setTextSize(20);
        root.addView(total);

        month = new TextView(this);
        month.setPadding(0, pad(), 0, pad());
        root.addView(month);

        table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        root.addView(table);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        empty = new TextView(this);
        empty.setText("Пока пусто. Нажмите «+ Добавить расход».");
        list.addView(empty);

        setContentView(root);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (db != null) refresh();
    }

    private void refresh() {
        total.setText("Всего: " + Db.money(db.total(0)));
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long from = cal.getTimeInMillis();

        StringBuilder sb = new StringBuilder("За этот месяц: ").append(Db.money(db.total(from)));
        month.setText(sb.toString());
        renderTable(from);

        list.removeAllViews();
        Cursor r = db.rows();
        while (r.moveToNext()) {
            final long id = r.getLong(0);
            String line = r.getString(1) + "  " + Db.money(r.getLong(3)) + "  " + r.getString(4);
            String shop = r.getString(2);
            if (shop != null && !shop.isEmpty()) line += "  ·  " + shop;
            String note = r.getString(5);
            if (note != null && !note.isEmpty()) line += "  (" + note + ")";
            TextView t = new TextView(this);
            t.setText(line);
            t.setPadding(0, pad(), 0, pad());
            t.setOnLongClickListener(v -> { confirmDelete(id); return true; });
            list.addView(t);
        }
        r.close();
        if (list.getChildCount() == 0) list.addView(empty);
    }

    /** Таблица: категория | сумма | доля полоской. */
    private void renderTable(long from) {
        table.removeAllViews();
        String[] cats = Db.CATS.split(",");
        long[] sums = Db.totalsByCat(db, from);
        long max = 1;
        for (long s : sums) if (s > max) max = s;

        float dp = getResources().getDisplayMetrics().density;
        addRow("Категория", "Потрачено", 0f, true, dp);
        for (int i = 0; i < cats.length; i++) {
            addRow(cats[i], Db.money(sums[i]), (float) Math.abs(sums[i]) / max, false, dp);
        }
        long sum = 0;
        for (long s : sums) sum += s;
        addRow("Итого", Db.money(sum), 0f, true, dp);
    }

    private void addRow(String name, String value, float ratio, boolean header, float dp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (6 * dp), 0, (int) (6 * dp));

        TextView l = new TextView(this);
        l.setText(name);
        l.setTextSize(header ? 15 : 16);
        if (header) l.setTypeface(l.getTypeface(), android.graphics.Typeface.BOLD);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
        row.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(header ? 15 : 16);
        if (header) v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        v.setGravity(android.view.Gravity.END);
        row.addView(v);

        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.addView(row);

        if (!header) {
            android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress((int) (ratio * 1000));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, (int) (4 * dp));
            lp.topMargin = (int) (2 * dp);
            bar.setLayoutParams(lp);
            cell.addView(bar);
        }

        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E0E0);
        divider.setLayoutParams(new LinearLayout.LayoutParams(-1, 1));

        table.addView(cell);
        table.addView(divider);
    }

    /** Сохраняет отчёт в .txt и открывает системный диалог «Поделиться». */
    private void exportTxt() {
        StringBuilder sb = new StringBuilder();
        sb.append("ОТЧЁТ О РАСХОДАХ\n");
        sb.append("Сформирован: ").append(new SimpleDateFormat("dd.MM.yyyy HH:mm", new Locale("ru"))
                .format(new Date())).append("\n");

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long monthFrom = cal.getTimeInMillis();

        sb.append("\nИТОГИ ЗА МЕСЯЦ\n");
        String[] cats = Db.CATS.split(",");
        long[] sums = Db.totalsByCat(db, monthFrom);
        long monthSum = 0;
        for (int i = 0; i < cats.length; i++) {
            sb.append(String.format("%-16s %14s%n", cats[i], Db.money(sums[i])));
            monthSum += sums[i];
        }
        sb.append(String.format("%-16s %14s%n", "ИТОГО", Db.money(monthSum)));

        sb.append("\nВСЕ ЗАПИСИ\n");
        Cursor r = db.rows();
        int n = 0;
        while (r.moveToNext()) {
            n++;
            String ts = r.getString(1);
            long amount = r.getLong(3);
            String cat = r.getString(4);
            String shop = r.getString(2);
            String note = r.getString(5);
            sb.append(n).append(". ").append(ts).append("  ").append(String.format("%12s", Db.money(amount)))
                    .append("  ").append(cat);
            if (shop != null && !shop.isEmpty()) sb.append("  [").append(shop).append("]");
            if (note != null && !note.isEmpty()) sb.append("  // ").append(note);
            sb.append("\n");
        }
        r.close();
        if (n == 0) sb.append("Записей нет.\n");

        String name = "rashody-" + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US)
                .format(new Date()) + ".txt";
        try {
            java.io.OutputStream os;
            Uri uri;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new java.io.IOException("не удалось создать файл в Загрузках");
                os = getContentResolver().openOutputStream(uri);
            } else {
                File dir = android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, name);
                uri = Uri.fromFile(file);
                os = new java.io.FileOutputStream(file);
            }
            java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(os, "UTF-8");
            w.write(sb.toString());
            w.close();

            android.widget.Toast.makeText(this, "Сохранено в Загрузки: " + name,
                    android.widget.Toast.LENGTH_LONG).show();

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Расходы");
            share.putExtra(Intent.EXTRA_TEXT, sb.toString());
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Отправить отчёт"));
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "Ошибка выгрузки: " + e.getMessage(),
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private int pad() {
        return (int) (8 * getResources().getDisplayMetrics().density);
    }

    private void confirmDelete(long id) {
        new AlertDialog.Builder(this)
                .setMessage("Удалить эту запись?")
                .setPositiveButton("Удалить", (d, w) -> { db.remove(id); refresh(); })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showAdd(Receipt rc) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = pad() * 2;
        box.setPadding(pad, pad, pad, pad);

        final EditText amount = new EditText(this);
        amount.setHint("Сумма, ₽");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (rc != null) amount.setText(String.valueOf(rc.kopecks / 100.0));
        box.addView(amount);

        final EditText shop = new EditText(this);
        shop.setHint("Магазин");
        box.addView(shop);

        final EditText note = new EditText(this);
        note.setHint("Комментарий (необязательно)");
        box.addView(note);

        final String[] cats = Db.CATS.split(",");
        final String[] chosen = {cats[0]};
        final AlertDialog[] box_ref = {null};
        Button catBtn = new Button(this);
        catBtn.setText("Категория: " + chosen[0]);
        catBtn.setOnClickListener(v -> {
            String[] opts = new String[cats.length + 1];
            opts[0] = "Разбить на несколько категорий";
            System.arraycopy(cats, 0, opts, 1, cats.length);
            new AlertDialog.Builder(MainActivity.this)
                    .setItems(opts, (d, which) -> {
                        if (which == 0) {
                            if (box_ref[0] != null) box_ref[0].dismiss();
                            showSplit(rc, Receipt.parseMoney(amount.getText().toString()));
                            return;
                        }
                        chosen[0] = cats[which - 1];
                        catBtn.setText("Категория: " + chosen[0]);
                    }).show();
        });
        box.addView(catBtn);

        // магазин заполняется по мере ввода: правило категории срабатывает само
        final boolean[] manualCat = {false};
        shop.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable e) {
                if (manualCat[0]) return;
                String g = Db.guessCat(e.toString());
                if (!g.equals(chosen[0])) {
                    chosen[0] = g;
                    catBtn.setText("Категория: " + g);
                }
            }
        });

        if (rc != null) {
            Button scan = new Button(this);
            scan.setText("Сканировать QR чека (камера)");
            scan.setOnClickListener(v -> startScan());
            box.addView(scan);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(rc != null ? "Чек" : "Новый расход")
                .setView(sv)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        box_ref[0] = dialog;
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    if (save(rc, amount, shop, note, chosen[0])) dialog.dismiss();
                }));
        dialog.show();
    }

    private boolean save(Receipt rc, EditText amount, EditText shop, EditText note, String cat) {
        long kop = Receipt.parseMoney(amount.getText().toString());
        if (kop <= 0) {
            amount.setError("Введите сумму");
            return false;
        }
        if (rc != null && rc.opType < 0) kop = -kop;
        Date when = rc != null && rc.when != null ? rc.when : new Date();
        boolean ok = db.add(rc == null ? null : rc.key(), when, shop.getText().toString().trim(),
                kop, cat, note.getText().toString().trim());
        if (!ok) android.widget.Toast.makeText(this, "Этот чек уже добавлен", android.widget.Toast.LENGTH_SHORT).show();
        refresh();
        return true;
    }

    /** Делит чек на несколько категорий: сумма частей должна совпасть с итогом. */
    private void showSplit(Receipt rc, long fallbackKop) {
        final String[] cats = Db.CATS.split(",");
        final EditText[] fields = new EditText[cats.length];
        final long[] totalKop = {rc != null && rc.kopecks > 0 ? rc.kopecks : (fallbackKop > 0 ? fallbackKop : 0)};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = pad() * 2;
        box.setPadding(pad, pad, pad, pad);

        final TextView sum = new TextView(this);
        sum.setTextSize(16);
        box.addView(sum);

        final TextView rest = new TextView(this);
        rest.setTextSize(16);
        rest.setPadding(0, pad(), 0, pad());
        box.addView(rest);

        for (int i = 0; i < cats.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            TextView label = new TextView(this);
            label.setText(cats[i]);
            label.setWidth((int) (110 * getResources().getDisplayMetrics().density));
            row.addView(label);

            EditText e = new EditText(this);
            e.setHint("0");
            e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            e.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
            fields[i] = e;
            row.addView(e);
            box.addView(row);
        }

        final Runnable upd = () -> {
            long assigned = 0;
            for (EditText e : fields) {
                long v = Receipt.parseMoney(e.getText().toString());
                if (v > 0) assigned += v;
            }
            long left = totalKop[0] - assigned;
            sum.setText("Сумма чека: " + Db.money(totalKop[0]));
            rest.setText(left == 0 ? "Распределено полностью ✓"
                    : (left > 0 ? "Осталось разнести: " + Db.money(left)
                                : "Перебор на " + Db.money(-left)));
            rest.setTextColor(left == 0 ? 0xFF2E7D32 : 0xFFC62828);
        };
        for (EditText e : fields) {
            e.addTextChangedListener(new android.text.TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                public void onTextChanged(CharSequence s, int a, int b, int c) { }
                public void afterTextChanged(android.text.Editable t) { upd.run(); }
            });
        }
        upd.run();

        final EditText shop = new EditText(this);
        shop.setHint("Магазин");
        box.addView(shop);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Разбить чек по категориям")
                .setView(sv)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dlg.setOnShowListener(x -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            long assigned = 0;
            for (EditText e : fields) {
                long val = Receipt.parseMoney(e.getText().toString());
                if (val > 0) assigned += val;
            }
            if (assigned != totalKop[0]) {
                android.widget.Toast.makeText(this,
                        "Сумма частей не совпадает с чеком", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            Date when = rc != null && rc.when != null ? rc.when : new Date();
            String shopName = shop.getText().toString().trim();
            String keyBase = rc == null ? null : rc.key();
            boolean any = false;
            for (int i = 0; i < cats.length; i++) {
                long val = Receipt.parseMoney(fields[i].getText().toString());
                if (val <= 0) continue;
                any = true;
                String key = keyBase == null ? null : keyBase + "#" + i;
                db.add(key, when, shopName, val, cats[i], rc == null ? "" : "чек");
            }
            if (!any) {
                android.widget.Toast.makeText(this, "Введите хотя бы одну сумму",
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            refresh();
            dlg.dismiss();
        }));
        dlg.show();
    }

    private void startScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM);
            return;
        }
        startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_PERM && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_SCAN && result == RESULT_OK && data != null) {
            Receipt rc = Receipt.parse(data.getStringExtra("qr"));
            if (rc != null) showAdd(rc);
        }
    }
}
