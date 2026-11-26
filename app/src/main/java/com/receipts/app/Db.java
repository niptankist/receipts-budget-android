package com.receipts.app;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.content.ContentValues;
import android.database.Cursor;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Db extends SQLiteOpenHelper {
    static final String CATS = "Продукты,Транспорт,Автомобиль,Аптека,Дом,Одежда,Развлечения,Обучение";

    Db(Context c) { super(c, "receipts.db", null, 1); }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE r (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + " receipt TEXT UNIQUE, ts TEXT, shop TEXT, amount INTEGER, cat TEXT, note TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int a, int b) { }

    // receipt == null для ручного ввода: UNIQUE в SQLite допускает несколько NULL
    boolean add(String receipt, Date when, String shop, long kopecks, String cat, String note) {
        ContentValues v = new ContentValues();
        v.put("receipt", receipt);
        v.put("ts", new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(when));
        v.put("shop", shop);
        v.put("amount", kopecks);
        v.put("cat", cat);
        v.put("note", note);
        return getWritableDatabase().insertWithOnConflict("r", null, v,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    long total(long sinceMillis) {
        String from = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(sinceMillis));
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT SUM(amount) FROM r WHERE ts >= ?", new String[]{from});
        long r = c.moveToFirst() ? c.getLong(0) : 0;
        c.close();
        return r;
    }

    Cursor rows() {
        return getReadableDatabase().rawQuery(
                "SELECT id, ts, shop, amount, cat, note FROM r ORDER BY ts DESC, id DESC", null);
    }

    Cursor byCat(long sinceMillis) {
        String from = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(sinceMillis));
        return getReadableDatabase().rawQuery(
                "SELECT cat, SUM(amount) FROM r WHERE ts >= ? GROUP BY cat ORDER BY 2 DESC",
                new String[]{from});
    }

    void remove(long id) {
        getWritableDatabase().delete("r", "id = ?", new String[]{String.valueOf(id)});
    }

    static String money(long kopecks) {
        return String.format(Locale.US, "%d,%02d ₽", kopecks / 100, Math.abs(kopecks % 100));
    }

    /** Суммы по всем категориям за период, включая нулевые. */
    static long[] totalsByCat(Db db, long sinceMillis) {
        String[] cats = CATS.split(",");
        long[] out = new long[cats.length];
        Cursor c = db.byCat(sinceMillis);
        while (c.moveToNext()) {
            String name = c.getString(0);
            long sum = c.getLong(1);
            for (int i = 0; i < cats.length; i++) {
                if (cats[i].equals(name)) { out[i] += sum; break; }
            }
        }
        c.close();
        return out;
    }

    static String guessCat(String shop) {
        String s = shop == null ? "" : shop.toLowerCase(Locale.ROOT);
        if (s.contains("магнит") || s.contains("пятёроч") || s.contains("пятероч")
                || s.contains("перекрест") || s.contains("лента") || s.contains("ашан"))
            return "Продукты";
        if (s.contains("аптек") || s.contains("здоров")) return "Аптека";
        if (s.contains("азс") || s.contains("лукойл") || s.contains("газпромнефть")
                || s.contains("роснефт") || s.contains("татнефт")) return "Автомобиль";
        if (s.contains("метро") || s.contains("автобус") || s.contains("такси")
                || s.contains("яндекс go") || s.contains("троллейбус")) return "Транспорт";
        if (s.contains("wildberries") || s.contains("озон") || s.contains("ozon")
                || s.contains("одежд")) return "Одежда";
        if (s.contains("школ") || s.contains("курс") || s.contains("обучен")
                || s.contains("книг")) return "Обучение";
        return "Продукты";
    }
}
