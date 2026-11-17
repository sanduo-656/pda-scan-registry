package com.sanduo.pdaregistry;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.dothantech.lpapi.LPAPI;
import com.dothantech.printer.IDzPrinter;
import com.sanduo.pdaregistry.Models.Record;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/** Device tests use a separate application ID and uniquely named test databases. */
public final class RegistryTestRunner extends Instrumentation {
  private final StringBuilder report = new StringBuilder();
  private int passed, failed;
  private RegistryStore store;
  private String database;
  private MainActivity activity;

  interface Check {
    void run() throws Exception;
  }

  @Override
  public void onCreate(Bundle arguments) {
    super.onCreate(arguments);
    start();
  }

  @Override
  public void onStart() {
    if (!getTargetContext().getPackageName().endsWith(".verification")) {
      Bundle result = new Bundle();
      result.putString(
          "stream", "Refused: tests require the isolated verification application ID.\n");
      finish(Activity.RESULT_CANCELED, result);
      return;
    }
    getTargetContext()
        .getSharedPreferences("settings", 0)
        .edit()
        .putBoolean("errorFeedback", false)
        .commit();
    test("project_numbering_duplicate_persistence", this::numbering);
    test("archive_date_and_print_filters", this::filters);
    test("project_delete_transaction_isolation_and_last_fallback", this::projectDeletion);
    test("project_naming_renumber_transaction_search_export_and_print", this::projectNaming);
    test(
        "record_edit_delete_validation_isolation_rollback_and_number_continuity",
        this::recordChanges);
    test("database_v1_migration_preserves_existing_projects_and_records", this::namingMigration);
    test("parallel_registration_no_gaps", this::parallel);
    test("sn_validation_and_literal_search", this::validation);
    test("label_fields_json_and_bounds", this::labels);
    test("xlsx_text_cells_and_column_selection", this::xlsx);
    test("csv_unicode_quoting_and_text_protection", this::csv);
    test("printer_sdk_renders_30x20_job", this::printerDrawing);
    test("print_exact_selection_and_sample_request", this::printRequests);
    test("print_queue_copies_callbacks_and_sample_isolation", this::printQueue);
    test("device_ui_broadcast_duplicate_and_navigation", this::ui);
    test("launcher_entry_and_adaptive_icon", this::launcherEntry);
    test("success_notice_nonblocking_and_auto_hides", this::notice);
    test("compact_layout_and_project_search_on_pda", this::compactLayout);
    test("print_page_quantity_selection_validation_and_empty_sample", this::printUi);
    test("project_swipe_delete_cancel_confirm_and_current_switch", this::projectDeleteUi);
    test("naming_settings_preview_apply_existing_and_scan_continuation", this::namingUi);
    test(
        "record_edit_swipe_delete_cancel_confirm_selection_and_scan_continuation",
        this::recordChangesUi);
    test("scan_recent_edit_delete_return_and_fixed_footer_on_pda", this::scanRecordActions);
    test("search_expand_cancel_recreate_and_all_page_fixed_footers", this::searchAndFixedActions);
    test("export_page_document_creation_and_csv_output", this::exportPageOutput);
    test("inventoryImportCsv", this::inventoryImportCsv);
    test("inventoryImportRejects", this::inventoryImportRejects);
    test("inventoryImportXlsx", this::inventoryImportXlsx);
    test("inventoryCounting", this::inventoryCounting);
    test("inventoryDeleteAndEdit", this::inventoryDeleteAndEdit);
    test("inventoryExportRoundtrip", this::inventoryExportRoundtrip);
    test("inventoryMigration", this::inventoryMigration);
    test("inventoryUi", this::inventoryUi);
    test("import_manual_mapping_validation_and_category", this::importMapping);
    test("inventory_category_v3_migration_preserves_checks", this::categoryMigration);
    test("artifact_template_text_identifiers_and_category", this::templateImport);
    test("warning_audio_duplicate_unknown_preview_toggle_and_lifecycle", this::warningAudioUi);
    Bundle result = new Bundle();
    result.putInt("passed", passed);
    result.putInt("failed", failed);
    result.putString(
        "stream", "\n" + report + "TOTAL: " + passed + " passed, " + failed + " failed\n");
    finish(failed == 0 ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
  }

  private void test(String name, Check check) {
    database = "verification-" + UUID.randomUUID() + ".db";
    store = new RegistryStore(getTargetContext(), database);
    try {
      check.run();
      passed++;
      report.append("PASS ").append(name).append('\n');
    } catch (Throwable error) {
      failed++;
      report.append("FAIL ").append(name).append(": ").append(error).append('\n');
      android.util.Log.e("RegistryTests", name, error);
    } finally {
      store.close();
      getTargetContext().deleteDatabase(database);
    }
  }

  private static void equal(Object expected, Object actual) {
    if (!java.util.Objects.equals(expected, actual))
      throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
  }

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void rejects(Check check) throws Exception {
    try {
      check.run();
    } catch (IllegalArgumentException expected) {
      return;
    }
    throw new AssertionError("Invalid input was accepted");
  }

  private void numbering() {
    long a = store.createProject("项目甲"), b = store.createProject("项目乙");
    Models.ScanResult first = store.register(a, "00012345678901234567890");
    equal("001", first.record.deviceName());
    equal("00012345678901234567890", first.record.sn);
    Models.ScanResult duplicate = store.register(a, " 00012345678901234567890\r\n");
    require(duplicate.duplicate, "Duplicate SN inserted");
    equal(first.record.id, duplicate.record.id);
    equal(1, store.project(a).count);
    equal(2, store.project(a).nextNumber);
    equal("002", store.register(a, "NEXT").record.deviceName());
    equal("001", store.register(b, first.record.sn).record.deviceName());
    equal(LocalDate.now().toString(), first.record.date);
    store.close();
    store = new RegistryStore(getTargetContext(), database);
    equal("003", store.register(a, "AFTER-RESTART").record.deviceName());
    equal(3, store.project(a).count);
    equal(1, store.project(b).count);
    equal("1000", Models.deviceName(1000));
  }

  private void recordChanges() throws Exception {
    long a = store.createProject("记录修改甲"), b = store.createProject("记录修改乙");
    store.saveNaming(a, "SB-", 3, 1);
    Record first = store.register(a, "000FIRST").record;
    Record second = store.register(a, "000SECOND").record;
    Record other = store.register(b, "000OTHER").record;
    store.markPrinted(first.id);
    rejects(() -> store.updateRecord(a, first.id, 1, second.sn, first.date));
    rejects(() -> store.updateRecord(a, first.id, 2, first.sn, first.date));
    rejects(() -> store.updateRecord(a, first.id, -1, first.sn, first.date));
    rejects(() -> store.updateRecord(a, first.id, Integer.MAX_VALUE, first.sn, first.date));
    rejects(() -> store.updateRecord(a, first.id, 1, "", first.date));
    for (String date :
        new String[] {"2026-02-29", "2026-13-01", "2026-11-31", "2026-1-01", "invalid"})
      rejects(() -> store.updateRecord(a, first.id, 1, first.sn, date));
    rejects(() -> store.updateRecord(b, first.id, 4, "CROSS", first.date));
    rejects(() -> store.deleteRecord(b, first.id));
    equal(3, store.project(a).nextNumber);
    equal(first.sn, store.record(a, first.id).sn);
    store
        .getWritableDatabase()
        .execSQL(
            "CREATE TRIGGER fail_record_advance BEFORE UPDATE OF next_number ON projects "
                + "WHEN OLD.id="
                + a
                + " BEGIN SELECT RAISE(ABORT,'rollback edit'); END");
    boolean failed = false;
    try {
      store.updateRecord(a, first.id, 20, "ROLLBACK", "2024-02-29");
    } catch (android.database.sqlite.SQLiteException expected) {
      failed = true;
    }
    require(failed, "Edit rollback not exercised");
    store.getWritableDatabase().execSQL("DROP TRIGGER fail_record_advance");
    equal(first.sn, store.record(a, first.id).sn);
    equal(1, store.record(a, first.id).number);
    equal(3, store.project(a).nextNumber);
    store.archive(a, true);
    store.updateRecord(a, first.id, 20, " 000EDITED ", "2024-02-29");
    Record edited = store.record(a, first.id);
    equal("SB-020", edited.deviceName());
    equal("000EDITED", edited.sn);
    equal("2024-02-29", edited.date);
    equal(first.createdAt, edited.createdAt);
    equal(1, edited.printed);
    equal(21, store.project(a).nextNumber);
    equal(second.id, store.latestRecord(a).id);
    equal(1, store.records(a, "SB-020", "all", 0).size());
    equal(1, store.records(a, "000EDITED", "all", 0).size());
    require(
        !store.records(a, "", "today", 0).stream().anyMatch(r -> r.id.equals(first.id)),
        "Edited date filter stale");
    equal("SB-020", LabelTemplate.defaults().lines.get(0).resolve(edited));
    ByteArrayOutputStream csv = new ByteArrayOutputStream();
    TableExporter.csv(csv, Arrays.asList(edited), new boolean[] {true, true, true, true});
    String output = csv.toString("UTF-8");
    require(
        output.contains("SB-020") && output.contains("000EDITED") && output.contains("2024-02-29"),
        "Edited export stale");
    store.updateRecord(a, first.id, 0, edited.sn, edited.date);
    equal(21, store.project(a).nextNumber);
    store.deleteRecord(a, first.id);
    equal(null, store.record(a, first.id));
    equal(1, store.project(a).count);
    equal(2, store.record(a, second.id).number);
    equal(other.sn, store.record(b, other.id).sn);
    rejects(() -> store.deleteRecord(a, first.id));
    store.archive(a, false);
    equal("SB-021", store.register(a, first.sn).record.deviceName());
    store.close();
    store = new RegistryStore(getTargetContext(), database);
    equal(22, store.project(a).nextNumber);
    equal(null, store.record(a, first.id));
    equal(2, store.project(a).count);
  }

  private void scanRecordActions() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long p = uiStore.createProject("扫码布局验证-" + SystemClock.elapsedRealtime());
      Record first = uiStore.register(p, "000SCAN-FIRST").record;
      Record last = uiStore.register(p, "000SCAN-LAST").record;
      uiStore.updateRecord(p, first.id, 20, first.sn, first.date);
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", p)
          .putInt("scanMode", 0)
          .putBoolean("feedback", false)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No scan layout focus");
        requireVisible("SN  000SCAN-LAST");
        int baseline = scanFooterBottom();
        screenshot("ui-scan-fixed-footer.png");
        broadcast("000SCAN-LAST");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "SN重复")),
            "Pre-edit duplicate missing");
        clickDescription("编辑记录 002");
        setPrintInput("设备编号（数字部分）", "0");
        setPrintInput("设备 SN", "000EDITED-LATEST");
        setPrintInput("YYYY-MM-DD", LocalDate.now().minusDays(1).toString());
        clickText("保存修改");
        requireVisible("扫码登记");
        requireVisible("SN  000EDITED-LATEST");
        requireVisible("设备名称（DevicesID）  000");
        requireVisible("000EDITED-LATEST");
        require(
            !mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "SN重复")),
            "Stale duplicate after edit");
        equal(baseline, scanFooterBottom());
        clickDescription("编辑记录 000");
        setPrintInput("设备 SN", "UNSAVED");
        clickText("取消");
        requireVisible("SN  000EDITED-LATEST");
        swipeRow("record-" + last.id, true, false);
        screenshot("ui-scan-record-delete-swipe.png");
        clickDescription("删除记录 000");
        await(() -> activeWindowText("删除记录？"), "Missing scan delete confirmation");
        clickActiveWindow("取消");
        equal(2, uiStore.project(p).count);
        swipeRow("record-" + last.id, false, false);
        clickDescription("删除记录 000");
        await(() -> activeWindowText("删除记录？"), "Missing scan second delete confirmation");
        clickActiveWindow("删除记录");
        await(() -> uiStore.project(p).count == 1, "Scan record not deleted");
        requireVisible("扫码登记");
        requireVisible("SN  000SCAN-FIRST");
        equal(baseline, scanFooterBottom());
        broadcast("000SCAN-FIRST");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "SN重复")),
            "Pre-delete duplicate missing");
        clickDescription("编辑记录 020");
        clickDescription("删除这条记录");
        await(() -> activeWindowText("删除记录？"), "Missing editor delete confirmation");
        clickActiveWindow("删除记录");
        await(() -> uiStore.project(p).count == 0, "Last scan record not deleted");
        requireVisible("尚未登记设备");
        requireVisible("等待扫码…");
        equal(baseline, scanFooterBottom());
        equal(null, uiStore.latestRecord(p));
        equal(21, uiStore.project(p).nextNumber);
        broadcast("000AFTER-DELETE");
        await(() -> uiStore.project(p).count == 1, "Scan did not resume after record deletion");
        equal(21, uiStore.latestRecord(p).number);
        equal(baseline, scanFooterBottom());
        requireVisible("000AFTER-DELETE");
        SystemClock.sleep(1700);
        requireVisible("000AFTER-DELETE");
        screenshot("ui-scan-retained-result.png");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_F11);
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "等待扫码…")),
            "Scan key did not clear previous result");
        equal(1, uiStore.project(p).count);
        requireVisible("SN  000AFTER-DELETE");
        screenshot("ui-scan-next-trigger.png");
        broadcast("000AFTER-DELETE");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "SN重复")),
            "Duplicate missing");
        equal(baseline, scanFooterBottom());
        screenshot("ui-scan-duplicate-fixed-footer.png");
        clickText("暂停登记");
        equal(baseline, scanFooterBottom());
        broadcast("PAUSED-IGNORED");
        SystemClock.sleep(200);
        equal(1, uiStore.project(p).count);
        clickText("继续登记");
        clickText("手动录入");
        await(() -> activeWindowText("手动录入 SN"), "Fixed manual button not working");
        clickActiveWindow("取消");
        await(
            () -> mainBoolean(() -> activity.hasWindowFocus()),
            "Manual dialog did not release focus");
        equal(baseline, scanFooterBottom());
        broadcast("LONG-SN-" + String.join("", java.util.Collections.nCopies(12, "1234567890")));
        await(() -> uiStore.project(p).count == 2, "Long SN not registered");
        equal(baseline, scanFooterBottom());
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
      getTargetContext().getSharedPreferences("settings", 0).edit().putInt("scanMode", 1).commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No focus-mode layout focus");
        scanFooterBottom();
        sendStringSync("FOOTER-FOCUS");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);
        await(() -> uiStore.project(p).count == 3, "Focus scan not saved with fixed footer");
        scanFooterBottom();
        screenshot("ui-scan-focus-fixed-footer.png");
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
        getTargetContext()
            .getSharedPreferences("settings", 0)
            .edit()
            .putInt("scanMode", 0)
            .commit();
      }
    }
  }

  private int scanFooterBottom() {
    await(
        () ->
            mainBoolean(
                () -> {
                  View footer = activity.getWindow().getDecorView().findViewWithTag("page-footer");
                  return footer != null && footer.isLaidOut() && !footer.isLayoutRequested();
                }),
        "Scan footer layout did not settle");
    int[] bottom = {0};
    runOnMainSync(
        () -> {
          View root = activity.getWindow().getDecorView();
          View pause = findText(root, "暂停登记");
          if (pause == null) pause = findText(root, "继续登记");
          View manual = findText(root, "手动录入");
          View navLabel = findText(root, "扫码");
          require(pause != null && manual != null && navLabel != null, "Scan actions missing");
          View footer = (View) pause.getParent().getParent();
          for (android.view.ViewParent parent = pause.getParent();
              parent != null;
              parent = parent.getParent())
            require(
                !(parent instanceof android.widget.ScrollView),
                "Scan actions still scroll with content");
          View nav = (View) navLabel.getParent().getParent().getParent();
          View recent = root.findViewWithTag("scan-recent-record");
          View waiting = root.findViewWithTag("scan-waiting");
          require(recent != null && waiting != null, "Missing scan result sections");
          int[] recentPosition = new int[2], waitingPosition = new int[2];
          recent.getLocationOnScreen(recentPosition);
          waiting.getLocationOnScreen(waitingPosition);
          require(
              recentPosition[1] + recent.getHeight() <= waitingPosition[1],
              "Recent success must be above the scan area as in k5Os4");
          int[] navPosition = new int[2], footerPosition = new int[2], buttonPosition = new int[2];
          nav.getLocationOnScreen(navPosition);
          footer.getLocationOnScreen(footerPosition);
          pause.getLocationOnScreen(buttonPosition);
          float density = getTargetContext().getResources().getDisplayMetrics().density;
          require(
              Math.abs(footerPosition[1] + footer.getHeight() - navPosition[1]) <= 2,
              "Footer is not immediately above navigation");
          bottom[0] = buttonPosition[1] + pause.getHeight();
          require(
              navPosition[1] - bottom[0] <= 16 * density && bottom[0] <= navPosition[1],
              "Scan buttons are not at the bottom of the content area");
        });
    requireVisible("手动录入");
    return bottom[0];
  }

  private void recordChangesUi() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long p = uiStore.createProject("记录编辑验证-" + SystemClock.elapsedRealtime());
      uiStore.saveNaming(p, "SB-", 3, 1);
      Record first = uiStore.register(p, "002611120126").record;
      uiStore.register(p, "002611120127");
      Record target = uiStore.register(p, "002611120128").record;
      uiStore.markPrinted(target.id);
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", p)
          .putInt("scanMode", 0)
          .putBoolean("feedback", false)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No record editor focus");
        clickDescription("记录");
        clickDescription("选择设备 SB-003");
        requireVisible("已选 1 条");
        clickDescription("编辑记录 SB-003");
        requireVisible("编辑记录");
        requireVisible("完整编号：SB-003");
        broadcast("BLOCKED-IN-EDITOR");
        SystemClock.sleep(200);
        equal(3, uiStore.project(p).count);
        setPrintInput("设备 SN", first.sn);
        clickText("保存修改");
        requireVisible("SN重复，未保存");
        equal(target.sn, uiStore.record(p, target.id).sn);
        setPrintInput("设备 SN", "000EDITED-SN");
        setPrintInput("设备编号（数字部分）", "2");
        clickText("保存修改");
        requireVisible("设备编号重复，未保存");
        setPrintInput("设备编号（数字部分）", "10");
        setPrintInput("YYYY-MM-DD", "2026-02-29");
        clickText("保存修改");
        requireVisible("请输入有效的登记日期，格式为 YYYY-MM-DD");
        setPrintInput("YYYY-MM-DD", "2026-11-11");
        requireVisible("完整编号：SB-010");
        screenshot("ui-record-edit.png");
        clickText("保存修改");
        equal("000EDITED-SN", uiStore.record(p, target.id).sn);
        equal(1, uiStore.record(p, target.id).printed);
        equal(11, uiStore.project(p).nextNumber);
        requireVisible("登记日期  2026-11-11");
        requireVisible("已选 1 条");
        clickDescription("编辑记录 SB-010");
        setPrintInput("设备 SN", "UNSAVED");
        clickText("取消");
        equal("000EDITED-SN", uiStore.record(p, target.id).sn);
        swipeRow("record-" + target.id, true, false);
        require(
            mainBoolean(
                () -> {
                  ViewGroup row =
                      activity.getWindow().getDecorView().findViewWithTag("record-" + target.id);
                  return row != null && row.getChildAt(0).getVisibility() == View.VISIBLE;
                }),
            "Record swipe did not reveal delete");
        screenshot("ui-record-delete-swipe.png");
        clickDescription("删除记录 SB-010");
        await(() -> activeWindowText("删除记录？"), "Missing record deletion dialog");
        require(activeWindowText("000EDITED-SN"), "Delete confirmation missing SN");
        screenshot("ui-record-delete-confirm.png");
        clickActiveWindow("取消");
        equal(3, uiStore.project(p).count);
        swipeRow("record-" + target.id, false, false);
        clickDescription("删除记录 SB-010");
        await(() -> activeWindowText("删除记录？"), "Missing second record confirmation");
        clickActiveWindow("删除记录");
        await(() -> uiStore.record(p, target.id) == null, "Record not deleted");
        requireVisible("已选 0 条");
        equal(2, uiStore.project(p).count);
        equal(11, uiStore.project(p).nextNumber);
        clickDescription("扫码");
        broadcast("AFTER-RECORD-DELETE");
        await(() -> uiStore.project(p).count == 3, "Scan after delete failed");
        equal("SB-011", uiStore.records(p, "AFTER-RECORD-DELETE", "all", 0).get(0).deviceName());
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private void filters() throws Exception {
    long p = store.createProject("筛选");
    Record first = store.register(p, "FIRST").record;
    store.register(p, "SECOND");
    equal(2, store.records(p, "", "today", 0).size());
    store.markPrinted(first.id);
    equal(1, store.records(p, "", "unprinted", 0).size());
    store.archive(p, true);
    rejects(() -> store.register(p, "BLOCKED"));
    equal(3, store.project(p).nextNumber);
    store.archive(p, false);
    equal("003", store.register(p, "RESTORED").record.deviceName());
    store.saveTemplate(p, LabelTemplate.defaults().toJson());
    equal(2, LabelTemplate.fromJson(store.project(p).template).lines.size());
  }

  private void parallel() throws Exception {
    long p = store.createProject("并发");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<?>> tasks = new ArrayList<>();
      for (int i = 0; i < 64; i++) {
        final String sn = "PARALLEL-" + i;
        tasks.add(
            pool.submit(
                () -> {
                  store.register(p, sn);
                  store.register(p, sn);
                }));
      }
      for (Future<?> task : tasks) task.get();
      List<Record> records = store.records(p, "", "all", 0);
      equal(64, records.size());
      equal(65, store.project(p).nextNumber);
      Set<Integer> numbers = new HashSet<>();
      for (Record r : records) numbers.add(r.number);
      for (int n = 1; n <= 64; n++) require(numbers.contains(n), "Missing number " + n);
    } finally {
      pool.shutdownNow();
    }
  }

  private void validation() throws Exception {
    long p = store.createProject("校验");
    rejects(() -> store.register(p, ""));
    rejects(() -> store.register(p, "\t  \r\n"));
    rejects(() -> store.register(p, "a\nb"));
    rejects(() -> store.register(p, new String(new char[513]).replace('\0', 'X')));
    equal(1, store.project(p).nextNumber);
    store.register(p, "TEST%_\\'");
    store.register(p, "TEST-OTHER");
    equal(1, store.records(p, "%_", "all", 0).size());
    equal(1, store.records(p, "\\'", "all", 0).size());
    equal("001", store.records(p, "001", "all", 0).get(0).deviceName());
    store.register(p, "abc");
    store.register(p, "ABC");
    equal(4, store.project(p).count);
  }

  private Record sample() {
    Record r = new Record();
    r.number = 1;
    r.sn = "00012345678901234567890";
    r.date = "2026-11-12";
    r.projectName = "项目&<设备>🙂";
    return r;
  }

  private void labels() throws Exception {
    LabelTemplate t = LabelTemplate.defaults();
    t.validate();
    equal("001", t.lines.get(0).resolve(sample()));
    require(t.lines.get(0).bold, "Equipment name must be bold");
    equal("SN00012345678901234567890", t.lines.get(1).resolve(sample()));
    t.lines.get(1).expression = "{项目}-{设备名称}/{SN}/{日期}/{DevicesID}";
    LabelTemplate restored = LabelTemplate.fromJson(t.toJson());
    equal(
        sample().projectName + "-001/" + sample().sn + "/2026-11-12/001",
        restored.lines.get(1).resolve(sample()));
    Record literal = sample();
    literal.sn = "{日期}-$\\";
    restored.lines.get(1).expression = "SN{SN}";
    equal("SN{日期}-$\\", restored.lines.get(1).resolve(literal));
    t.lines.get(0).expression = "{不存在}";
    rejects(t::validate);
    t = LabelTemplate.defaults();
    t.lines.get(0).x = 29;
    rejects(t::validate);
    t = LabelTemplate.defaults();
    t.lines.get(0).pt = Float.NaN;
    rejects(t::validate);
    t = LabelTemplate.defaults();
    t.lines.get(0).y = 19;
    rejects(t::validate);
  }

  private void xlsx() throws Exception {
    Record r = sample();
    Record formula = sample();
    formula.number = 2;
    formula.sn = "=1+2";
    List<Record> rows = Arrays.asList(r, formula);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    TableExporter.xlsx(out, rows, new boolean[] {true, true, true, true});
    Map<String, byte[]> entries = new HashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = zip.read(buffer)) != -1) bytes.write(buffer, 0, count);
        entries.put(e.getName(), bytes.toByteArray());
      }
    }
    equal(5, entries.size());
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    for (byte[] xml : entries.values())
      factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    Document sheet =
        factory
            .newDocumentBuilder()
            .parse(new ByteArrayInputStream(entries.get("xl/worksheets/sheet1.xml")));
    String ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    equal(3, sheet.getElementsByTagNameNS(ns, "row").getLength());
    equal(0, sheet.getElementsByTagNameNS(ns, "f").getLength());
    NodeList cells = sheet.getElementsByTagNameNS(ns, "c");
    equal(12, cells.getLength());
    equal("001", cells.item(4).getTextContent());
    equal(r.sn, cells.item(5).getTextContent());
    equal(r.projectName, cells.item(7).getTextContent());
    equal("=1+2", cells.item(9).getTextContent());
    for (int i = 0; i < cells.getLength(); i++)
      equal("inlineStr", cells.item(i).getAttributes().getNamedItem("t").getNodeValue());
    try (FileOutputStream file =
        new FileOutputStream(
            new File(getTargetContext().getFilesDir(), "export-verification.xlsx"))) {
      file.write(out.toByteArray());
    }
    out.reset();
    TableExporter.xlsx(out, rows, new boolean[] {false, true, false, false});
    require(out.size() > 100, "Single-column XLSX is empty");
  }

  private void csv() throws Exception {
    Record r = sample();
    r.projectName = "项目,\"甲\"\n第二行";
    Record formula = sample();
    formula.number = 2;
    formula.sn = "=SUM(1,2)";
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    TableExporter.csv(out, Arrays.asList(r, formula), new boolean[] {true, true, true, true});
    String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
    require(text.startsWith("\uFEFF"), "Missing UTF-8 BOM");
    require(text.contains("\"001\",\"00012345678901234567890\""), "Leading zero lost");
    require(text.contains("\"项目,\"\"甲\"\"\n第二行\""), "Invalid CSV quoting");
    require(text.contains("\"'=SUM(1,2)\""), "Missing CSV formula protection");
    try (FileOutputStream file =
        new FileOutputStream(
            new File(getTargetContext().getFilesDir(), "export-verification.csv"))) {
      file.write(out.toByteArray());
    }
  }

  private void printerDrawing() throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    runOnMainSync(
        () -> {
          LPAPI api = null;
          try {
            api =
                LPAPI.Factory.createInstance(
                    new LPAPI.Callback() {
                      public void onProgressInfo(IDzPrinter.ProgressInfo p, Object e) {}

                      public void onStateChange(
                          IDzPrinter.PrinterAddress a, IDzPrinter.PrinterState s) {}

                      public void onPrintProgress(
                          IDzPrinter.PrinterAddress a,
                          IDzPrinter.PrintData d,
                          IDzPrinter.PrintProgress p,
                          Object e) {}

                      public void onPrinterDiscovery(IDzPrinter.PrinterAddress a, Object e) {}
                    });
            require(api.startJob(30, 20, 0), "SDK rejected 30x20 job");
            for (LabelTemplate.Line l : LabelTemplate.defaults().lines) {
              api.setItemHorizontalAlignment(l.align);
              require(
                  api.drawTextRegular(
                      l.resolve(sample()),
                      l.x,
                      l.y,
                      l.width,
                      l.boxHeight(),
                      l.fontMm(),
                      l.bold ? 1 : 0),
                  "SDK rejected label text");
            }
            api.endJob();
            List<Bitmap> pages = api.getJobPages();
            require(pages != null && !pages.isEmpty(), "SDK produced no bitmap");
            Bitmap bitmap = pages.get(0);
            require(
                Math.abs((double) bitmap.getWidth() / bitmap.getHeight() - 1.5) < 0.03,
                "Wrong label aspect ratio");
            try (FileOutputStream file =
                new FileOutputStream(
                    new File(getTargetContext().getFilesDir(), "sdk-label-verification.png"))) {
              bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
            }
          } catch (Throwable error) {
            failure.set(error);
          } finally {
            if (api != null) {
              api.abortJob();
              api.quit();
            }
          }
        });
    if (failure.get() != null)
      throw new AssertionError("Printer SDK drawing failed", failure.get());
  }

  private void projectNaming() throws Exception {
    long p = store.createProject("命名甲"), other = store.createProject("命名乙");
    Record first = store.register(p, "000ABC").record;
    Record second = store.register(p, "NEXT-ABC").record;
    store.register(p, "THIRD-ABC");
    store.markPrinted(first.id);
    store.register(other, "UNTOUCHED");
    store.saveNaming(p, "SB-", 4, 2);
    List<Record> rows = store.records(p, "", "all", 0);
    equal("SB-0002", rows.get(2).deviceName());
    equal("SB-0004", rows.get(0).deviceName());
    equal(first.id, rows.get(2).id);
    equal(first.sn, rows.get(2).sn);
    equal(first.createdAt, rows.get(2).createdAt);
    equal(1, rows.get(2).printed);
    equal(5, store.project(p).nextNumber);
    equal(2, store.project(p).startNumber);
    equal(1, store.records(p, "SB-0002", "all", 0).size());
    equal("SB-0002", TableExporter.value(rows.get(2), 0));
    equal("SB-0002", LabelTemplate.defaults().lines.get(0).resolve(rows.get(2)));
    PrintRequest request = PrintRequest.records(rows, p, 2, 2, 1, true);
    equal("SB-0002", request.record(0).deviceName());
    equal("SB-0003", request.record(1).deviceName());
    equal("SB-0005", store.register(p, "FOURTH").record.deviceName());
    require(store.register(p, second.sn).duplicate, "Duplicate changed after renaming");
    equal(6, store.project(p).nextNumber);
    equal("001", store.records(other, "", "all", 1).get(0).deviceName());
    store
        .getWritableDatabase()
        .execSQL(
            "CREATE TRIGGER fail_rename BEFORE UPDATE OF number ON records WHEN OLD.id='"
                + second.id
                + "' AND NEW.number>=0 BEGIN SELECT RAISE(ABORT,'rollback naming'); END");
    boolean failed = false;
    try {
      store.saveNaming(p, "FAIL-", 2, 0);
    } catch (android.database.sqlite.SQLiteException expected) {
      failed = true;
    } finally {
      store.getWritableDatabase().execSQL("DROP TRIGGER fail_rename");
    }
    require(failed, "Rename failure was not surfaced");
    equal("SB-", store.project(p).namePrefix);
    equal(6, store.project(p).nextNumber);
    equal("SB-0002", store.recordsByIds(Arrays.asList(first.id)).get(0).deviceName());
    store.saveNaming(p, "机柜%_-", 2, 0);
    equal("机柜%_-00", store.recordsByIds(Arrays.asList(first.id)).get(0).deviceName());
    equal(1, store.records(p, "机柜%_-00", "all", 0).size());
    equal("机柜%_-04", store.register(p, "FIFTH").record.deviceName());
    equal("SB-0002", request.record(0).deviceName());
    rejects(() -> store.saveNaming(p, "A", 0, 1));
    rejects(() -> store.saveNaming(p, "A", 11, 1));
    rejects(() -> store.saveNaming(p, "A", 3, -1));
    rejects(() -> store.saveNaming(p, "BAD\nPREFIX", 3, 1));
    rejects(() -> store.saveNaming(p, "XXXXXXXXXXXXXXXXXXXXX", 3, 1));
    rejects(() -> store.saveNaming(p, "A", 3, Integer.MAX_VALUE - 2));
    equal("机柜%_-", store.project(p).namePrefix);
    equal(5, store.project(p).count);
    equal(5, store.project(p).nextNumber);
    store.close();
    store = new RegistryStore(getTargetContext(), database);
    equal(0, store.project(p).startNumber);
    equal(2, store.project(p).numberDigits);
    equal("机柜%_-05", store.register(p, "REOPENED").record.deviceName());
    equal("X-1000", Models.deviceName("X-", 3, 1000));
    long empty = store.createProject("空项目命名");
    store.saveNaming(empty, "Z", 1, 0);
    equal("Z0", store.register(empty, "FIRST").record.deviceName());
  }

  private void namingMigration() throws Exception {
    String name = "migration-" + UUID.randomUUID() + ".db";
    File file = getTargetContext().getDatabasePath(name);
    try {
      try (android.database.sqlite.SQLiteDatabase old =
          android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)) {
        old.execSQL(
            "CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL"
                + " UNIQUE,archived INTEGER NOT NULL DEFAULT 0,next_number INTEGER NOT NULL DEFAULT"
                + " 1,template TEXT NOT NULL DEFAULT '')");
        old.execSQL(
            "CREATE TABLE records(id TEXT PRIMARY KEY,project_id INTEGER NOT NULL REFERENCES"
                + " projects(id),number INTEGER NOT NULL,sn TEXT NOT NULL,date TEXT NOT"
                + " NULL,created_at TEXT NOT NULL,printed INTEGER NOT NULL DEFAULT"
                + " 0,UNIQUE(project_id,sn),UNIQUE(project_id,number))");
        old.execSQL("CREATE INDEX records_project_date ON records(project_id,date)");
        old.execSQL(
            "INSERT INTO projects(id,name,next_number,template) VALUES(1,'旧版项目',3,?)",
            new Object[] {LabelTemplate.defaults().toJson()});
        old.execSQL(
            "INSERT INTO records VALUES('old-1',1,1,'00000001','2026-11-12','2026-11-12"
                + " 08:00:00',2)");
        old.execSQL(
            "INSERT INTO records VALUES('old-2',1,2,'00000002','2026-11-12','2026-11-12"
                + " 08:01:00',0)");
        old.setVersion(1);
      }
      try (RegistryStore migrated = new RegistryStore(getTargetContext(), name)) {
        equal(4, migrated.getReadableDatabase().getVersion());
        equal(2, migrated.project(1).count);
        equal(3, migrated.project(1).nextNumber);
        equal(3, migrated.project(1).numberDigits);
        equal("", migrated.project(1).namePrefix);
        equal("001", migrated.recordsByIds(Arrays.asList("old-1")).get(0).deviceName());
        equal(2, migrated.recordsByIds(Arrays.asList("old-1")).get(0).printed);
        equal(LabelTemplate.defaults().toJson(), migrated.project(1).template);
        migrated.saveNaming(1, "OLD-", 3, 10);
        equal("OLD-010", migrated.recordsByIds(Arrays.asList("old-1")).get(0).deviceName());
        equal("00000001", migrated.recordsByIds(Arrays.asList("old-1")).get(0).sn);
        equal("OLD-012", migrated.register(1, "NEW").record.deviceName());
      }
    } finally {
      getTargetContext().deleteDatabase(name);
    }
  }

  private void namingUi() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long p = uiStore.createProject("命名设置验证-" + SystemClock.elapsedRealtime());
      Record first = uiStore.register(p, "000NAMING-ONE").record;
      uiStore.register(p, "000NAMING-TWO");
      uiStore.markPrinted(first.id);
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", p)
          .putInt("scanMode", 0)
          .putBoolean("feedback", false)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No naming activity focus");
        clickDescription("设置");
        clickDescription("设备编号规则");
        requireVisible("名称前缀");
        setPrintInput("可留空，例如 SB-", "SB-");
        setPrintInput("编号位数（1–10）", "3");
        setPrintInput("起始编号（可为 0）", "0");
        requireVisible("SB-000、SB-001、SB-002");
        requireVisible("已有 2 条记录 · 下次扫码：SB-002");
        screenshot("ui-device-naming-preview.png");
        setPrintInput("编号位数（1–10）", "0");
        require(
            !mainBoolean(() -> findText(activity.getWindow().getDecorView(), "保存并应用").isEnabled()),
            "Invalid naming could be saved");
        setPrintInput("编号位数（1–10）", "3");
        clickText("保存并应用");
        requireVisible("下个 SB-002");
        screenshot("ui-device-naming-settings.png");
        equal("SB-000", uiStore.recordsByIds(Arrays.asList(first.id)).get(0).deviceName());
        equal(1, uiStore.recordsByIds(Arrays.asList(first.id)).get(0).printed);
        clickDescription("记录");
        requireVisible("设备名称（DevicesID）  SB-000");
        requireVisible("设备名称（DevicesID）  SB-001");
        setPrintInput("搜索 SN / 设备名称", "SB-000");
        requireVisible("000NAMING-ONE");
        require(
            !mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "000NAMING-TWO")),
            "Prefix name search did not filter");
        clickDescription("扫码");
        broadcast("000NAMING-THREE");
        await(() -> uiStore.project(p).count == 3, "Scan after renaming failed");
        equal("SB-002", uiStore.records(p, "", "all", 1).get(0).deviceName());
        clickDescription("标签");
        clickText("标签打印");
        requireVisible("1 张 × 1 份 = 1 张");
        setPrintInput("起始设备号", "0");
        requireVisible("设备 SB-000 · 1/1");
        screenshot("ui-device-naming-print.png");
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private void projectDeletion() throws Exception {
    long a = store.createProject("删除甲"), b = store.createProject("保留乙");
    Record deleted = store.register(a, "DELETE-SN").record;
    store.saveTemplate(a, LabelTemplate.defaults().toJson());
    store.register(b, "KEEP-SN");
    store.archive(a, true);
    store
        .getWritableDatabase()
        .execSQL(
            "CREATE TRIGGER reject_delete BEFORE DELETE ON projects WHEN OLD.id="
                + a
                + " BEGIN SELECT RAISE(ABORT,'test rollback'); END");
    boolean rejected = false;
    try {
      store.deleteProject(a);
    } catch (android.database.sqlite.SQLiteException expected) {
      rejected = true;
    } finally {
      store.getWritableDatabase().execSQL("DROP TRIGGER reject_delete");
    }
    require(rejected, "Delete failure was not reported");
    equal(1, store.records(a, "", "all", 0).size());
    require(store.project(a) != null, "Failed transaction removed project");
    equal(b, store.deleteProject(a));
    equal(null, store.project(a));
    equal(0, store.recordsByIds(Arrays.asList(deleted.id)).size());
    try (android.database.Cursor rows =
        store
            .getReadableDatabase()
            .rawQuery(
                "SELECT COUNT(*) FROM records WHERE project_id=?",
                new String[] {String.valueOf(a)})) {
      rows.moveToFirst();
      equal(0, rows.getInt(0));
    }
    equal(1, store.project(b).count);
    equal(2, store.project(b).nextNumber);
    rejects(() -> store.deleteProject(a));
    equal("002", store.register(b, "KEEP-NEXT").record.deviceName());
    long fallback = store.deleteProject(b);
    require(fallback != a && fallback != b, "Deleted project identity was reused");
    equal(1, store.projects().size());
    equal("默认项目", store.project(fallback).name);
    equal(0, store.project(fallback).count);
    equal(1, store.project(fallback).nextNumber);
    store.close();
    store = new RegistryStore(getTargetContext(), database);
    equal(fallback, store.projects().get(0).id);
    equal("001", store.register(fallback, "FRESH").record.deviceName());
  }

  private void projectDeleteUi() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      String suffix = String.valueOf(SystemClock.elapsedRealtime());
      String keep = "保留项目-" + suffix, other = "待删归档-" + suffix, current = "待删当前-" + suffix;
      long c = uiStore.createProject(keep),
          b = uiStore.createProject(other),
          a = uiStore.createProject(current);
      uiStore.register(c, "KEEP-ONE");
      uiStore.register(c, "KEEP-TWO");
      uiStore.register(a, "DELETE-ONE");
      uiStore.register(a, "DELETE-TWO");
      uiStore.register(b, "ARCHIVED");
      uiStore.archive(b, true);
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", a)
          .putInt("scanMode", 0)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No project-delete focus");
        clickDescription("记录");
        clickText("全选");
        clickDescription("项目");
        setPrintInput("搜索项目名称或编号", current);
        swipeProject(a, true, false);
        require(
            mainBoolean(
                () ->
                    projectDeleteButton(a) != null
                        && projectDeleteButton(a).getVisibility() == View.VISIBLE),
            "Right swipe did not reveal delete");
        screenshot("ui-project-delete-right.png");
        clickDescription("删除项目 " + current);
        await(() -> activeWindowText("删除项目？"), "Missing delete confirmation");
        require(activeWindowText("2 条登记记录"), "Confirmation omitted affected record count");
        screenshot("ui-project-delete-confirm.png");
        clickActiveWindow("取消");
        await(
            () -> mainBoolean(() -> activity.hasWindowFocus()),
            "Cancel did not return to projects");
        equal(2, uiStore.project(a).count);
        swipeProject(a, false, false);
        require(
            mainBoolean(
                () ->
                    projectDeleteButton(a) != null
                        && projectDeleteButton(a).getVisibility() == View.VISIBLE),
            "Left swipe did not reveal delete");
        screenshot("ui-project-delete-left.png");
        tapProjectContent(a);
        await(
            () ->
                mainBoolean(
                    () ->
                        projectDeleteButton(a) != null
                            && projectDeleteButton(a).getVisibility() == View.INVISIBLE),
            "Tap did not close revealed action");
        swipeProject(a, true, true);
        require(
            mainBoolean(
                () ->
                    projectDeleteButton(a) != null
                        && projectDeleteButton(a).getVisibility() == View.INVISIBLE),
            "Vertical scroll revealed delete");
        setPrintInput("搜索项目名称或编号", other);
        swipeProject(b, true, false);
        clickDescription("删除项目 " + other);
        await(() -> activeWindowText("删除项目？"), "Missing archived project confirmation");
        clickActiveWindow("删除项目");
        await(() -> uiStore.project(b) == null, "Archived project not deleted");
        equal(a, getTargetContext().getSharedPreferences("settings", 0).getLong("project", -1));
        equal(2, uiStore.project(a).count);
        setPrintInput("搜索项目名称或编号", current);
        swipeProject(a, true, false);
        clickDescription("删除项目 " + current);
        await(() -> activeWindowText("删除项目？"), "Missing current project confirmation");
        clickActiveWindow("删除项目");
        await(() -> uiStore.project(a) == null, "Current project not deleted");
        equal(c, getTargetContext().getSharedPreferences("settings", 0).getLong("project", -1));
        clickDescription("记录");
        requireVisible("已选 0 条");
        requireVisible("KEEP-TWO");
        clickDescription("扫码");
        requireVisible("SN  KEEP-TWO");
        equal(2, uiStore.project(c).count);
        equal(3, uiStore.project(c).nextNumber);
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private View projectDeleteButton(long id) {
    ViewGroup row = activity.getWindow().getDecorView().findViewWithTag("project-" + id);
    return row == null ? null : row.getChildAt(0);
  }

  private int[] projectBounds(long id) {
    return rowBounds("project-" + id);
  }

  private int[] rowBounds(String tag) {
    int[] bounds = new int[4];
    runOnMainSync(
        () -> {
          View row = activity.getWindow().getDecorView().findViewWithTag(tag);
          if (row == null) return;
          int[] location = new int[2];
          row.getLocationOnScreen(location);
          bounds[0] = location[0];
          bounds[1] = location[1];
          bounds[2] = row.getWidth();
          bounds[3] = row.getHeight();
        });
    require(bounds[2] > 0, "Missing project swipe row");
    return bounds;
  }

  private void swipeProject(long id, boolean right, boolean vertical) {
    swipeRow("project-" + id, right, vertical);
  }

  private void swipeRow(String tag, boolean right, boolean vertical) {
    int[] b = rowBounds(tag);
    float fromX = b[0] + b[2] * (right ? 0.25f : 0.75f);
    float fromY = b[1] + b[3] * 0.45f;
    float toX = vertical ? fromX : b[0] + b[2] * (right ? 0.75f : 0.25f);
    float toY = vertical ? fromY + b[3] * 0.6f : fromY;
    long start = SystemClock.uptimeMillis();
    for (int i = 0; i <= 10; i++) {
      int action =
          i == 0
              ? android.view.MotionEvent.ACTION_DOWN
              : i == 10 ? android.view.MotionEvent.ACTION_UP : android.view.MotionEvent.ACTION_MOVE;
      float fraction = i / 10f;
      android.view.MotionEvent event =
          android.view.MotionEvent.obtain(
              start,
              SystemClock.uptimeMillis(),
              action,
              fromX + (toX - fromX) * fraction,
              fromY + (toY - fromY) * fraction,
              0);
      sendPointerSync(event);
      event.recycle();
      SystemClock.sleep(24);
    }
    waitForIdleSync();
    SystemClock.sleep(200);
  }

  private void tapProjectContent(long id) {
    int[] b = projectBounds(id);
    long now = SystemClock.uptimeMillis();
    for (int action :
        new int[] {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
      android.view.MotionEvent event =
          android.view.MotionEvent.obtain(
              now, SystemClock.uptimeMillis(), action, b[0] + b[2] / 2f, b[1] + b[3] / 4f, 0);
      sendPointerSync(event);
      event.recycle();
    }
    waitForIdleSync();
  }

  private boolean activeWindowText(String text) {
    android.view.accessibility.AccessibilityNodeInfo root =
        getUiAutomation().getRootInActiveWindow();
    return root != null && !root.findAccessibilityNodeInfosByText(text).isEmpty();
  }

  private void clickActiveWindow(String text) {
    android.view.accessibility.AccessibilityNodeInfo root =
        getUiAutomation().getRootInActiveWindow();
    require(root != null, "No active dialog");
    for (android.view.accessibility.AccessibilityNodeInfo node :
        root.findAccessibilityNodeInfosByText(text)) {
      if (text.contentEquals(node.getText())
          && node.isClickable()
          && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
        waitForIdleSync();
        return;
      }
    }
    throw new AssertionError("Missing dialog button " + text);
  }

  private void printRequests() throws Exception {
    long p = store.createProject("打印选择");
    List<Record> rows = new ArrayList<>();
    for (int i = 1; i <= 5; i++) rows.add(store.register(p, "SN-" + i).record);
    List<Record> reversed = new ArrayList<>(rows);
    java.util.Collections.reverse(reversed);
    PrintRequest request = PrintRequest.records(reversed, p, 2, 3, 2, true);
    equal(3, request.size());
    equal(6, request.total);
    equal(2, request.copies);
    equal("002", request.record(0).deviceName());
    equal("004", request.record(2).deviceName());
    PrintRequest selected =
        PrintRequest.records(Arrays.asList(rows.get(4), rows.get(1)), p, 0, 1, 3, false);
    equal(1, selected.size());
    equal("002", selected.record(0).deviceName());
    equal(3, selected.total);
    rejects(() -> PrintRequest.records(rows, p, 1, 0, 1, true));
    rejects(() -> PrintRequest.records(rows, p, 1, 1, 100, true));
    rejects(() -> PrintRequest.records(rows, p, 1, 1, 0, true));
    rejects(() -> PrintRequest.records(rows, p, 5, 2, 1, true));
    rejects(() -> PrintRequest.records(rows, p, 6, 1, 1, true));
    rejects(() -> PrintRequest.records(Arrays.asList(rows.get(0), rows.get(0)), p, 1, 1, 1, true));
    rejects(() -> PrintRequest.records(rows, p + 1, 1, 1, 1, true));
    rejects(() -> PrintRequest.records(new ArrayList<>(), p, 0, 1, 1, false));
    Record original = rows.get(1);
    String id = original.id;
    original.sn = "CHANGED";
    equal("SN-2", request.record(0).sn);
    Record copy = request.record(0);
    copy.id = "CHANGED-ID";
    equal(id, request.record(0).id);
    PrintRequest sample = PrintRequest.sample(rows.get(0));
    equal(1, sample.size());
    equal(1, sample.copies);
    equal(1, sample.total);
    require(sample.testSample, "Sample flag missing");
    equal(null, sample.record(0).id);
  }

  private static final class FakePrintTransport implements PrinterController.JobTransport {
    final List<Record> submitted = java.util.Collections.synchronizedList(new ArrayList<>());
    volatile int key;

    public boolean connected() {
      return true;
    }

    public void submit(Record record, LabelTemplate template, int key) {
      this.key = key;
      submitted.add(record);
    }

    public void cancel() {}

    public void close() {}
  }

  private void printQueue() throws Exception {
    long p = store.createProject("打印队列");
    Record a = store.register(p, "QUEUE-A").record;
    Record b = store.register(p, "QUEUE-B").record;
    Record untouched = store.register(p, "QUEUE-C").record;
    FakePrintTransport transport = new FakePrintTransport();
    AtomicReference<PrinterController> ref = new AtomicReference<>();
    runOnMainSync(
        () -> {
          PrinterController controller = new PrinterController(store, () -> {}, transport);
          ref.set(controller);
          controller.start(
              PrintRequest.records(Arrays.asList(b, a), p, 1, 2, 3, true),
              LabelTemplate.defaults());
        });
    PrinterController controller = ref.get();
    try {
      for (int i = 0; i < 6; i++) {
        final int index = i;
        await(() -> transport.submitted.size() == index + 1, "Missing physical copy " + index);
        equal(i < 3 ? a.id : b.id, transport.submitted.get(i).id);
        int key = transport.key;
        runOnMainSync(
            () -> {
              controller.handleProgress(key - 1, IDzPrinter.PrintProgress.Success);
              controller.handleProgress(key, IDzPrinter.PrintProgress.DataEnded);
              equal(index, controller.completed);
              controller.handleProgress(key, IDzPrinter.PrintProgress.Success);
              controller.handleProgress(key, IDzPrinter.PrintProgress.Success);
              equal(index + 1, controller.completed);
            });
      }
      equal(6, controller.total());
      require(!mainBoolean(controller::active), "Finished task remains active");
      List<Record> result = store.records(p, "", "all", 0);
      equal(0, result.get(0).printed);
      equal(3, result.get(1).printed);
      equal(3, result.get(2).printed);
      int next = store.project(p).nextNumber;
      runOnMainSync(() -> controller.start(PrintRequest.sample(a), LabelTemplate.defaults()));
      equal(7, transport.submitted.size());
      equal(null, transport.submitted.get(6).id);
      int sampleKey = transport.key;
      runOnMainSync(() -> controller.handleProgress(sampleKey, IDzPrinter.PrintProgress.Success));
      SystemClock.sleep(350);
      equal(7, transport.submitted.size());
      equal(next, store.project(p).nextNumber);
      equal(3, store.project(p).count);
      result = store.records(p, "", "all", 0);
      equal(3, result.get(2).printed);
      equal(0, result.get(0).printed);
      runOnMainSync(
          () -> {
            controller.start(
                PrintRequest.records(Arrays.asList(untouched), p, 3, 1, 1, true),
                LabelTemplate.defaults());
            int failedKey = transport.key;
            controller.handleProgress(failedKey, IDzPrinter.PrintProgress.Failed);
            require(controller.paused, "Failure did not pause");
            equal(0, controller.completed);
            controller.togglePause();
            require(transport.key != failedKey, "Retry reused callback key");
            controller.handleProgress(failedKey, IDzPrinter.PrintProgress.Success);
            equal(0, controller.completed);
            int canceledKey = transport.key;
            controller.cancel();
            controller.handleProgress(canceledKey, IDzPrinter.PrintProgress.Success);
            equal(0, controller.completed);
          });
      equal(0, store.records(p, "", "all", 0).get(0).printed);
    } finally {
      runOnMainSync(controller::close);
    }
  }

  private void printUi() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long p = uiStore.createProject("标签打印验证-" + SystemClock.elapsedRealtime());
      for (int i = 1; i <= 5; i++) uiStore.register(p, "00261112000" + i);
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", p)
          .putInt("scanMode", 0)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No print activity focus");
        clickDescription("标签");
        clickText("标签打印");
        requireVisible("1 张 × 1 份 = 1 张");
        requireVisible("测试样稿 1 张");
        setPrintInput("起始设备号", "2");
        setPrintInput("标签张数", "3");
        setPrintInput("每张份数", "2");
        requireVisible("3 张 × 2 份 = 6 张");
        requireVisible("打印 6 张");
        requireVisible("本次设备 002 至 004 · 3 台（按编号顺序）");
        screenshot("ui-print-plan.png");
        setPrintInput("标签张数", "99");
        require(
            !mainBoolean(() -> findText(activity.getWindow().getDecorView(), "打印标签").isEnabled()),
            "Too many labels allowed");
        setPrintInput("标签张数", "0");
        require(
            !mainBoolean(() -> findText(activity.getWindow().getDecorView(), "打印标签").isEnabled()),
            "Zero labels allowed");
        setPrintInput("标签张数", "3");
        clickText("切换预览 ›");
        requireVisible("设备 003 · 2/3");
        screenshot("ui-print-preview.png");
        clickText("返回");
        clickDescription("记录");
        clickText("全选");
        clickText("打印 5 条");
        requireVisible("5 张 × 1 份 = 5 张");
        setPrintInput("标签张数", "2");
        setPrintInput("每张份数", "3");
        requireVisible("2 张 × 3 份 = 6 张");
        requireVisible("本次设备 001 至 002 · 2 台（按编号顺序）");
        screenshot("ui-print-selected.png");
        clickDescription("标签");
        clickText("标签打印");
        requireVisible("1 张 × 1 份 = 1 张");
        equal(5, uiStore.project(p).count);
        for (Record record : uiStore.records(p, "", "all", 0)) equal(0, record.printed);
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
      long empty = uiStore.createProject("空项目测试样稿-" + SystemClock.elapsedRealtime());
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", empty)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No empty project focus");
        clickDescription("标签");
        clickText("标签打印");
        requireVisible("示例设备 001");
        requireVisible("测试样稿 1 张");
        require(
            !mainBoolean(() -> findText(activity.getWindow().getDecorView(), "打印标签").isEnabled()),
            "Empty project allowed record printing");
        screenshot("ui-print-empty-sample.png");
        equal(0, uiStore.project(empty).count);
        equal(1, uiStore.project(empty).nextNumber);
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private void setPrintInput(String hint, String value) {
    if (hint.equals("搜索项目名称或编号")
        && mainBoolean(
            () ->
                activity
                        .getWindow()
                        .getDecorView()
                        .findViewWithTag("project-search-row")
                        .getVisibility()
                    == View.GONE)) clickDescription("搜索项目");
    if (hint.equals("搜索 SN / 设备名称")
        && mainBoolean(
            () ->
                activity
                        .getWindow()
                        .getDecorView()
                        .findViewWithTag("record-search-row")
                        .getVisibility()
                    == View.GONE)) clickDescription("搜索记录");
    if (hint.startsWith("搜索")) {
      String tag = hint.contains("项目") ? "project-search-row" : "record-search-row";
      if (mainBoolean(
          () ->
              activity.getWindow().getDecorView().findViewWithTag(tag).getVisibility()
                  != View.VISIBLE)) clickDescription(hint.contains("项目") ? "搜索项目" : "搜索记录");
    }
    runOnMainSync(
        () -> {
          android.widget.EditText edit = findInput(activity.getWindow().getDecorView(), hint);
          require(edit != null, "Missing print field " + hint);
          edit.setText(value);
        });
    waitForIdleSync();
  }

  private void ui() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long project = uiStore.createProject("界面验证-" + SystemClock.elapsedRealtime());
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", project)
          .putInt("scanMode", 0)
          .putBoolean("feedback", false)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "Activity never gained focus");
        broadcast("00000012345678901234");
        await(() -> uiStore.project(project).count == 1, "Broadcast scan was not saved");
        await(
            () ->
                mainBoolean(
                    () -> hasText(activity.getWindow().getDecorView(), "00000012345678901234")),
            "Successful scan did not retain the recognized SN");
        broadcast("00000012345678901234");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "SN重复")),
            "Missing inline duplicate feedback");
        equal(1, uiStore.project(project).count);
        equal(2, uiStore.project(project).nextNumber);
        screenshot("ui-duplicate.png");
        clickText("暂停登记");
        broadcast("PAUSED-IGNORED");
        SystemClock.sleep(200);
        equal(1, uiStore.project(project).count);
        clickText("继续登记");
        broadcast("SECOND-DEVICE");
        await(() -> uiStore.project(project).count == 2, "Resume did not accept scan");
        await(
            () ->
                mainBoolean(
                    () ->
                        hasText(activity.getWindow().getDecorView(), "SECOND-DEVICE")
                            && !hasText(activity.getWindow().getDecorView(), "SN重复")),
            "Next successful scan did not reset the duplicate state");
        screenshot("ui-scan-saved.png");
        clickDescription("标签");
        screenshot("ui-label.png");
        require(
            mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "内容拼接")),
            "Label editor not displayed");
        clickDescription("记录");
        screenshot("ui-records.png");
        broadcast("WRONG-TAB-IGNORED");
        SystemClock.sleep(200);
        equal(2, uiStore.project(project).count);
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
        getTargetContext()
            .getSharedPreferences("settings", 0)
            .edit()
            .putInt("scanMode", 1)
            .commit();
        activity =
            (MainActivity)
                startActivitySync(
                    new Intent(getTargetContext(), MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        await(
            () -> mainBoolean(() -> activity.hasWindowFocus()),
            "Focus activity never gained focus");
        sendStringSync("FOCUS1234");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);
        await(() -> uiStore.project(project).count == 3, "Focus-input scan was not saved");
        equal("FOCUS1234", uiStore.records(project, "", "all", 1).get(0).sn);
        equal(4, uiStore.project(project).nextNumber);
        require(
            !mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "SN重复")),
            "Enter registered the same scan twice");
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private void launcherEntry() throws Exception {
    Intent launch =
        new Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(getTargetContext().getPackageName());
    List<android.content.pm.ResolveInfo> entries =
        getTargetContext().getPackageManager().queryIntentActivities(launch, 0);
    equal(1, entries.size());
    equal(MainActivity.class.getName(), entries.get(0).activityInfo.name);
    android.graphics.drawable.Drawable icon =
        getTargetContext().getApplicationInfo().loadIcon(getTargetContext().getPackageManager());
    require(
        icon instanceof android.graphics.drawable.AdaptiveIconDrawable,
        "Launcher icon is not an adaptive icon");
    android.content.pm.ShortcutInfo shortcut = HomeShortcut.info(getTargetContext());
    equal(MainActivity.class.getName(), shortcut.getIntent().getComponent().getClassName());
    equal(Intent.ACTION_MAIN, shortcut.getIntent().getAction());
    equal(getTargetContext().getString(R.string.app_name), shortcut.getShortLabel().toString());
    Bitmap bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888);
    icon.setBounds(0, 0, 144, 144);
    icon.draw(new android.graphics.Canvas(bitmap));
    try (FileOutputStream file =
        new FileOutputStream(new File(getTargetContext().getFilesDir(), "launcher-icon.png"))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
    } finally {
      bitmap.recycle();
    }
  }

  private void notice() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long project = uiStore.createProject("提示验证-" + SystemClock.elapsedRealtime());
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", project)
          .putInt("scanMode", 0)
          .putBoolean("feedback", false)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "Activity never gained focus");
        broadcast("NOTICE-ONE");
        await(
            () -> mainBoolean(() -> findText(activity.getWindow().getDecorView(), "录入成功") != null),
            "Missing registration success notice");
        equal(1, uiStore.project(project).count);
        waitForIdleSync();
        runOnMainSync(
            () -> {
              View view = findText(activity.getWindow().getDecorView(), "录入成功");
              require(view != null, "Success notice disappeared early");
              int[] location = new int[2];
              view.getLocationOnScreen(location);
              float density = getTargetContext().getResources().getDisplayMetrics().density;
              require(
                  location[0] + view.getWidth() / 2
                      > activity.getWindow().getDecorView().getWidth() / 2,
                  "Success notice is not at the right edge");
              require(location[1] < 120 * density, "Success notice is not near the top");
              require(activity.hasWindowFocus(), "Notice took scanner focus");
            });
        screenshot("ui-success-notice.png");
        // A second broadcast while the first notice remains visible must still be accepted.
        broadcast("NOTICE-TWO");
        await(() -> uiStore.project(project).count == 2, "Success notice blocked next scan");
        broadcast("NOTICE-TWO");
        await(
            () ->
                mainBoolean(
                    () ->
                        hasText(activity.getWindow().getDecorView(), "SN重复")
                            && !hasText(activity.getWindow().getDecorView(), "录入成功")),
            "Duplicate showed a success notice");
        equal(2, uiStore.project(project).count);
        broadcast("NOTICE-THREE");
        await(() -> uiStore.project(project).count == 3, "Next scan was not saved");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "录入成功")),
            "Next success did not refresh notice");
        await(
            () -> mainBoolean(() -> !hasText(activity.getWindow().getDecorView(), "录入成功")),
            "Success notice did not auto-dismiss");
        equal(4, uiStore.project(project).nextNumber);
        require(
            mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "NOTICE-THREE")),
            "Recognized SN disappeared with the success notice");
        screenshot("ui-success-dismissed.png");
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private void compactLayout() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long project = uiStore.createProject("东区设备入库-" + SystemClock.elapsedRealtime());
      for (int i = 126; i <= 128; i++) uiStore.register(project, "002611120" + i);
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", project)
          .putInt("scanMode", 0)
          .putBoolean("feedback", false)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No compact activity focus");
        requireVisible("等待扫码…");
        requireVisible("SN  002611120128");
        requireVisible("手动录入");
        requireVisible("暂停登记");
        screenshot("ui-compact-scan.png");
        clickDescription("记录");
        requireVisible("002611120128");
        requireVisible("002611120127");
        requireVisible("002611120126");
        requireVisible("导出表格");
        clickText("全选");
        require(
            mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "已选 3 条")),
            "Select all no longer selects visible records");
        screenshot("ui-compact-records.png");
        clickText("清空");
        require(
            mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "已选 0 条")),
            "Clear selection failed");
        clickDescription("标签");
        requireVisible("内容拼接");
        requireVisible("第 2 行");
        requireVisible("保存模板");
        screenshot("ui-compact-label.png");
        clickDescription("设置");
        requireVisible("搜索并连接打印机");
        runOnMainSync(
            () -> {
              View saved = findText(activity.getWindow().getDecorView(), "数据保存");
              saved.requestRectangleOnScreen(
                  new android.graphics.Rect(0, 0, saved.getWidth(), saved.getHeight()), true);
            });
        waitForIdleSync();
        requireVisible("数据保存");
        requireVisible("搜索并连接打印机");
        screenshot("ui-compact-settings.png");
        clickDescription("项目");
        screenshot("ui-compact-projects.png");
        clickDescription("搜索项目");
        clickDescription("搜索项目");
        runOnMainSync(
            () -> {
              android.widget.EditText search =
                  findInput(activity.getWindow().getDecorView(), "搜索项目名称或编号");
              require(search != null, "Project search missing");
              search.setText("NO-MATCH-PROJECT");
              require(
                  !hasText(activity.getWindow().getDecorView(), uiStore.project(project).name),
                  "Project search did not filter");
              search.setText(uiStore.project(project).name);
              require(
                  hasText(activity.getWindow().getDecorView(), uiStore.project(project).name),
                  "Project search did not restore matching project");
            });
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private void searchAndFixedActions() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long p = uiStore.createProject("简洁界面验证-" + SystemClock.elapsedRealtime());
      Record first = null;
      for (int i = 1; i <= 8; i++) {
        Record r = uiStore.register(p, "UI-SEARCH-00" + i).record;
        if (i == 1) first = r;
      }
      uiStore.markPrinted(first.id);
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", p)
          .putInt("scanMode", 0)
          .putBoolean("feedback", false)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No fixed-actions focus");
        fixedFooter("暂停登记", "手动录入");
        clickDescription("项目");
        requireSearchHidden("project-search-row");
        fixedFooter("＋  新建项目");
        screenshot("ui-project-search-collapsed.png");
        clickDescription("搜索项目");
        require(
            mainBoolean(
                () -> findInput(activity.getWindow().getDecorView(), "搜索项目名称或编号").hasFocus()),
            "Search did not focus");
        setPrintInput("搜索项目名称或编号", "NO-MATCH-UI-QUERY");
        requireVisible("没有匹配的项目");
        screenshot("ui-project-search-expanded.png");
        clickDescription("取消项目搜索");
        requireSearchHidden("project-search-row");
        require(
            mainBoolean(
                () -> hasText(activity.getWindow().getDecorView(), uiStore.project(p).name)),
            "Cancel did not restore project list");
        clickDescription("记录");
        requireSearchHidden("record-search-row");
        clickText("全选");
        requireVisible("已选 8 条");
        fixedFooter("全选", "清空", "导出表格", "打印 8 条");
        runOnMainSync(
            () -> {
              View root = activity.getWindow().getDecorView();
              View all = findText(root, "全选"), clear = findText(root, "清空");
              require(all.getParent() == clear.getParent(), "Selection actions separated");
              int[] a = new int[2], b = new int[2];
              all.getLocationOnScreen(a);
              clear.getLocationOnScreen(b);
              require(Math.abs(a[1] - b[1]) <= 2, "Selection actions not on same row");
              require(b[0] - a[0] - all.getWidth() <= 32, "Selection actions too far apart");
            });
        screenshot("ui-record-fixed-selection.png");
        clickText("清空");
        clickDescription("选择设备 008");
        clickDescription("搜索记录");
        setPrintInput("搜索 SN / 设备名称", "UI-SEARCH-008");
        clickText("未打印");
        requireVisible("已选 1 条");
        require(
            mainBoolean(
                () ->
                    "UI-SEARCH-008"
                        .contentEquals(
                            findInput(activity.getWindow().getDecorView(), "搜索 SN / 设备名称")
                                .getText())),
            "Filter rebuild lost search");
        screenshot("ui-record-search-expanded.png");
        ActivityMonitor monitor = addMonitor(MainActivity.class.getName(), null, false);
        runOnMainSync(() -> activity.recreate());
        MainActivity recreated = (MainActivity) waitForMonitorWithTimeout(monitor, 6000);
        removeMonitor(monitor);
        require(recreated != null, "Recreation did not return activity");
        activity = recreated;
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No recreated focus");
        requireVisible("已选 1 条");
        require(
            mainBoolean(
                () ->
                    activity
                            .getWindow()
                            .getDecorView()
                            .findViewWithTag("record-search-row")
                            .getVisibility()
                        == View.VISIBLE),
            "Expanded search not restored");
        require(
            mainBoolean(
                () ->
                    "UI-SEARCH-008"
                        .contentEquals(
                            findInput(activity.getWindow().getDecorView(), "搜索 SN / 设备名称")
                                .getText())),
            "Search query not restored");
        clickDescription("取消记录搜索");
        requireSearchHidden("record-search-row");
        requireVisible("已选 1 条");
        require(
            mainBoolean(() -> !hasText(activity.getWindow().getDecorView(), "UI-SEARCH-001")),
            "Cancel reset unprinted filter");
        clickText("导出表格");
        requireVisible("导出 1 条记录");
        fixedFooter("取消", "导出 1 条记录");
        screenshot("ui-export-fixed-actions.png");
        clickDescription("导出为 CSV");
        require(
            mainBoolean(
                () ->
                    findInput(activity.getWindow().getDecorView(), "导出文件名")
                        .getText()
                        .toString()
                        .endsWith(".csv")),
            "Export extension not updated");
        clickText("整个项目");
        requireVisible("导出 8 条记录");
        runOnMainSync(
            () -> {
              ViewGroup fields =
                  (ViewGroup) findText(activity.getWindow().getDecorView(), "SN").getParent();
              for (int i = 0; i < fields.getChildCount(); i++)
                if (fields.getChildAt(i) instanceof android.widget.CheckBox)
                  ((android.widget.CheckBox) fields.getChildAt(i)).setChecked(false);
              require(
                  !findDescription(activity.getWindow().getDecorView(), "导出登记表").isEnabled(),
                  "Empty field selection allowed export");
            });
        clickText("取消");
        requireVisible("已选 1 条");
        clickDescription("编辑记录 008");
        fixedFooter("删除", "取消", "保存修改");
        screenshot("ui-record-editor-fixed-actions.png");
        clickText("取消");
        clickDescription("标签");
        fixedFooter("保存模板", "标签打印");
        runOnMainSync(
            () ->
                ((android.widget.ScrollView)
                        activity.getWindow().getDecorView().findViewWithTag("content-scroll"))
                    .fullScroll(View.FOCUS_DOWN));
        waitForIdleSync();
        screenshot("ui-label-scrolled-fixed-actions.png");
        clickText("标签打印");
        fixedFooter("连接打印机", "测试样稿 1 张", "打印 1 张");
        screenshot("ui-print-fixed-actions.png");
        clickDescription("设置");
        fixedFooter("搜索并连接打印机");
        screenshot("ui-settings-fixed-actions.png");
        clickDescription("设备编号规则");
        fixedFooter("保存并应用");
        clickText("返回");
        equal(8, uiStore.project(p).count);
        for (Record r : uiStore.records(p, "", "all", 0)) equal(r.number == 1 ? 1 : 0, r.printed);
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private static View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = findDescription(((ViewGroup) view).getChildAt(i), description);
        if (found != null) return found;
      }
    return null;
  }

  private void requireSearchHidden(String tag) {
    require(
        mainBoolean(
            () ->
                activity.getWindow().getDecorView().findViewWithTag(tag).getVisibility()
                    == View.GONE),
        "Search should be collapsed: " + tag);
  }

  private void fixedFooter(String... labels) {
    int[] before = new int[2];
    runOnMainSync(
        () -> {
          View root = activity.getWindow().getDecorView();
          View footer = root.findViewWithTag("page-footer");
          View nav = root.findViewWithTag("bottom-navigation");
          footer.getLocationOnScreen(before);
          int[] position = new int[2];
          nav.getLocationOnScreen(position);
          require(before[1] + footer.getHeight() == position[1], "Footer detached from bottom nav");
          for (String label : labels) {
            View action = findText(footer, label);
            require(action != null, "Action missing from footer: " + label);
            for (android.view.ViewParent parent = action.getParent();
                parent != null;
                parent = parent.getParent())
              require(!(parent instanceof android.widget.ScrollView), "Action scrolls: " + label);
          }
          android.widget.ScrollView scroll =
              (android.widget.ScrollView) root.findViewWithTag("content-scroll");
          scroll.setSmoothScrollingEnabled(false);
          scroll.fullScroll(View.FOCUS_DOWN);
        });
    waitForIdleSync();
    for (String label : labels) requireVisible(label);
    runOnMainSync(
        () -> {
          View root = activity.getWindow().getDecorView();
          int[] after = new int[2];
          root.findViewWithTag("page-footer").getLocationOnScreen(after);
          equal(before[1], after[1]);
          ((android.widget.ScrollView) root.findViewWithTag("content-scroll")).scrollTo(0, 0);
        });
    waitForIdleSync();
  }

  private void exportPageOutput() throws Exception {
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long p = uiStore.createProject("导出界面验证-" + SystemClock.elapsedRealtime());
      uiStore.register(p, "00012345678901234567890");
      uiStore.register(p, "000SECOND");
      getTargetContext().getSharedPreferences("settings", 0).edit().putLong("project", p).commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      File output = new File(getTargetContext().getFilesDir(), "ui-export-selected.csv");
      AtomicReference<Intent> request = new AtomicReference<>();
      ActivityMonitor documentPicker =
          new ActivityMonitor() {
            @Override
            public ActivityResult onStartActivity(Intent intent) {
              if (!Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())) return null;
              request.set(new Intent(intent));
              return new ActivityResult(
                  Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(output)));
            }
          };
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "No export focus");
        clickDescription("记录");
        clickDescription("选择设备 001");
        clickText("导出表格");
        clickDescription("导出为 CSV");
        setPrintInput("导出文件名", "验收登记表.csv");
        addMonitor(documentPicker);
        clickDescription("导出登记表");
        await(() -> request.get() != null, "Export did not request document creation");
        equal("text/csv", request.get().getType());
        equal("验收登记表.csv", request.get().getStringExtra(Intent.EXTRA_TITLE));
        await(() -> output.length() > 0 && activeWindowText("导出完成"), "Export did not finish");
        require(activeWindowText("已导出 1 条记录。"), "Export ignored selected scope");
        String csv =
            new String(java.nio.file.Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
        require(csv.startsWith("\uFEFF"), "CSV BOM missing");
        require(
            csv.contains("00012345678901234567890") && csv.contains("001"),
            "Leading zero values lost");
        require(!csv.contains("000SECOND"), "Export included unselected record");
        screenshot("ui-export-completed.png");
        clickActiveWindow("完成");
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "Export dialog did not close");
        requireVisible("已选 1 条");
        equal(2, uiStore.project(p).count);
      } finally {
        removeMonitor(documentPicker);
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private static TableImporter.Result importCsv(String csv) throws Exception {
    return TableImporter.read(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
  }

  private void inventoryImportCsv() throws Exception {
    TableImporter.Result result =
        importCsv(
            "\uFEFF设备名称,SN,资产编号,设备编号,数量,项目名称\r\n"
                + "\"电脑,办公\",000001,A001,PC-01,1,导入项目\r\n"
                + ",000002,A002,,1,\r\n"
                + "打印机,,A003,,,\r\n");
    equal(3, result.rows.size());
    equal("导入项目", result.projectName);
    equal("电脑,办公", result.rows.get(1).equipmentName);
    equal("PC-01", result.rows.get(0).deviceNumber);
    equal("000001", result.rows.get(0).sn);
    long p = store.importProject(result.projectName, result.rows);
    require(store.project(p).inventory, "Import should create inventory project");
    equal(3, store.project(p).count);
    equal(4, store.project(p).nextNumber);
    equal("PC-01", store.records(p, "000001", "all", 0).get(0).deviceName());
    equal("002", store.records(p, "000002", "all", 0).get(0).deviceName());
    equal("A003", store.records(p, "A003", "all", 0).get(0).sn);
    String legacy = "设备名称,资产编号\r\n笔记本,000资产001\r\n";
    TableImporter.Result gb =
        TableImporter.read(
            new ByteArrayInputStream(legacy.getBytes(java.nio.charset.Charset.forName("GB18030"))));
    equal("笔记本", gb.rows.get(0).equipmentName);
    equal("000资产001", gb.rows.get(0).assetNumber);
    equal("带\"引号\"", importCsv("设备名称,SN\n\"带\"\"引号\"\"\",ID-01").rows.get(0).equipmentName);
  }

  private void inventoryImportRejects() throws Exception {
    rejects(() -> importCsv("设备名称,SN\n电脑,\n"));
    rejects(() -> importCsv("SN,资产编号\nS1,A1\nA1,A2\n"));
    rejects(() -> importCsv("SN\nS1\nS1\n"));
    rejects(() -> importCsv("SN,数量\nS1,10\n"));
    rejects(() -> importCsv("SN,数量\nS1,0\n"));
    rejects(() -> importCsv("SN,设备编号\nS1,002\nS2,\n"));
    rejects(() -> importCsv("SN,项目\nS1,甲\nS2,乙\n"));
    rejects(() -> importCsv("SN,SN\nS1,S1\n"));
    rejects(() -> importCsv("设备名称\n电脑\n"));
    rejects(() -> importCsv("SN\n\"unterminated"));
    rejects(() -> importCsv("SN\n\"S1\"oops\n"));
    int count = store.projects().size();
    List<TableImporter.Row> valid = importCsv("SN\nGOOD\n").rows;
    store.importProject("已存在项目", valid);
    try {
      store.importProject("已存在项目", valid);
      throw new AssertionError("Duplicate name accepted");
    } catch (android.database.sqlite.SQLiteConstraintException expected) {
    }
    equal(count + 1, store.projects().size());
    TableImporter.Row bad = new TableImporter.Row();
    rejects(() -> store.importProject("无效不能创建", Arrays.asList(bad)));
    equal(count + 1, store.projects().size());
  }

  private byte[] inventoryWorkbook(String sheet, String shared) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
      Map<String, String> parts = new HashMap<>();
      parts.put(
          "xl/workbook.xml",
          "<workbook"
              + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet"
              + " r:id=\"r1\"/></sheets></workbook>");
      parts.put(
          "xl/_rels/workbook.xml.rels",
          "<Relationships><Relationship Id=\"r1\""
              + " Target=\"worksheets/data.xml\"/></Relationships>");
      parts.put("xl/worksheets/data.xml", sheet);
      if (shared != null) parts.put("xl/sharedStrings.xml", shared);
      for (Map.Entry<String, String> e : parts.entrySet()) {
        zip.putNextEntry(new ZipEntry(e.getKey()));
        zip.write(e.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    return out.toByteArray();
  }

  private void inventoryImportXlsx() throws Exception {
    String header =
        "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"s\"><v>1</v></c><c r=\"D1\""
            + " t=\"inlineStr\"><is><t>资产编号</t></is></c></row>";
    String data =
        "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>2</v></c><c r=\"C2\" t=\"s\"><v>3</v></c><c r=\"D2\""
            + " t=\"inlineStr\"><is><t>000A-01</t></is></c></row>";
    String shared =
        "<sst><si><t>设备名称</t></si><si><t>SN</t></si><si><r><t>办公</t></r><r><t>电脑</t></r></si><si><t>00012345678901234567890</t></si></sst>";
    byte[] file =
        inventoryWorkbook(
            "<worksheet><sheetData>" + header + data + "</sheetData></worksheet>", shared);
    TableImporter.Result result = TableImporter.read(new ByteArrayInputStream(file));
    equal("办公电脑", result.rows.get(0).equipmentName);
    equal("00012345678901234567890", result.rows.get(0).sn);
    equal("000A-01", result.rows.get(0).assetNumber);
    String numeric = "<row><c r=\"C2\"><v>12345678901234567890</v></c></row>";
    byte[] invalid =
        inventoryWorkbook(
            "<worksheet><sheetData>" + header + numeric + "</sheetData></worksheet>", shared);
    rejects(() -> TableImporter.read(new ByteArrayInputStream(invalid)));
    byte[] formula =
        inventoryWorkbook(
            "<worksheet><sheetData>"
                + header
                + "<row><c r=\"C2\" t=\"str\"><f>1+1</f><v>2</v></c></row></sheetData></worksheet>",
            shared);
    rejects(() -> TableImporter.read(new ByteArrayInputStream(formula)));
    try {
      TableImporter.read(new ByteArrayInputStream(new byte[] {'P', 'K', 0, 0}));
      throw new AssertionError("Bad XLSX accepted");
    } catch (IllegalArgumentException expected) {
    }
  }

  private void inventoryCounting() throws Exception {
    List<TableImporter.Row> rows =
        importCsv("设备名称,SN,资产编号,设备编号\n电脑,00001,A01,PC-01\n电脑,00002,A02,PC-02\n打印机,,A03,PR-01\n")
            .rows;
    long p = store.importProject("盘点项目", rows), other = store.importProject("另一个项目", rows);
    long reg = store.createProject("登记项目");
    equal("001", store.register(reg, "REGISTER").record.deviceName());
    rejects(() -> store.register(p, "UNKNOWN"));
    require(store.checkInventory(p, "UNKNOWN") == null, "Unknown should return null");
    equal(0, store.project(p).checked);
    equal(3, store.project(p).count);
    equal(4, store.project(p).nextNumber);
    Models.ScanResult first = store.checkInventory(p, "00001");
    require(!first.duplicate && !first.record.checkedAt.isEmpty(), "Initial check missing");
    Models.ScanResult alias = store.checkInventory(p, "A01");
    require(alias.duplicate, "Alias counted again");
    equal(first.record.checkedAt, alias.record.checkedAt);
    equal(first.record.id, alias.record.id);
    equal(1, store.project(p).checked);
    equal(0, store.project(other).checked);
    equal(1, store.records(p, "", "checked", 0).size());
    equal(2, store.records(p, "", "missing", 0).size());
    equal(2, store.records(p, "电脑", "all", 0).size());
    equal(1, store.records(p, "A02", "all", 0).size());
    equal("PC-01", store.latestInventory(p).deviceName());
    store.checkInventory(p, "A03");
    equal(2, store.project(p).checked);
    Map<String, String[]> groups = new HashMap<>();
    for (String[] g : store.inventoryGroups(p)) groups.put(g[0], g);
    equal("2", groups.get("电脑")[1]);
    equal("1", groups.get("电脑")[2]);
    equal("1", groups.get("打印机")[1]);
    equal("1", groups.get("打印机")[2]);
    store.close();
    store = new RegistryStore(getTargetContext(), database);
    equal(2, store.project(p).checked);
    store.setInventoryMode(p, false);
    require(!store.project(p).inventory && store.project(other).inventory, "Mode leaked");
    require(store.register(p, "A01").duplicate, "Registration alias created a second device");
    equal("004", store.register(p, "NEW-REG").record.deviceName());
    store.setInventoryMode(p, true);
    equal(2, store.project(p).checked);
    store.resetInventory(p);
    equal(0, store.project(p).checked);
    equal(4, store.project(p).count);
    require(store.latestInventory(p) == null, "Reset retained latest");
    store.archive(p, true);
    rejects(() -> store.checkInventory(p, "00002"));
    long collision = store.importProject("编号碰撞", importCsv("SN,设备编号\nC1,002\n").rows);
    store.setInventoryMode(collision, false);
    rejects(() -> store.register(collision, "C2"));
    equal(1, store.project(collision).count);
    equal(2, store.project(collision).nextNumber);
  }

  private void inventoryDeleteAndEdit() throws Exception {
    long p = store.importProject("删改隔离", importCsv("SN,资产编号,设备编号\nS1,A1,PC1\nS2,A2,PC2\n").rows);
    Record r = store.checkInventory(p, "S1").record;
    rejects(() -> store.updateRecord(p, r.id, r.number, "A2", r.date));
    equal(1, store.project(p).checked);
    store.updateRecord(p, r.id, r.number, "S1-NEW", r.date);
    equal(0, store.project(p).checked);
    equal("PC1", store.record(p, r.id).deviceName());
    require(store.checkInventory(p, "S1") == null, "Old SN still accepted");
    store.checkInventory(p, "A1");
    store.deleteRecord(p, r.id);
    equal(0, store.project(p).checked);
    equal(1, store.project(p).count);
    store.checkInventory(p, "S2");
    store.deleteProject(p);
    try (android.database.Cursor c =
        store.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM inventory_checks", null)) {
      c.moveToFirst();
      equal(0, c.getInt(0));
    }
  }

  private void importMapping() throws Exception {
    TableImporter.Source source =
        TableImporter.readSource(
            new ByteArrayInputStream(
                ("固定资产编码,品名,类别,台数,其他\n000001,电脑,计算机,,忽略\n000002,,,,其他\n000003,电脑,办公设备,1,\n")
                    .getBytes(StandardCharsets.UTF_8)));
    equal(3, source.rowCount());
    equal("A · 固定资产编码", source.columnLabel(0));
    equal("000001、000002", source.sample(0));
    int[] mapping = TableImporter.suggest(source);
    mapping[TableImporter.ASSET] = 0;
    mapping[TableImporter.NAME] = 1;
    mapping[TableImporter.CATEGORY] = 2;
    mapping[TableImporter.QUANTITY] = 3;
    TableImporter.Result result = TableImporter.map(source, mapping);
    equal(3, result.rows.size());
    equal("000001", result.rows.get(0).assetNumber);
    equal("电脑", result.rows.get(1).equipmentName);
    equal("计算机", result.rows.get(1).category);
    long p = store.importProject("字段映射", result.rows);
    equal(2, store.inventoryGroups(p).size());
    equal(2, store.records(p, "计算机", "all", 0).size());
    equal(1, store.records(p, "办公设备", "all", 0).size());
    equal("计算机", store.records(p, "000001", "all", 0).get(0).category);
    store.checkInventory(p, "000001");
    equal(1, store.project(p).checked);
    equal(3, store.project(p).count);
    int[] duplicate = mapping.clone();
    duplicate[TableImporter.SN] = 0;
    rejects(() -> TableImporter.map(source, duplicate));
    int[] missing = mapping.clone();
    missing[TableImporter.ASSET] = -1;
    rejects(() -> TableImporter.map(source, missing));
    TableImporter.Source quantity =
        TableImporter.readSource(
            new ByteArrayInputStream(
                "编码,品名,类别,台数\n00001,电脑,计算机,10\n".getBytes(StandardCharsets.UTF_8)));
    rejects(() -> TableImporter.map(quantity, mapping));
    TableImporter.Source unknown =
        TableImporter.readSource(
            new ByteArrayInputStream("自定义列,备注\n000099,忽略内容\n".getBytes(StandardCharsets.UTF_8)));
    int[] custom = TableImporter.suggest(unknown);
    rejects(() -> TableImporter.map(unknown, custom));
    custom[TableImporter.ASSET] = 0;
    equal("000099", TableImporter.map(unknown, custom).rows.get(0).assetNumber);
  }

  private void categoryMigration() throws Exception {
    String name = "migration-v3-" + UUID.randomUUID() + ".db";
    try {
      try (android.database.sqlite.SQLiteDatabase db =
          android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
              getTargetContext().getDatabasePath(name), null)) {
        db.execSQL(
            "CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL"
                + " UNIQUE,archived INTEGER NOT NULL DEFAULT 0,next_number INTEGER NOT NULL DEFAULT"
                + " 1,template TEXT NOT NULL DEFAULT '',name_prefix TEXT NOT NULL DEFAULT"
                + " '',number_digits INTEGER NOT NULL DEFAULT 3,start_number INTEGER NOT NULL"
                + " DEFAULT 1,inventory INTEGER NOT NULL DEFAULT 0)");
        db.execSQL(
            "CREATE TABLE records(id TEXT PRIMARY KEY,project_id INTEGER NOT NULL REFERENCES"
                + " projects(id),number INTEGER NOT NULL,sn TEXT NOT NULL,date TEXT NOT"
                + " NULL,created_at TEXT NOT NULL,printed INTEGER NOT NULL DEFAULT 0,equipment_name"
                + " TEXT NOT NULL DEFAULT '',asset_number TEXT NOT NULL DEFAULT '',imported_name"
                + " TEXT NOT NULL DEFAULT '',UNIQUE(project_id,sn),UNIQUE(project_id,number))");
        db.execSQL(
            "CREATE TABLE inventory_checks(record_id TEXT PRIMARY KEY REFERENCES records(id) ON"
                + " DELETE CASCADE,checked_at TEXT NOT NULL)");
        db.execSQL("INSERT INTO projects VALUES(7,'旧盘点',1,13,'template-json','PC-',4,10,1)");
        db.execSQL(
            "INSERT INTO records VALUES('keep-id',7,12,'000SN','2026-11-16','2026-11-16"
                + " 09:00:00',3,'电脑','000ASSET','PC-0012')");
        db.execSQL("INSERT INTO inventory_checks VALUES('keep-id','2026-11-16 10:00:00')");
        db.setVersion(3);
      }
      try (RegistryStore migrated = new RegistryStore(getTargetContext(), name)) {
        equal(4, migrated.getReadableDatabase().getVersion());
        Models.Project p = migrated.project(7);
        require(p.inventory && p.archived, "Migration changed project");
        equal(1, p.checked);
        equal(13, p.nextNumber);
        equal("template-json", p.template);
        Record r = migrated.record(7, "keep-id");
        equal("", r.category);
        equal("000SN", r.sn);
        equal("000ASSET", r.assetNumber);
        equal("电脑", r.equipmentName);
        equal("PC-0012", r.deviceName());
        equal(3, r.printed);
        equal("2026-11-16 10:00:00", r.checkedAt);
        migrated.archive(7, false);
        require(migrated.checkInventory(7, "000ASSET").duplicate, "Migrated checked state lost");
      }
    } finally {
      getTargetContext().deleteDatabase(name);
    }
  }

  private void templateImport() throws Exception {
    try (java.io.InputStream input = getContext().getAssets().open("import-template-sample.xlsx")) {
      TableImporter.Result data = TableImporter.read(input);
      equal(3, data.rows.size());
      equal("000001", data.rows.get(0).assetNumber);
      equal("00012345678901234567", data.rows.get(0).sn);
      equal("计算机", data.rows.get(0).category);
      equal("", data.rows.get(2).sn);
      long p = store.importProject("模板验证", data.rows);
      equal(3, store.project(p).count);
      equal("PC-001", store.records(p, "000001", "all", 0).get(0).deviceName());
      store.checkInventory(p, "000003");
      equal(1, store.project(p).checked);
    }
  }

  private int warningStream() {
    int[] stream = {0};
    runOnMainSync(
        () -> {
          try {
            java.lang.reflect.Field f = MainActivity.class.getDeclaredField("warningSound");
            f.setAccessible(true);
            java.lang.reflect.Field s = WarningSound.class.getDeclaredField("streamId");
            s.setAccessible(true);
            stream[0] = s.getInt(f.get(activity));
          } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
          }
        });
    return stream[0];
  }

  private void awaitWarningReady() {
    await(
        () ->
            mainBoolean(
                () -> {
                  try {
                    java.lang.reflect.Field f = MainActivity.class.getDeclaredField("warningSound");
                    f.setAccessible(true);
                    java.lang.reflect.Field l = WarningSound.class.getDeclaredField("loaded");
                    l.setAccessible(true);
                    return l.getBoolean(f.get(activity));
                  } catch (ReflectiveOperationException e) {
                    throw new AssertionError(e);
                  }
                }),
        "Bundled warning voice failed to load on PDA");
  }

  private void warningAudioUi() throws Exception {
    android.content.SharedPreferences preferences =
        getTargetContext().getSharedPreferences("settings", 0);
    try (android.content.res.AssetFileDescriptor wav =
        getTargetContext().getResources().openRawResourceFd(R.raw.scan_warning)) {
      android.media.MediaMetadataRetriever meta = new android.media.MediaMetadataRetriever();
      try {
        meta.setDataSource(wav.getFileDescriptor(), wav.getStartOffset(), wav.getLength());
        long duration =
            Long.parseLong(
                meta.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
        require(
            duration >= 300 && duration <= 1400,
            "Warning is not a short playable sound: " + duration);
      } finally {
        meta.release();
      }
    }
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long registration = uiStore.createProject("声音登记-" + SystemClock.elapsedRealtime());
      preferences
          .edit()
          .putLong("project", registration)
          .putInt("scanMode", 0)
          .putBoolean("feedback", false)
          .remove("errorFeedback")
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "Audio registration no focus");
        awaitWarningReady();
        broadcast("SOUND-ONE");
        await(() -> uiStore.project(registration).count == 1, "Audio success did not save");
        equal(0, warningStream());
        broadcast("SOUND-ONE");
        await(() -> warningStream() > 0, "Registration duplicate did not play warning");
        int stream = warningStream();
        equal(1, uiStore.project(registration).count);
        equal(2, uiStore.project(registration).nextNumber);
        broadcast("SOUND-ONE");
        await(
            () -> warningStream() != stream && warningStream() > 0,
            "Repeated duplicate did not restart warning");
        broadcast("SOUND-TWO");
        await(() -> uiStore.project(registration).count == 2, "Audio next success failed");
        equal(0, warningStream());
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
      long inventory =
          uiStore.importProject(
              "声音盘点-" + SystemClock.elapsedRealtime(),
              importCsv("资产ID,设备名称,SN\nSOUND-ASSET,电脑,SOUND-KNOWN\n").rows);
      preferences.edit().putLong("project", inventory).commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "Audio inventory no focus");
        awaitWarningReady();
        broadcast("SOUND-ONE");
        await(() -> warningStream() > 0, "Foreign project barcode did not play warning");
        requireVisible("不属于当前项目");
        equal(0, uiStore.project(inventory).checked);
        equal(1, uiStore.project(inventory).count);
        broadcast("SOUND-KNOWN");
        await(() -> uiStore.project(inventory).checked == 1, "Audio known scan failed");
        equal(0, warningStream());
        broadcast("SOUND-ASSET");
        await(() -> warningStream() > 0, "Inventory duplicate asset did not play warning");
        requireVisible("已盘点");
        equal(1, uiStore.project(inventory).checked);
        clickDescription("设置");
        clickDescription("异常提示音开关");
        require(!preferences.getBoolean("errorFeedback", true), "Warning switch did not disable");
        equal(0, warningStream());
        clickDescription("扫码");
        broadcast("SOUND-ASSET");
        SystemClock.sleep(180);
        equal(0, warningStream());
        requireVisible("已盘点");
        broadcast("SOUND-UNKNOWN");
        SystemClock.sleep(180);
        equal(0, warningStream());
        requireVisible("不属于当前项目");
        clickDescription("设置");
        clickDescription("试听异常提示音");
        await(() -> warningStream() > 0, "Preview did not play with switch disabled");
        require(
            !preferences.getBoolean("errorFeedback", true), "Preview changed warning preference");
        clickDescription("异常提示音开关");
        require(preferences.getBoolean("errorFeedback", false), "Warning switch did not enable");
        screenshot("ui-warning-sound-settings.png");
        clickDescription("扫码");
        broadcast("SOUND-KNOWN");
        await(() -> warningStream() > 0, "Reenabled warning silent");
        runOnMainSync(() -> activity.moveTaskToBack(true));
        await(
            () -> !mainBoolean(() -> activity.hasWindowFocus()) && warningStream() == 0,
            "Background did not stop warning");
        broadcast("SOUND-UNKNOWN");
        SystemClock.sleep(180);
        equal(0, warningStream());
        equal(1, uiStore.project(inventory).count);
        equal(1, uiStore.project(inventory).checked);
        equal(2, uiStore.project(registration).count);
      } finally {
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    } finally {
      preferences.edit().putBoolean("errorFeedback", false).commit();
    }
  }

  private void inventoryExportRoundtrip() throws Exception {
    long p =
        store.importProject(
            "往返项目",
            importCsv(
                    "设备名称,SN,资产编号,设备编号,分类,数量\n"
                        + "电脑,00012345678901234567890,A01,PC-001,计算机,1\n"
                        + "电脑,00002,A02,PC-002,计算机,1\n")
                .rows);
    store.checkInventory(p, "A01");
    List<Record> rows = store.records(p, "", "all", 0);
    boolean[] cols = new boolean[TableExporter.INVENTORY_HEADERS.length];
    Arrays.fill(cols, true);
    for (boolean csv : new boolean[] {false, true}) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      if (csv) TableExporter.csv(out, rows, cols, TableExporter.INVENTORY_HEADERS);
      else TableExporter.xlsx(out, rows, cols, TableExporter.INVENTORY_HEADERS);
      TableImporter.Result imported =
          TableImporter.read(new ByteArrayInputStream(out.toByteArray()));
      equal(2, imported.rows.size());
      equal("往返项目", imported.projectName);
      long copy = store.importProject(csv ? "CSV副本" : "XLSX副本", imported.rows);
      equal(0, store.project(copy).checked);
      Record original = store.records(copy, "A01", "all", 0).get(0);
      equal("00012345678901234567890", original.sn);
      equal("电脑", original.equipmentName);
      equal("PC-001", original.deviceName());
      equal("计算机", original.category);
      equal("1", TableExporter.value(original, 9));
    }
    equal("已盘点", TableExporter.value(store.records(p, "A01", "all", 0).get(0), 6));
    equal("未盘点", TableExporter.value(store.records(p, "A02", "all", 0).get(0), 6));
  }

  private void inventoryMigration() throws Exception {
    String name = "migration-v2-" + UUID.randomUUID() + ".db";
    try {
      try (android.database.sqlite.SQLiteDatabase old =
          android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
              getTargetContext().getDatabasePath(name), null)) {
        old.execSQL(
            "CREATE TABLE projects(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL"
                + " UNIQUE,archived INTEGER NOT NULL DEFAULT 0,next_number INTEGER NOT NULL DEFAULT"
                + " 1,template TEXT NOT NULL DEFAULT '',name_prefix TEXT NOT NULL DEFAULT"
                + " '',number_digits INTEGER NOT NULL DEFAULT 3,start_number INTEGER NOT NULL"
                + " DEFAULT 1)");
        old.execSQL(
            "CREATE TABLE records(id TEXT PRIMARY KEY,project_id INTEGER NOT NULL REFERENCES"
                + " projects(id),number INTEGER NOT NULL,sn TEXT NOT NULL,date TEXT NOT"
                + " NULL,created_at TEXT NOT NULL,printed INTEGER NOT NULL DEFAULT"
                + " 0,UNIQUE(project_id,sn),UNIQUE(project_id,number))");
        old.execSQL("INSERT INTO projects VALUES(7,'旧项目',1,13,'template-json','PC-',4,10)");
        old.execSQL(
            "INSERT INTO records VALUES('keep-id',7,12,'000OLD','2026-11-16','2026-11-16"
                + " 09:00:00',3)");
        old.setVersion(2);
      }
      try (RegistryStore migrated = new RegistryStore(getTargetContext(), name)) {
        equal(4, migrated.getReadableDatabase().getVersion());
        Models.Project p = migrated.project(7);
        equal("旧项目", p.name);
        require(p.archived && !p.inventory, "Existing project changed mode");
        equal(13, p.nextNumber);
        equal(4, p.numberDigits);
        equal(10, p.startNumber);
        equal("template-json", p.template);
        equal(0, p.checked);
        Record r = migrated.records(7, "", "all", 0).get(0);
        equal("keep-id", r.id);
        equal("PC-0012", r.deviceName());
        equal("000OLD", r.sn);
        equal(3, r.printed);
        equal("2026-11-16 09:00:00", r.createdAt);
        equal("", r.checkedAt);
      }
    } finally {
      getTargetContext().deleteDatabase(name);
    }
  }

  private void inventoryUi() throws Exception {
    String name = "清单界面验证-" + SystemClock.elapsedRealtime();
    File fixture = new File(getTargetContext().getFilesDir(), "inventory-input.csv");
    try (FileOutputStream out = new FileOutputStream(fixture)) {
      out.write(
          ("设备名称,SN,资产编号,设备编号\n"
                  + "电脑,INV-0001,INV-A001,PC-01\n"
                  + "电脑,INV-0002,INV-A002,PC-02\n"
                  + "打印机,,INV-A003,PR-01\n")
              .getBytes(StandardCharsets.UTF_8));
    }
    ActivityMonitor picker =
        new ActivityMonitor() {
          @Override
          public ActivityResult onStartActivity(Intent intent) {
            return Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())
                ? new ActivityResult(
                    Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(fixture)))
                : null;
          }
        };
    try (RegistryStore uiStore = new RegistryStore(getTargetContext())) {
      long prior = uiStore.createProject("导入前登记-" + SystemClock.elapsedRealtime());
      uiStore.register(prior, "OUTSIDE-CURRENT");
      getTargetContext()
          .getSharedPreferences("settings", 0)
          .edit()
          .putLong("project", prior)
          .putInt("scanMode", 0)
          .putBoolean("feedback", false)
          .commit();
      activity =
          (MainActivity)
              startActivitySync(
                  new Intent(getTargetContext(), MainActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      try {
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "Inventory activity no focus");
        int projects = uiStore.projects().size();
        clickDescription("项目");
        addMonitor(picker);
        clickText("导入项目");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "选择导入字段")),
            "Mapping page missing");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "已读取 3 行 · 4 列")),
            "Mapping data missing");
        fixedFooter("取消", "预览清单");
        clickDescription("选择导入字段 资产ID");
        clickActiveWindow("A · 设备名称");
        clickText("预览清单");
        requireVisible("同一列只能对应一个字段，请检查“设备名称”");
        equal(projects, uiStore.projects().size());
        clickDescription("选择导入字段 资产ID");
        clickActiveWindow("C · 资产编号");
        ActivityMonitor mappingRotation = addMonitor(MainActivity.class.getName(), null, false);
        runOnMainSync(() -> activity.recreate());
        MainActivity mappingRecreated =
            (MainActivity) waitForMonitorWithTimeout(mappingRotation, 6000);
        removeMonitor(mappingRotation);
        require(mappingRecreated != null, "Mapping recreation failed");
        activity = mappingRecreated;
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "已读取 3 行 · 4 列")),
            "Mapping recreation lost source");
        requireVisible("C · 资产编号  ▾");
        fixedFooter("取消", "预览清单");
        screenshot("ui-import-mapping.png");
        clickText("预览清单");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "已识别 3 台设备")),
            "Import preview missing");
        equal(projects, uiStore.projects().size());
        fixedFooter("上一步", "导入并开始盘点");
        setPrintInput("导入项目名称", name);
        clickText("上一步");
        fixedFooter("取消", "预览清单");
        clickText("预览清单");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "已识别 3 台设备")),
            "Preview return missing");
        equal(projects, uiStore.projects().size());
        screenshot("ui-inventory-import.png");
        clickText("导入并开始盘点");
        await(() -> uiStore.projects().size() == projects + 1, "Import did not commit");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "扫码盘点")),
            "Inventory page missing");
        long p = getTargetContext().getSharedPreferences("settings", 0).getLong("project", 0);
        require(uiStore.project(p).inventory, "Imported project wrong mode");
        equal(3, uiStore.project(p).count);
        fixedFooter("暂停盘点", "手动核对");
        broadcast("INV-0001");
        await(() -> uiStore.project(p).checked == 1, "Known scan not checked");
        requireVisible("盘点成功");
        screenshot("ui-inventory-success.png");
        broadcast("INV-A001");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "已盘点")),
            "Repeat missing");
        equal(1, uiStore.project(p).checked);
        broadcast("OUTSIDE-CURRENT");
        await(
            () -> mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "不属于当前项目")),
            "Unknown feedback missing");
        equal(3, uiStore.project(p).count);
        equal(1, uiStore.project(p).checked);
        equal(4, uiStore.project(p).nextNumber);
        screenshot("ui-inventory-unknown.png");
        ActivityMonitor rotation = addMonitor(MainActivity.class.getName(), null, false);
        runOnMainSync(() -> activity.recreate());
        MainActivity recreated = (MainActivity) waitForMonitorWithTimeout(rotation, 6000);
        removeMonitor(rotation);
        require(recreated != null, "Inventory recreation failed");
        activity = recreated;
        await(() -> mainBoolean(() -> activity.hasWindowFocus()), "Inventory recreated no focus");
        requireVisible("不属于当前项目");
        clickText("盘点结果");
        fixedFooter("重新盘点", "导出结果");
        requireVisible("应盘 3 台   已盘 1 台   未盘 2 台");
        screenshot("ui-inventory-summary.png");
        clickDescription("记录");
        clickText("未盘 2");
        requireVisible("INV-0002");
        requireVisible("INV-A003");
        require(
            !mainBoolean(() -> hasText(activity.getWindow().getDecorView(), "INV-0001")),
            "Missing filter included checked");
        clickText("已盘 1");
        requireVisible("INV-0001");
        screenshot("ui-inventory-records.png");
        clickText("导出表格");
        File output = new File(getTargetContext().getFilesDir(), "inventory-result.csv");
        ActivityMonitor exporter =
            new ActivityMonitor() {
              @Override
              public ActivityResult onStartActivity(Intent intent) {
                return Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())
                    ? new ActivityResult(
                        Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(output)))
                    : null;
              }
            };
        addMonitor(exporter);
        try {
          clickDescription("导出为 CSV");
          clickDescription("导出登记表");
          await(() -> output.length() > 0 && activeWindowText("导出完成"), "Inventory export missing");
          String csv =
              new String(java.nio.file.Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
          require(
              csv.contains("盘点时间") && csv.contains("已盘点") && csv.contains("INV-A001"),
              "Inventory export lost fields");
          require(!csv.contains("INV-A002"), "Export ignored checked filter");
          clickActiveWindow("完成");
        } finally {
          removeMonitor(exporter);
        }
        clickDescription("扫码");
        clickText("盘点结果");
        clickText("重新盘点");
        clickActiveWindow("取消");
        equal(1, uiStore.project(p).checked);
        clickText("重新盘点");
        clickActiveWindow("重新盘点");
        equal(0, uiStore.project(p).checked);
        equal(3, uiStore.project(p).count);
        equal(1, uiStore.project(prior).count);
        require(!uiStore.project(prior).inventory, "Other mode changed");
      } finally {
        removeMonitor(picker);
        runOnMainSync(() -> activity.finish());
        waitForIdleSync();
      }
    }
  }

  private void requireVisible(String label) {
    runOnMainSync(
        () -> {
          View view = findText(activity.getWindow().getDecorView(), label);
          android.graphics.Rect bounds = new android.graphics.Rect();
          require(view != null && view.getGlobalVisibleRect(bounds), "Not visible: " + label);
          require(
              bounds.height() >= view.getHeight() - 2 && bounds.width() >= view.getWidth() - 2,
              "Clipped on PDA: " + label);
        });
  }

  private static android.widget.EditText findInput(View view, String hint) {
    if (view instanceof android.widget.EditText
        && hint.contentEquals(((android.widget.EditText) view).getHint()))
      return (android.widget.EditText) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        android.widget.EditText found = findInput(((ViewGroup) view).getChildAt(i), hint);
        if (found != null) return found;
      }
    return null;
  }

  private static View findText(View view, String text) {
    if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = findText(((ViewGroup) view).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private void broadcast(String sn) {
    getTargetContext()
        .sendBroadcast(
            new Intent(ScannerBridge.DEFAULT_ACTION)
                .setPackage(getTargetContext().getPackageName())
                .putExtra(ScannerBridge.DEFAULT_KEY, sn));
  }

  private boolean mainBoolean(BooleanSupplier supplier) {
    boolean[] result = {false};
    runOnMainSync(() -> result[0] = supplier.getAsBoolean());
    return result[0];
  }

  private static void await(BooleanSupplier condition, String message) {
    long end = SystemClock.elapsedRealtime() + 6000;
    do {
      if (condition.getAsBoolean()) return;
      SystemClock.sleep(60);
    } while (SystemClock.elapsedRealtime() < end);
    throw new AssertionError(message);
  }

  private static boolean hasText(View view, String value) {
    if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return true;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        if (hasText(((ViewGroup) view).getChildAt(i), value)) return true;
    return false;
  }

  private void clickText(String text) {
    runOnMainSync(
        () ->
            require(
                click(activity.getWindow().getDecorView(), text, false), "Missing button " + text));
    waitForIdleSync();
  }

  private void clickDescription(String text) {
    runOnMainSync(
        () ->
            require(click(activity.getWindow().getDecorView(), text, true), "Missing tab " + text));
    waitForIdleSync();
  }

  private static boolean click(View view, String value, boolean description) {
    boolean match =
        description
            ? value.contentEquals(
                view.getContentDescription() == null ? "" : view.getContentDescription())
            : view instanceof TextView && value.contentEquals(((TextView) view).getText());
    if (match && view.isClickable()) {
      view.performClick();
      return true;
    }
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        if (click(((ViewGroup) view).getChildAt(i), value, description)) return true;
    return false;
  }

  private void screenshot(String name) throws Exception {
    waitForIdleSync();
    SystemClock.sleep(150);
    Bitmap bitmap = getUiAutomation().takeScreenshot();
    require(bitmap != null, "No screenshot");
    try (FileOutputStream file =
        new FileOutputStream(new File(getTargetContext().getFilesDir(), name))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, file);
    } finally {
      bitmap.recycle();
    }
  }
}
