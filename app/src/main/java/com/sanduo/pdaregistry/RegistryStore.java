package com.sanduo.pdaregistry;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.sanduo.pdaregistry.Models.Project;
import com.sanduo.pdaregistry.Models.Record;
import com.sanduo.pdaregistry.Models.ScanResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class RegistryStore extends SQLiteOpenHelper {
  public RegistryStore(Context context) {
    this(context, "registry.db");
  }

  public RegistryStore(Context context, String name) {
    super(context, name, null, 4);
  }

  @Override
  public void onConfigure(SQLiteDatabase db) {
    db.setForeignKeyConstraintsEnabled(true);
  }

  @Override
  public void onCreate(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL"
            + " UNIQUE,archived INTEGER NOT NULL DEFAULT 0,next_number INTEGER NOT NULL DEFAULT"
            + " 1,template TEXT NOT NULL DEFAULT '',name_prefix TEXT NOT NULL DEFAULT"
            + " '',number_digits INTEGER NOT NULL DEFAULT 3,start_number INTEGER NOT NULL DEFAULT"
            + " 1,inventory INTEGER NOT NULL DEFAULT 0)");
    db.execSQL(
        "CREATE TABLE records(id TEXT PRIMARY KEY,project_id INTEGER NOT NULL REFERENCES"
            + " projects(id),number INTEGER NOT NULL,sn TEXT NOT NULL,date TEXT NOT NULL,created_at"
            + " TEXT NOT NULL,printed INTEGER NOT NULL DEFAULT 0,equipment_name TEXT NOT NULL"
            + " DEFAULT '',asset_number TEXT NOT NULL DEFAULT '',imported_name TEXT NOT NULL"
            + " DEFAULT '',category TEXT NOT NULL DEFAULT"
            + " '',UNIQUE(project_id,sn),UNIQUE(project_id,number))");
    db.execSQL("CREATE INDEX records_project_date ON records(project_id,date)");
    createInventoryTable(db);
  }

  @Override
  public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (oldVersion < 2) {
      db.execSQL("ALTER TABLE projects ADD COLUMN name_prefix TEXT NOT NULL DEFAULT ''");
      db.execSQL("ALTER TABLE projects ADD COLUMN number_digits INTEGER NOT NULL DEFAULT 3");
      db.execSQL("ALTER TABLE projects ADD COLUMN start_number INTEGER NOT NULL DEFAULT 1");
    }
    if (oldVersion < 3) {
      db.execSQL("ALTER TABLE projects ADD COLUMN inventory INTEGER NOT NULL DEFAULT 0");
      db.execSQL("ALTER TABLE records ADD COLUMN equipment_name TEXT NOT NULL DEFAULT ''");
      db.execSQL("ALTER TABLE records ADD COLUMN asset_number TEXT NOT NULL DEFAULT ''");
      db.execSQL("ALTER TABLE records ADD COLUMN imported_name TEXT NOT NULL DEFAULT ''");
      createInventoryTable(db);
    }
    if (oldVersion < 4)
      db.execSQL("ALTER TABLE records ADD COLUMN category TEXT NOT NULL DEFAULT ''");
  }

  private void createInventoryTable(SQLiteDatabase db) {
    db.execSQL(
        "CREATE TABLE inventory_checks(record_id TEXT PRIMARY KEY REFERENCES records(id) ON DELETE"
            + " CASCADE,checked_at TEXT NOT NULL)");
  }

  public long createProject(String name) {
    name = name.trim();
    if (name.isEmpty() || name.length() > 60) throw new IllegalArgumentException("项目名称为 1–60 个字符");
    ContentValues values = new ContentValues();
    values.put("name", name);
    return getWritableDatabase().insertOrThrow("projects", null, values);
  }

  public List<Project> projects() {
    List<Project> result = new ArrayList<>();
    String sql = projectSelect() + " ORDER BY p.archived,p.id DESC";
    try (Cursor c =
        getReadableDatabase().rawQuery(sql, new String[] {LocalDate.now().toString()})) {
      while (c.moveToNext()) {
        result.add(readProject(c));
      }
    }
    return result;
  }

  public Project project(long id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                projectSelect() + " WHERE p.id=?",
                new String[] {LocalDate.now().toString(), String.valueOf(id)})) {
      return c.moveToFirst() ? readProject(c) : null;
    }
  }

  private String projectSelect() {
    return "SELECT p.id,p.name,p.archived,p.next_number,p.template,(SELECT COUNT(*) FROM records r"
        + " WHERE r.project_id=p.id),(SELECT COUNT(*) FROM records r WHERE r.project_id=p.id"
        + " AND r.date=?),p.name_prefix,p.number_digits,p.start_number,p.inventory,(SELECT"
        + " COUNT(*) FROM inventory_checks c JOIN records r ON r.id=c.record_id WHERE"
        + " r.project_id=p.id) FROM projects p";
  }

  private Project readProject(Cursor c) {
    Project p = new Project();
    p.id = c.getLong(0);
    p.name = c.getString(1);
    p.archived = c.getInt(2) != 0;
    p.nextNumber = c.getInt(3);
    p.template = c.getString(4);
    p.count = c.getInt(5);
    p.today = c.getInt(6);
    p.namePrefix = c.getString(7);
    p.numberDigits = c.getInt(8);
    p.startNumber = c.getInt(9);
    p.inventory = c.getInt(10) != 0;
    p.checked = c.getInt(11);
    return p;
  }

  public void archive(long id, boolean archived) {
    ContentValues v = new ContentValues();
    v.put("archived", archived ? 1 : 0);
    getWritableDatabase().update("projects", v, "id=?", new String[] {String.valueOf(id)});
  }

  /** Deletes the project and its records atomically, retaining a usable fallback project. */
  public long deleteProject(long id) {
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      String[] args = {String.valueOf(id)};
      db.delete("records", "project_id=?", args);
      if (db.delete("projects", "id=?", args) != 1) throw new IllegalArgumentException("项目不存在或已删除");
      long fallback;
      try (Cursor c =
          db.rawQuery("SELECT id FROM projects ORDER BY archived,id DESC LIMIT 1", null)) {
        if (c.moveToFirst()) fallback = c.getLong(0);
        else {
          ContentValues values = new ContentValues();
          values.put("name", "默认项目");
          fallback = db.insertOrThrow("projects", null, values);
        }
      }
      db.setTransactionSuccessful();
      return fallback;
    } finally {
      db.endTransaction();
    }
  }

  public void saveTemplate(long id, String json) {
    ContentValues v = new ContentValues();
    v.put("template", json);
    getWritableDatabase().update("projects", v, "id=?", new String[] {String.valueOf(id)});
  }

  public static String validateNaming(String prefix, int digits, int start) {
    if (prefix == null) throw new IllegalArgumentException("请输入名称前缀，或留空");
    prefix = prefix.trim();
    if (prefix.length() > 20) throw new IllegalArgumentException("名称前缀最多 20 个字符");
    for (int i = 0; i < prefix.length(); i++)
      if (Character.isISOControl(prefix.charAt(i)))
        throw new IllegalArgumentException("名称前缀不能包含换行或控制字符");
    if (digits < 1 || digits > 10) throw new IllegalArgumentException("编号位数须为 1–10 的整数");
    if (start < 0 || start >= Integer.MAX_VALUE)
      throw new IllegalArgumentException("起始编号须为 0–2147483646 的整数");
    return prefix;
  }

  /** Reassigns numbers in the existing equipment order without replacing record identities. */
  public void saveNaming(long projectId, String prefix, int digits, int start) {
    prefix = validateNaming(prefix, digits, start);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      if (project(projectId) == null) throw new IllegalArgumentException("请先选择项目");
      List<String> ids = new ArrayList<>();
      try (Cursor c =
          db.rawQuery(
              "SELECT id FROM records WHERE project_id=? ORDER BY number",
              new String[] {String.valueOf(projectId)})) {
        while (c.moveToNext()) ids.add(c.getString(0));
      }
      if ((long) start + ids.size() >= Integer.MAX_VALUE)
        throw new IllegalArgumentException("起始编号过大，无法容纳现有记录及下次登记");
      // Move to a disjoint temporary range to avoid the (project_id,number) unique constraint.
      db.execSQL(
          "UPDATE records SET number=-number-1 WHERE project_id=?", new Object[] {projectId});
      db.execSQL(
          "UPDATE records SET imported_name='' WHERE project_id=?", new Object[] {projectId});
      int next = start;
      for (String id : ids)
        db.execSQL(
            "UPDATE records SET number=? WHERE id=? AND project_id=?",
            new Object[] {next++, id, projectId});
      ContentValues values = new ContentValues();
      values.put("name_prefix", prefix);
      values.put("number_digits", digits);
      values.put("start_number", start);
      values.put("next_number", next);
      db.update("projects", values, "id=?", new String[] {String.valueOf(projectId)});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public static String normalizeSn(String input) {
    if (input == null) throw new IllegalArgumentException("请输入 SN");
    String sn = input.trim();
    if (sn.isEmpty() || sn.length() > 512) throw new IllegalArgumentException("SN 长度须为 1–512 个字符");
    for (int i = 0; i < sn.length(); i++)
      if (Character.isISOControl(sn.charAt(i)))
        throw new IllegalArgumentException("SN 不能包含换行或控制字符");
    return sn;
  }

  public ScanResult register(long projectId, String input) {
    String sn = normalizeSn(input);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Project project = project(projectId);
      if (project != null && project.inventory) throw new IllegalArgumentException("当前项目为盘点模式");
      int number;
      try (Cursor p =
          db.rawQuery(
              "SELECT next_number,archived FROM projects WHERE id=?",
              new String[] {String.valueOf(projectId)})) {
        if (!p.moveToFirst()) throw new IllegalArgumentException("请先选择项目");
        if (p.getInt(1) != 0) throw new IllegalArgumentException("项目已归档，请先恢复项目");
        number = p.getInt(0);
      }
      try (Cursor old =
          db.rawQuery(
              recordSelect() + " WHERE r.project_id=? AND (r.sn=? OR r.asset_number=?)",
              new String[] {String.valueOf(projectId), sn, sn})) {
        if (old.moveToFirst()) {
          Record record = readRecord(old);
          db.setTransactionSuccessful();
          return new ScanResult(record, true);
        }
      }
      if (number == Integer.MAX_VALUE) throw new IllegalStateException("设备编号已达到上限");
      String generatedName = Models.deviceName(project.namePrefix, project.numberDigits, number);
      try (Cursor c =
          db.rawQuery(
              "SELECT id FROM records WHERE project_id=? AND imported_name=?",
              new String[] {String.valueOf(projectId), generatedName})) {
        if (c.moveToFirst()) throw new IllegalArgumentException("下个设备编号与导入编号重复，请调整设备编号规则");
      }
      String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
      ContentValues v = new ContentValues();
      v.put("id", UUID.randomUUID().toString());
      v.put("project_id", projectId);
      v.put("number", number);
      v.put("sn", sn);
      v.put("date", now.substring(0, 10));
      v.put("created_at", now);
      db.insertOrThrow("records", null, v);
      db.execSQL(
          "UPDATE projects SET next_number=? WHERE id=?", new Object[] {number + 1, projectId});
      Record r;
      try (Cursor c =
          db.rawQuery(recordSelect() + " WHERE r.id=?", new String[] {v.getAsString("id")})) {
        c.moveToFirst();
        r = readRecord(c);
      }
      db.setTransactionSuccessful();
      return new ScanResult(r, false);
    } finally {
      db.endTransaction();
    }
  }

  private String recordSelect() {
    return "SELECT"
               + " r.id,r.project_id,r.number,r.sn,r.date,r.created_at,r.printed,p.name,p.name_prefix,p.number_digits,r.equipment_name,r.asset_number,r.imported_name,COALESCE(ic.checked_at,''),r.category"
               + " FROM records r JOIN projects p ON p.id=r.project_id LEFT JOIN inventory_checks"
               + " ic ON ic.record_id=r.id";
  }

  private Record readRecord(Cursor c) {
    Record r = new Record();
    r.id = c.getString(0);
    r.projectId = c.getLong(1);
    r.number = c.getInt(2);
    r.sn = c.getString(3);
    r.date = c.getString(4);
    r.createdAt = c.getString(5);
    r.printed = c.getInt(6);
    r.projectName = c.getString(7);
    r.namePrefix = c.getString(8);
    r.numberDigits = c.getInt(9);
    r.equipmentName = c.getString(10);
    r.assetNumber = c.getString(11);
    r.importedName = c.getString(12);
    r.checkedAt = c.getString(13);
    r.category = c.getString(14);
    return r;
  }

  public List<Record> records(long projectId, String search, String filter, int limit) {
    List<String> args = new ArrayList<>();
    args.add(String.valueOf(projectId));
    String where = " WHERE r.project_id=?";
    if (search != null && !search.isEmpty()) {
      String pattern =
          "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      where +=
          " AND (r.sn LIKE ? ESCAPE '\\' OR (p.name_prefix ||"
              + " printf('%0*d',p.number_digits,r.number)) LIKE ? ESCAPE '\\' OR r.equipment_name"
              + " LIKE ? ESCAPE '\\' OR r.asset_number LIKE ? ESCAPE '\\' OR r.imported_name LIKE ?"
              + " ESCAPE '\\' OR r.category LIKE ? ESCAPE '\\')";
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
      args.add(pattern);
    }
    if ("today".equals(filter)) {
      where += " AND r.date=?";
      args.add(LocalDate.now().toString());
    } else if ("unprinted".equals(filter)) where += " AND r.printed=0";
    else if ("checked".equals(filter)) where += " AND ic.record_id IS NOT NULL";
    else if ("missing".equals(filter)) where += " AND ic.record_id IS NULL";
    List<Record> result = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                recordSelect()
                    + where
                    + " ORDER BY r.number DESC"
                    + (limit > 0 ? " LIMIT " + limit : ""),
                args.toArray(new String[0]))) {
      while (c.moveToNext()) result.add(readRecord(c));
    }
    return result;
  }

  public List<Record> recordsByIds(List<String> ids) {
    List<Record> result = new ArrayList<>();
    SQLiteDatabase db = getReadableDatabase();
    for (String id : ids)
      try (Cursor c = db.rawQuery(recordSelect() + " WHERE r.id=?", new String[] {id})) {
        if (c.moveToFirst()) result.add(readRecord(c));
      }
    return result;
  }

  public Record record(long projectId, String id) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                recordSelect() + " WHERE r.project_id=? AND r.id=?",
                new String[] {String.valueOf(projectId), id})) {
      return c.moveToFirst() ? readRecord(c) : null;
    }
  }

  public Record latestRecord(long projectId) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                recordSelect() + " WHERE r.project_id=? ORDER BY r.rowid DESC LIMIT 1",
                new String[] {String.valueOf(projectId)})) {
      return c.moveToFirst() ? readRecord(c) : null;
    }
  }

  /** Corrects a record while retaining its identity, creation time and print history. */
  public void updateRecord(long projectId, String id, int number, String input, String date) {
    String sn = normalizeSn(input);
    if (number < 0 || number >= Integer.MAX_VALUE)
      throw new IllegalArgumentException("设备编号须为 0–2147483646 的整数");
    try {
      if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
        throw new IllegalArgumentException();
      LocalDate.parse(date);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("请输入有效的登记日期，格式为 YYYY-MM-DD");
    }
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Record before = record(projectId, id);
      if (before == null) throw new IllegalArgumentException("记录不存在或已删除");
      try (Cursor c =
          db.rawQuery(
              "SELECT sn,number FROM records WHERE project_id=? AND id<>? AND (sn=? OR number=?)",
              new String[] {String.valueOf(projectId), id, sn, String.valueOf(number)})) {
        while (c.moveToNext()) {
          if (sn.equals(c.getString(0))) throw new IllegalArgumentException("SN重复，未保存");
          if (number == c.getInt(1)) throw new IllegalArgumentException("设备编号重复，未保存");
        }
      }
      if (before.number != number) {
        Project p = project(projectId);
        String generatedName = p.deviceName(number);
        try (Cursor c =
            db.rawQuery(
                "SELECT id FROM records WHERE project_id=? AND id<>? AND imported_name=?",
                new String[] {String.valueOf(projectId), id, generatedName})) {
          if (c.moveToFirst()) throw new IllegalArgumentException("设备编号与导入编号重复，未保存");
        }
      }
      ContentValues values = new ContentValues();
      values.put("sn", sn);
      values.put("number", number);
      values.put("date", date);
      if (before.number != number) values.put("imported_name", "");
      if (!before.sn.equals(sn)) {
        try (Cursor c =
            db.rawQuery(
                "SELECT id FROM records WHERE project_id=? AND id<>? AND asset_number=?",
                new String[] {String.valueOf(projectId), id, sn})) {
          if (c.moveToFirst()) throw new IllegalArgumentException("条码与其他资产编号重复，未保存");
        }
        db.delete("inventory_checks", "record_id=?", new String[] {id});
      }
      db.update(
          "records", values, "project_id=? AND id=?", new String[] {String.valueOf(projectId), id});
      db.execSQL(
          "UPDATE projects SET next_number=MAX(next_number,?) WHERE id=?",
          new Object[] {number + 1, projectId});
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  /** Deleting a record never reuses its number or renumbers other records. */
  public void deleteRecord(long projectId, String id) {
    if (getWritableDatabase()
            .delete(
                "records", "project_id=? AND id=?", new String[] {String.valueOf(projectId), id})
        != 1) throw new IllegalArgumentException("记录不存在或已删除");
  }

  public void markPrinted(String id) {
    getWritableDatabase()
        .execSQL("UPDATE records SET printed=printed+1 WHERE id=?", new Object[] {id});
  }

  public void setInventoryMode(long id, boolean inventory) {
    ContentValues values = new ContentValues();
    values.put("inventory", inventory ? 1 : 0);
    if (getWritableDatabase().update("projects", values, "id=?", new String[] {String.valueOf(id)})
        != 1) throw new IllegalArgumentException("项目不存在");
  }

  /** All rows are validated before committing; a failed import never leaves a partial project. */
  public long importProject(String name, List<TableImporter.Row> rows) {
    if (rows.isEmpty()) throw new IllegalArgumentException("清单没有设备");
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      TableImporter.validate(rows);
      long id = createProject(name);
      setInventoryMode(id, true);
      int number = 1;
      java.util.HashSet<String> names = new java.util.HashSet<>();
      String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
      for (TableImporter.Row row : rows) {
        String deviceName =
            row.deviceNumber.isEmpty() ? Models.deviceName(number) : row.deviceNumber;
        if (!names.add(deviceName)) throw new IllegalArgumentException("设备编号重复：" + deviceName);
        ContentValues values = new ContentValues();
        values.put("id", UUID.randomUUID().toString());
        values.put("project_id", id);
        values.put("number", number++);
        values.put("sn", row.sn.isEmpty() ? row.assetNumber : row.sn);
        values.put("date", row.date.isEmpty() ? LocalDate.now().toString() : row.date);
        values.put("created_at", now);
        values.put("equipment_name", row.equipmentName);
        values.put("category", row.category);
        values.put("asset_number", row.assetNumber);
        values.put("imported_name", row.deviceNumber);
        db.insertOrThrow("records", null, values);
      }
      db.execSQL("UPDATE projects SET next_number=? WHERE id=?", new Object[] {number, id});
      db.setTransactionSuccessful();
      return id;
    } finally {
      db.endTransaction();
    }
  }

  /** Unknown codes return null and never alter the project or inventory progress. */
  public ScanResult checkInventory(long projectId, String input) {
    String code = normalizeSn(input);
    SQLiteDatabase db = getWritableDatabase();
    db.beginTransaction();
    try {
      Project p = project(projectId);
      if (p == null || !p.inventory) throw new IllegalArgumentException("请先将项目设置为盘点模式");
      if (p.archived) throw new IllegalArgumentException("项目已归档，请先恢复项目");
      Record record;
      try (Cursor c =
          db.rawQuery(
              recordSelect() + " WHERE r.project_id=? AND (r.sn=? OR r.asset_number=?)",
              new String[] {String.valueOf(projectId), code, code})) {
        if (!c.moveToFirst()) {
          db.setTransactionSuccessful();
          return null;
        }
        record = readRecord(c);
        if (c.moveToNext()) throw new IllegalArgumentException("项目条码不唯一，请核对清单");
      }
      boolean duplicate = !record.checkedAt.isEmpty();
      if (!duplicate) {
        record.checkedAt =
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ContentValues values = new ContentValues();
        values.put("record_id", record.id);
        values.put("checked_at", record.checkedAt);
        db.insertOrThrow("inventory_checks", null, values);
      }
      db.setTransactionSuccessful();
      return new ScanResult(record, duplicate);
    } finally {
      db.endTransaction();
    }
  }

  public Record latestInventory(long projectId) {
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                recordSelect()
                    + " WHERE r.project_id=? AND ic.record_id IS NOT NULL ORDER BY ic.rowid DESC"
                    + " LIMIT 1",
                new String[] {String.valueOf(projectId)})) {
      return c.moveToFirst() ? readRecord(c) : null;
    }
  }

  public List<String[]> inventoryGroups(long projectId) {
    List<String[]> groups = new ArrayList<>();
    try (Cursor c =
        getReadableDatabase()
            .rawQuery(
                "SELECT CASE WHEN r.category='' THEN '' ELSE r.category || ' · ' END ||"
                    + " COALESCE(NULLIF(r.equipment_name,''),'未填写设备名称'),COUNT(*),COUNT(ic.record_id)"
                    + " FROM records r LEFT JOIN inventory_checks ic ON ic.record_id=r.id WHERE"
                    + " r.project_id=? GROUP BY r.category,r.equipment_name ORDER BY"
                    + " r.category,r.equipment_name",
                new String[] {String.valueOf(projectId)})) {
      while (c.moveToNext())
        groups.add(new String[] {c.getString(0), c.getString(1), c.getString(2)});
    }
    return groups;
  }

  public void resetInventory(long projectId) {
    getWritableDatabase()
        .execSQL(
            "DELETE FROM inventory_checks WHERE record_id IN (SELECT id FROM records WHERE"
                + " project_id=?)",
            new Object[] {projectId});
  }
}
