package com.sanduo.pdaregistry;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import com.dothantech.lpapi.LPAPI;
import com.dothantech.printer.IDzPrinter.PrinterAddress;
import com.sanduo.pdaregistry.Models.Project;
import com.sanduo.pdaregistry.Models.Record;
import com.sanduo.pdaregistry.Models.ScanResult;
import java.io.OutputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
  private static final int BG = 0xFFF5F7FB,
      INK = 0xFF142238,
      MUTED = 0xFF64748B,
      BLUE = 0xFF215DE7,
      SOFT = 0xFFEAF0FF,
      GREEN = 0xFF087D57,
      LINE = 0xFFE3E9F1;
  private static final int EXPORT_REQUEST = 20,
      BLUETOOTH_REQUEST = 21,
      ENABLE_BT_REQUEST = 22,
      IMPORT_REQUEST = 23;
  private RegistryStore store;
  private SharedPreferences prefs;
  private ScannerBridge scanner;
  private PrinterController printer;
  private WarningSound warningSound;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private long projectId;
  private String importUri = "", importName = "";
  private List<TableImporter.Row> importRows;
  private TableImporter.Source importSource;
  private int[] importMapping;
  private String importError = "";
  private boolean importMoreFields, importNameEdited;
  private boolean importBusy;

  private int tab, limit = 100, editingLine;
  private String query = "", filter = "all", pending = "", pendingError = "";
  private boolean scanPaused, resumed, templateDirty;
  private final Map<Long, Integer> sessions = new HashMap<>();
  private final LinkedHashSet<String> selected = new LinkedHashSet<>();
  private SwipeDeleteLayout openProjectRow;
  private SwipeDeleteLayout openRecordRow;
  private int recordReturnTab = 2;
  private String editingRecordId = "", recordSn = "", recordNumber = "", recordDate = "";
  private TextView recordNamePreview, recordEditError;
  private String projectSearch = "";
  private boolean projectSearchExpanded, recordSearchExpanded;
  private LinearLayout body, recordList, footer;
  private FrameLayout screen;
  private TextView successNotice;
  private final Handler ui = new Handler(Looper.getMainLooper());
  private final Runnable hideNotice = this::hideSuccessNotice;
  private TextView selectionText, printProgressText;
  private Button recordPrintButton, printPauseButton;
  private int printReturnTab = 3, printSource, printPreviewIndex;
  private String printStart = "1", printQuantity = "1", printCopies = "1";
  private ArrayList<String> printIds = new ArrayList<>();
  private TextView printConnection, printSummary, printRange, printPreviewCaption;
  private Button printSubmitButton, printSampleButton;
  private LabelView printPreview;
  private PrintRequest preparedPrint;
  private Record printPreviewRecord;
  private String namingPrefix = "", namingDigits = "3", namingStart = "1";
  private TextView namingPreview, namingNext, namingError;
  private Button namingSave;
  private EditText focusInput;
  private LabelTemplate template;
  private LabelView labelView;
  private AlertDialog discoveryDialog;
  private ArrayAdapter<String> discoveryAdapter;
  private final List<PrinterAddress> discoveryAddresses = new ArrayList<>();
  private boolean exportCsv, exportBusy;
  private boolean[] exportColumns = {true, true, true, true};
  private int exportScope;
  private long exportProject;
  private String exportQuery = "", exportFilter = "all";
  private String exportFilename = "";
  private ArrayList<String> exportIds = new ArrayList<>();

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    prefs = getSharedPreferences("settings", MODE_PRIVATE);
    warningSound = new WarningSound(this);
    store = new RegistryStore(this);
    if (store.projects().isEmpty()) store.createProject("默认项目");
    projectId = prefs.getLong("project", store.projects().get(0).id);
    if (saved != null) {
      projectId = saved.getLong("project", projectId);
      tab = saved.getInt("tab");
      scanPaused = saved.getBoolean("paused");
      pending = saved.getString("pending", "");
      pendingError = saved.getString("pendingError", "");
      query = saved.getString("query", "");
      filter = saved.getString("filter", "all");
      projectSearch = saved.getString("projectSearch", "");
      importUri = saved.getString("importUri", "");
      importName = saved.getString("importName", "");
      importMapping = saved.getIntArray("importMapping");
      importError = saved.getString("importError", "");
      importMoreFields = saved.getBoolean("importMoreFields");
      importNameEdited = saved.getBoolean("importNameEdited");
      projectSearchExpanded = saved.getBoolean("projectSearchExpanded");
      recordSearchExpanded = saved.getBoolean("recordSearchExpanded");
      ArrayList<String> ids = saved.getStringArrayList("selected");
      if (ids != null) selected.addAll(ids);
      exportCsv = saved.getBoolean("exportCsv");
      exportColumns = saved.getBooleanArray("exportColumns");
      if (exportColumns == null) exportColumns = new boolean[] {true, true, true, true};
      exportProject = saved.getLong("exportProject");
      if (exportColumns.length > 4
          && exportColumns.length != TableExporter.INVENTORY_HEADERS.length) {
        boolean[] expanded = new boolean[TableExporter.INVENTORY_HEADERS.length];
        java.util.Arrays.fill(expanded, true);
        System.arraycopy(
            exportColumns, 0, expanded, 0, Math.min(exportColumns.length, expanded.length));
        exportColumns = expanded;
      }
      exportScope = saved.getInt("exportScope");
      exportQuery = saved.getString("exportQuery", "");
      exportFilter = saved.getString("exportFilter", "all");
      exportFilename = saved.getString("exportFilename", "");
      ArrayList<String> ex = saved.getStringArrayList("exportIds");
      if (ex != null) exportIds = ex;
      printReturnTab = saved.getInt("printReturnTab", 3);
      printSource = saved.getInt("printSource");
      printStart = saved.getString("printStart", "1");
      printQuantity = saved.getString("printQuantity", "1");
      printCopies = saved.getString("printCopies", "1");
      printPreviewIndex = saved.getInt("printPreviewIndex");
      ArrayList<String> printSelection = saved.getStringArrayList("printIds");
      if (printSelection != null) printIds = printSelection;
      namingPrefix = saved.getString("namingPrefix", "");
      namingDigits = saved.getString("namingDigits", "3");
      namingStart = saved.getString("namingStart", "1");
      editingRecordId = saved.getString("editingRecordId", "");
      recordSn = saved.getString("recordSn", "");
      recordNumber = saved.getString("recordNumber", "");
      recordDate = saved.getString("recordDate", "");
      recordReturnTab = saved.getInt("recordReturnTab", 2);
    }
    if (store.project(projectId) == null) projectId = store.projects().get(0).id;
    loadTemplate();
    scanner = new ScannerBridge(this, this::onScan);
    printer = new PrinterController(store, this::printerChanged);
    render();
    if ((tab == 9 || tab == 11) && !importUri.isEmpty()) readImport(false);
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    out.putString("importUri", importUri);
    out.putString("importName", importName);
    out.putIntArray("importMapping", importMapping);
    out.putString("importError", importError);
    out.putBoolean("importMoreFields", importMoreFields);
    out.putBoolean("importNameEdited", importNameEdited);
    out.putLong("project", projectId);
    out.putInt("tab", tab);
    out.putBoolean("paused", scanPaused);
    out.putString("pending", pending);
    out.putString("pendingError", pendingError);
    out.putString("query", query);
    out.putString("filter", filter);
    out.putString("projectSearch", projectSearch);
    out.putBoolean("projectSearchExpanded", projectSearchExpanded);
    out.putBoolean("recordSearchExpanded", recordSearchExpanded);
    out.putStringArrayList("selected", new ArrayList<>(selected));
    out.putBoolean("exportCsv", exportCsv);
    out.putBooleanArray("exportColumns", exportColumns);
    out.putLong("exportProject", exportProject);
    out.putInt("exportScope", exportScope);
    out.putString("exportQuery", exportQuery);
    out.putString("exportFilter", exportFilter);
    out.putString("exportFilename", exportFilename);
    out.putStringArrayList("exportIds", exportIds);
    out.putInt("printReturnTab", printReturnTab);
    out.putInt("printSource", printSource);
    out.putString("printStart", printStart);
    out.putString("printQuantity", printQuantity);
    out.putString("printCopies", printCopies);
    out.putInt("printPreviewIndex", printPreviewIndex);
    out.putStringArrayList("printIds", printIds);
    out.putString("namingPrefix", namingPrefix);
    out.putString("namingDigits", namingDigits);
    out.putString("namingStart", namingStart);
    out.putString("editingRecordId", editingRecordId);
    out.putString("recordSn", recordSn);
    out.putString("recordNumber", recordNumber);
    out.putString("recordDate", recordDate);
    out.putInt("recordReturnTab", recordReturnTab);
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    startScanner();
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    if (BuildConfig.AUTO_PIN_SHORTCUT && !prefs.getBoolean("desktopShortcutPrompted", false)) {
      ui.postDelayed(
          () -> {
            if (resumed && !isFinishing() && !isDestroyed() && hasWindowFocus())
              requestHomeShortcut(false);
          },
          600);
    }
  }

  @Override
  protected void onPause() {
    resumed = false;
    warningSound.stop();
    hideSuccessNotice();
    scanner.stop();
    if (templateDirty) saveTemplate(false);
    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    warningSound.close();
    scanner.stop();
    printer.close();
    ui.removeCallbacksAndMessages(null);
    io.shutdown();
    store.close();
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (tab == 11) {
      cancelImport();
      return;
    }
    if (tab == 9) {
      navigate(11);
      return;
    }
    if (tab == 10) {
      navigate(0);
      return;
    }
    if (tab == 8) {
      navigate(2);
      return;
    }
    if (tab == 7) {
      navigate(recordReturnTab);
      return;
    }
    if (tab == 6) {
      navigate(4);
      return;
    }
    if (tab == 5) {
      navigate(printReturnTab);
      return;
    }
    if (tab != 0) {
      navigate(0);
      return;
    }
    super.onBackPressed();
  }

  private void startScanner() {
    if (resumed && prefs.getInt("scanMode", 0) == 0)
      scanner.start(
          prefs.getString("scanAction", ScannerBridge.DEFAULT_ACTION),
          prefs.getString("scanKey", ScannerBridge.DEFAULT_KEY));
    else scanner.stop();
  }

  @Override
  public boolean dispatchKeyEvent(android.view.KeyEvent event) {
    int key = event.getKeyCode();
    if (event.getAction() == android.view.KeyEvent.ACTION_DOWN
        && event.getRepeatCount() == 0
        && (key == android.view.KeyEvent.KEYCODE_F11
            || key == android.view.KeyEvent.KEYCODE_F12
            || key == android.view.KeyEvent.KEYCODE_CAMERA)) beginScan();
    return super.dispatchKeyEvent(event);
  }

  private void beginScan() {
    if (!resumed || tab != 0 || scanPaused || !hasWindowFocus() || project().archived) return;
    if (pending.isEmpty() && pendingError.isEmpty()) return;
    pending = pendingError = "";
    render();
  }

  private Project project() {
    return store.project(projectId);
  }

  private void loadTemplate() {
    try {
      template = LabelTemplate.fromJson(project().template);
    } catch (IllegalArgumentException e) {
      template = LabelTemplate.defaults();
      toast(e.getMessage());
    }
    templateDirty = false;
    editingLine = 0;
  }

  private boolean saveTemplate(boolean announce) {
    try {
      template.validate();
      store.saveTemplate(projectId, template.toJson());
      templateDirty = false;
      if (announce) toast("模板已保存到当前项目");
      return true;
    } catch (RuntimeException e) {
      toast(e.getMessage());
      return false;
    }
  }

  private void navigate(int next) {
    if (templateDirty && !saveTemplate(false)) return;
    hideKeyboard();
    tab = next;
    render();
  }

  private void hideKeyboard() {
    if (getCurrentFocus() != null)
      ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
  }

  private void selectProject(long id) {
    if (templateDirty && !saveTemplate(false)) return;
    projectId = id;
    prefs.edit().putLong("project", id).apply();
    selected.clear();
    pending = "";
    pendingError = "";
    query = "";
    recordSearchExpanded = false;
    limit = 100;
    loadTemplate();
    tab = 0;
    render();
  }

  private int dp(float value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  private GradientDrawable shape(int color, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private LinearLayout col() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private void add(LinearLayout parent, View child) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = dp(10);
    parent.addView(child, lp);
  }

  private void weight(LinearLayout parent, View child) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    if (parent.getChildCount() > 0) lp.leftMargin = dp(8);
    parent.addView(child, lp);
  }

  private TextView text(String value, float size, int color, boolean bold) {
    TextView t = new TextView(this);
    t.setText(value);
    t.setTextSize(size * designScale());
    t.setTextColor(color);
    t.setIncludeFontPadding(false);
    if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    return t;
  }

  private TextView text(String value, float size) {
    return text(value, size, INK, false);
  }

  private LinearLayout panel(int color) {
    LinearLayout p = col();
    p.setPadding(dp(14), dp(12), dp(14), dp(12));
    p.setBackground(shape(color, 12));
    return p;
  }

  private float designScale() {
    return Math.min(
        1f,
        getResources().getDisplayMetrics().widthPixels
            / getResources().getDisplayMetrics().density
            / 390f);
  }

  private GradientDrawable outlined(int color, int border, int radius) {
    GradientDrawable background = shape(color, radius);
    background.setStroke(dp(1), border);
    return background;
  }

  private ImageView icon(int resource, int color, int size) {
    ImageView view = new ImageView(this);
    view.setImageResource(resource);
    view.setColorFilter(color);
    view.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
    view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    return view;
  }

  private void buttonIcon(Button button, int resource) {
    android.graphics.drawable.Drawable drawable = getDrawable(resource).mutate();
    drawable.setTint(button.getCurrentTextColor());
    drawable.setBounds(0, 0, dp(17), dp(17));
    button.setCompoundDrawablesRelative(drawable, null, null, null);
    button.setCompoundDrawablePadding(dp(7));
  }

  private Button button(String label, boolean primary, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setTextSize(13 * designScale());
    b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
    b.setAllCaps(false);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(dp(44));
    b.setMinimumHeight(dp(44));
    b.setIncludeFontPadding(false);
    b.setPadding(dp(14), dp(10), dp(14), dp(10));
    b.setStateListAnimator(null);
    b.setElevation(0);
    b.setTextColor(primary ? Color.WHITE : INK);
    b.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x18215DE7),
            primary ? shape(BLUE, 8) : outlined(Color.WHITE, LINE, 8),
            null));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private Button chip(String label, boolean active, Runnable action) {
    Button button = button(label, false, action);
    button.setTextSize(11 * designScale());
    button.setMinHeight(dp(28));
    button.setMinimumHeight(dp(28));
    button.setPadding(dp(10), dp(6), dp(10), dp(6));
    button.setTextColor(active ? BLUE : MUTED);
    button.setBackground(shape(active ? SOFT : Color.WHITE, 5));
    return button;
  }

  private void gap(LinearLayout parent, int height) {
    parent.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private void line(LinearLayout parent) {
    View rule = new View(this);
    rule.setBackgroundColor(LINE);
    parent.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  private void settingRow(LinearLayout parent, String name, String value, Runnable action) {
    settingRow(parent, name, value, null, action);
  }

  private void settingRow(
      LinearLayout parent, String name, String value, String description, Runnable action) {
    LinearLayout item = row();
    item.setPadding(dp(14), dp(14), dp(14), dp(14));
    if (description == null) weight(item, text(name, 13, INK, false));
    else {
      LinearLayout caption = col();
      caption.addView(text(name, 13, INK, false));
      gap(caption, 4);
      caption.addView(text(description, 10, MUTED, false));
      weight(item, caption);
    }
    item.addView(text(value, 11, MUTED, false));
    if (action != null) {
      ImageView arrow = icon(R.drawable.ui_chevron, MUTED, 14);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(14), dp(14));
      lp.leftMargin = dp(6);
      item.addView(arrow, lp);
      item.setContentDescription(name);
      item.setOnClickListener(v -> action.run());
    }
    parent.addView(item);
  }

  private EditText input(String value, String hint, boolean number) {
    EditText e = new EditText(this);
    e.setText(value);
    e.setHint(hint);
    e.setTextSize(13 * designScale());
    e.setIncludeFontPadding(false);
    e.setMinimumHeight(dp(42));
    e.setTextColor(INK);
    e.setSingleLine(true);
    e.setSelectAllOnFocus(false);
    e.setPadding(dp(12), dp(10), dp(12), dp(10));
    e.setBackground(outlined(Color.WHITE, LINE, 8));
    e.setInputType(
        number
            ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
            : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    return e;
  }

  private void searchControls(LinearLayout anchor, boolean projects, Runnable changed) {
    LinearLayout searchRow = row();
    searchRow.setTag(projects ? "project-search-row" : "record-search-row");
    searchRow.setVisibility(
        (projects ? projectSearchExpanded : recordSearchExpanded) ? View.VISIBLE : View.GONE);
    EditText search =
        input(
            projects ? projectSearch : query,
            projects
                ? "搜索项目名称或编号"
                : project().inventory ? "搜索 SN / 资产ID / 名称 / 分类" : "搜索 SN / 设备名称",
            false);
    search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    search.setOnEditorActionListener(
        (view, action, event) -> {
          if (action != EditorInfo.IME_ACTION_SEARCH) return false;
          hideKeyboard();
          return true;
        });
    weight(searchRow, search);
    Button cancel =
        chip(
            "取消",
            false,
            () -> {
              hideKeyboard();
              if (projects) projectSearchExpanded = false;
              else recordSearchExpanded = false;
              search.setText("");
              search.clearFocus();
              body.requestFocus();
              searchRow.setVisibility(View.GONE);
            });
    cancel.setContentDescription(projects ? "取消项目搜索" : "取消记录搜索");
    searchRow.addView(cancel);
    add(body, searchRow);
    search.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            if (projects) projectSearch = s.toString();
            else {
              query = s.toString();
              limit = 100;
            }
            changed.run();
          }

          public void afterTextChanged(Editable e) {}
        });
    ImageButton open = new ImageButton(this);
    open.setImageResource(R.drawable.ui_search);
    open.setImageTintList(ColorStateList.valueOf(BLUE));
    open.setPadding(dp(11), dp(11), dp(11), dp(11));
    open.setBackground(
        new RippleDrawable(ColorStateList.valueOf(0x18215DE7), shape(Color.WHITE, 10), null));
    open.setContentDescription(projects ? "搜索项目" : "搜索记录");
    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
    iconParams.leftMargin = dp(10);
    anchor.addView(open, iconParams);
    open.setOnClickListener(
        v -> {
          if (projects) projectSearchExpanded = true;
          else recordSearchExpanded = true;
          searchRow.setVisibility(View.VISIBLE);
          search.requestFocus();
          search.post(
              () ->
                  ((android.view.inputmethod.InputMethodManager)
                          getSystemService(INPUT_METHOD_SERVICE))
                      .showSoftInput(
                          search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
        });
  }

  private void toast(String message) {
    Toast.makeText(this, message == null ? "操作失败" : message, Toast.LENGTH_LONG).show();
  }

  private void header(String title, String subtitle) {
    LinearLayout heading = col();
    heading.addView(text(title, 20, INK, true));
    gap(heading, 5);
    heading.addView(text(subtitle, 10.5f, MUTED, false));
    add(body, heading);
  }

  private void render() {
    hideSuccessNotice();
    openProjectRow = null;
    openRecordRow = null;
    focusInput = null;
    LinearLayout root = col();
    root.setBackgroundColor(BG);
    if (Build.VERSION.SDK_INT >= 35) {
      root.setOnApplyWindowInsetsListener(
          (view, insets) -> {
            android.graphics.Insets bars =
                insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout()
                        | android.view.WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
          });
    }
    ScrollView scroll = new ScrollView(this);
    scroll.setTag("content-scroll");
    scroll.setFillViewport(false);
    scroll.setVerticalScrollBarEnabled(false);
    body = col();
    body.setFocusableInTouchMode(true);
    body.setPadding(dp(18), dp(12), dp(18), dp(8));
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    footer = col();
    footer.setTag("page-footer");
    footer.setPadding(dp(18), dp(6), dp(18), dp(2));
    line(footer);
    gap(footer, 5);
    switch (tab) {
      case 0:
        scanPage();
        break;
      case 1:
        projectsPage();
        break;
      case 2:
        recordsPage();
        break;
      case 3:
        labelPage();
        break;
      case 4:
        settingsPage();
        break;
      case 5:
        printPage();
        break;
      case 6:
        namingPage();
        break;
      case 7:
        recordEditPage();
        break;
      case 8:
        exportPage();
        break;
      case 9:
        importPage();
        break;
      case 10:
        inventorySummaryPage();
        break;
      case 11:
        importMappingPage();
        break;
      default:
        tab = 0;
        scanPage();
    }
    if (footer.getChildCount() > 0) root.addView(footer);
    LinearLayout navigation = col();
    navigation.setTag("bottom-navigation");
    navigation.setBackgroundColor(Color.WHITE);
    line(navigation);
    LinearLayout nav = row();
    nav.setPadding(dp(10), dp(6), dp(10), dp(6));
    String[] titles = {"扫码", "项目", "记录", "标签", "设置"};
    int[] icons = {
      R.drawable.ui_scan,
      R.drawable.ui_folder,
      R.drawable.ui_records,
      R.drawable.ui_tag,
      R.drawable.ui_settings
    };
    for (int i = 0; i < 5; i++) {
      final int index = i;
      boolean active =
          i
              == (tab == 5
                  ? 3
                  : tab == 6
                      ? 4
                      : tab == 7
                          ? recordReturnTab
                          : tab == 8 ? 2 : (tab == 9 || tab == 11) ? 1 : tab == 10 ? 2 : tab);
      LinearLayout item = col();
      item.setGravity(Gravity.CENTER);
      if (active) item.setBackground(shape(SOFT, 8));
      item.addView(icon(icons[i], active ? BLUE : MUTED, 21));
      gap(item, 4);
      TextView navLabel = text(titles[i], 10.5f, active ? BLUE : MUTED, active);
      navLabel.setGravity(Gravity.CENTER);
      item.addView(navLabel, new LinearLayout.LayoutParams(-1, -2));
      item.setContentDescription(titles[i]);
      item.setOnClickListener(v -> navigate(index));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1);
      if (i > 0) lp.leftMargin = dp(4);
      nav.addView(item, lp);
    }
    navigation.addView(nav);
    root.addView(navigation);
    screen = new FrameLayout(this);
    screen.addView(root, new FrameLayout.LayoutParams(-1, -1));
    setContentView(screen);
    body.requestFocus();
    if (focusInput != null) focusInput.requestFocus();
  }

  private void projectPickerCard() {
    LinearLayout picker = row();
    picker.setPadding(dp(12), dp(10), dp(12), dp(10));
    picker.setBackground(shape(Color.WHITE, 9));
    LinearLayout tile = col();
    tile.setGravity(Gravity.CENTER);
    tile.setBackground(shape(SOFT, 7));
    tile.addView(icon(R.drawable.ui_folder, BLUE, 21));
    picker.addView(tile, new LinearLayout.LayoutParams(dp(34), dp(34)));
    LinearLayout title = col();
    title.addView(text("当前项目", 10, MUTED, false));
    gap(title, 4);
    TextView name = text(project().name, 14, INK, true);
    name.setMaxLines(1);
    name.setEllipsize(TextUtils.TruncateAt.END);
    title.addView(name);
    weight(picker, title);
    picker.addView(icon(R.drawable.ui_chevrons, MUTED, 16));
    picker.setContentDescription("选择当前项目");
    picker.setOnClickListener(v -> showProjectPicker());
    add(body, picker);
  }

  private void showProjectPicker() {
    List<Project> ps = store.projects();
    String[] names = new String[ps.size()];
    for (int i = 0; i < ps.size(); i++)
      names[i] =
          ps.get(i).name + "  ·  " + ps.get(i).count + " 台" + (ps.get(i).archived ? "（已归档）" : "");
    new AlertDialog.Builder(this)
        .setTitle("选择项目")
        .setItems(names, (d, w) -> selectProject(ps.get(w).id))
        .setNeutralButton("新建项目", (d, w) -> newProject())
        .setNegativeButton("取消", null)
        .show();
  }

  private void scanPage() {
    Project p = project();
    header(
        p.inventory ? "扫码盘点" : "扫码登记",
        p.inventory ? "按项目清单核对 · 扫码自动保存盘点结果" : "PDA 设备登记工具 · 本机离线保存");
    projectPickerCard();
    LinearLayout metrics = row();
    LinearLayout count = col();
    count.addView(text(p.inventory ? "已盘点 / 应盘点" : "当前项目累计 / 台", 10.5f, MUTED, false));
    gap(count, 2);
    count.addView(
        text(p.inventory ? p.checked + " / " + p.count : String.valueOf(p.count), 38, INK, true));
    weight(metrics, count);
    LinearLayout daily = col();
    daily.setGravity(Gravity.END);
    TextView today =
        text(p.inventory ? "未盘  " + (p.count - p.checked) : "今日  " + p.today, 11, MUTED, false);
    today.setGravity(Gravity.END);
    daily.addView(today);
    gap(daily, 7);
    TextView session =
        text(p.inventory ? "项目盘点" : "本轮  " + sessions.getOrDefault(projectId, 0), 11, BLUE, true);
    session.setGravity(Gravity.END);
    daily.addView(session);
    weight(metrics, daily);
    add(body, metrics);
    add(body, recentScanRecord(p));
    LinearLayout waiting = col();
    waiting.setTag("scan-waiting");
    waiting.setPadding(dp(14), dp(12), dp(14), dp(12));
    waiting.setGravity(Gravity.CENTER);
    waiting.setBackground(outlined(Color.WHITE, 0xFFD4E0FD, 12));
    LinearLayout status = row();
    status.setGravity(Gravity.CENTER);
    status.addView(
        text(
            p.archived
                ? "项目已归档"
                : scanPaused
                    ? (p.inventory ? "盘点已暂停" : "登记已暂停")
                    : !pending.isEmpty() && pendingError.isEmpty()
                        ? (p.inventory ? "●  已盘点" : "●  已录入")
                        : (p.inventory ? "●  待盘点" : "●  待录入"),
            11,
            p.archived || scanPaused ? MUTED : GREEN,
            false));
    if (!pendingError.isEmpty()) {
      TextView duplicate = text(pendingError, 11, 0xFFB54708, false);
      LinearLayout.LayoutParams flag = new LinearLayout.LayoutParams(-2, -2);
      flag.leftMargin = dp(10);
      status.addView(duplicate, flag);
    }
    waiting.addView(status);
    gap(waiting, 6);
    waiting.addView(icon(R.drawable.ui_scan, scanPaused ? MUTED : BLUE, 40));
    gap(waiting, 7);
    TextView sn = text(pending.isEmpty() ? "等待扫码…" : pending, 16, INK, true);
    sn.setTag("scan-result");
    sn.setMaxLines(2);
    sn.setGravity(Gravity.CENTER);
    waiting.addView(sn, new LinearLayout.LayoutParams(-1, -2));
    gap(waiting, 6);
    String hintText =
        p.archived
            ? "项目已归档，恢复后可继续登记"
            : scanPaused
                ? "点击继续登记后可扫码"
                : !pendingError.isEmpty()
                    ? "该 SN 已登记，按扫码键继续"
                    : pending.isEmpty() ? "按 PDA 侧键扫码，识别后自动保存" : "已自动保存，按扫码键继续";
    if (p.inventory)
      hintText =
          p.archived
              ? "项目已归档，恢复后可继续盘点"
              : scanPaused
                  ? "点击继续盘点后可扫码"
                  : pendingError.equals("不属于当前项目")
                      ? "请核对项目或设备条码"
                      : pendingError.equals("已盘点")
                          ? "这台设备已盘过，数量不重复增加"
                          : pending.isEmpty() ? "扫描 SN 或资产编号，自动核对项目清单" : "已保存盘点结果，按扫码键继续";
    TextView hint = text(hintText, 10, MUTED, false);
    hint.setGravity(Gravity.CENTER);
    waiting.addView(hint);
    add(body, waiting);
    if (prefs.getInt("scanMode", 0) == 1) {
      focusInput = input("", "扫码结果输入到这里", false);
      focusInput.setShowSoftInputOnFocus(false);
      focusInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
      focusInput.setOnEditorActionListener(
          (v, action, event) -> {
            if (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) {
              if (event.getAction() == android.view.KeyEvent.ACTION_UP)
                register(v.getText().toString());
              return true;
            }
            if (action == EditorInfo.IME_ACTION_DONE) {
              register(focusInput.getText().toString());
              return true;
            }
            return false;
          });
      add(body, focusInput);
      add(body, button("登记输入内容", false, () -> register(focusInput.getText().toString())));
    }
    LinearLayout actions = row();
    Button pause =
        button(
            scanPaused ? (p.inventory ? "继续盘点" : "继续登记") : (p.inventory ? "暂停盘点" : "暂停登记"),
            false,
            () -> {
              scanPaused = !scanPaused;
              render();
            });
    buttonIcon(pause, R.drawable.ui_pause);
    weight(actions, pause);
    Button manual = button(p.inventory ? "手动核对" : "手动录入", true, this::manualEntry);
    buttonIcon(manual, R.drawable.ui_keyboard);
    weight(actions, manual);
    TextView rule =
        text("项目内 SN 查重 · 本地自动保存 · 下个名称 " + p.deviceName(p.nextNumber), 9.5f, MUTED, false);
    rule.setGravity(Gravity.CENTER);
    if (printer.active())
      add(
          body,
          button(
              "查看打印进度 " + printer.completed + " / " + printer.total(),
              false,
              this::showPrintProgress));
    if (p.inventory) {
      LinearLayout summary = row();
      weight(summary, text("仅核对当前项目 · 自动保存进度", 9.5f, MUTED, false));
      summary.addView(chip("盘点结果", false, () -> navigate(10)));
      add(footer, summary);
    } else add(footer, rule);
    add(footer, actions);
  }

  private View recentScanRecord(Project p) {
    if (p.inventory) return recentInventoryRecord(p);
    Record r = store.latestRecord(projectId);
    LinearLayout last = panel(0xFFE8F6F0);
    last.setTag("scan-recent-record");
    if (r == null) {
      last.addView(text("尚未登记设备", 13, INK, true));
      gap(last, 7);
      last.addView(text("按 PDA 侧键扫码，首台设备名称为 " + p.deviceName(p.nextNumber), 11, MUTED, false));
      return last;
    }
    LinearLayout title = row();
    title.addView(icon(R.drawable.ui_check, GREEN, 13));
    weight(title, text("最近登记成功", 10.5f, GREEN, true));
    String time = r.createdAt.length() > 11 ? r.createdAt.substring(11) : "";
    title.addView(text(time, 10, GREEN, false));
    ImageView edit = icon(R.drawable.ui_edit, GREEN, 14);
    LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(dp(14), dp(14));
    editParams.leftMargin = dp(8);
    title.addView(edit, editParams);
    last.addView(title);
    gap(last, 8);
    TextView saved = text("SN  " + r.sn, 15, INK, true);
    saved.setMaxLines(2);
    last.addView(saved);
    gap(last, 7);
    last.addView(text("设备名称（DevicesID）  " + r.deviceName(), 10.5f, MUTED, false));
    gap(last, 5);
    LinearLayout meta = row();
    weight(meta, text("登记日期  " + r.date, 10.5f, MUTED, false));
    meta.addView(text("点击编辑 · 右滑删除", 9, MUTED, false));
    last.addView(meta);
    return recordSwipe(last, r);
  }

  private void onScan(String value) {
    if (!resumed || tab != 0 || scanPaused || !hasWindowFocus() || project().archived) return;
    register(value);
  }

  private void register(String sn) {
    if (scanPaused) {
      toast("登记已暂停");
      return;
    }
    try {
      if (project().inventory) {
        inventoryScan(sn);
        return;
      }
      ScanResult result = store.register(projectId, sn);
      pending = result.record.sn;
      pendingError = result.duplicate ? "SN重复" : "";
      if (result.duplicate) warningFeedback();
      else warningSound.stop();
      if (!result.duplicate) {
        sessions.put(projectId, sessions.getOrDefault(projectId, 0) + 1);
        if (prefs.getBoolean("feedback", true)) feedback();
      }
      render();
      if (!result.duplicate) showSuccessNotice();
    } catch (RuntimeException e) {
      warningFeedback();
      toast(e.getMessage());
    }
  }

  private void showSuccessNotice() {
    hideSuccessNotice();
    successNotice = text(project().inventory ? "盘点成功" : "录入成功", 12, Color.WHITE, true);
    successNotice.setGravity(Gravity.CENTER);
    successNotice.setPadding(dp(13), dp(10), dp(13), dp(10));
    android.graphics.drawable.Drawable check = getDrawable(R.drawable.ui_check).mutate();
    check.setTint(Color.WHITE);
    check.setBounds(0, 0, dp(14), dp(14));
    successNotice.setCompoundDrawablesRelative(check, null, null, null);
    successNotice.setCompoundDrawablePadding(dp(6));
    successNotice.setBackground(shape(GREEN, 10));
    successNotice.setElevation(dp(2));
    successNotice.setClickable(false);
    successNotice.setFocusable(false);
    successNotice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    FrameLayout.LayoutParams layout =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
    layout.topMargin = dp(12);
    layout.setMarginEnd(dp(20));
    if (Build.VERSION.SDK_INT >= 35 && screen.getRootWindowInsets() != null)
      layout.topMargin +=
          screen
              .getRootWindowInsets()
              .getInsets(
                  android.view.WindowInsets.Type.systemBars()
                      | android.view.WindowInsets.Type.displayCutout())
              .top;
    screen.addView(successNotice, layout);
    ui.postDelayed(hideNotice, 1500);
  }

  private void hideSuccessNotice() {
    ui.removeCallbacks(hideNotice);
    if (successNotice != null) {
      if (successNotice.getParent() instanceof android.view.ViewGroup)
        ((android.view.ViewGroup) successNotice.getParent()).removeView(successNotice);
      successNotice = null;
    }
  }

  private void requestHomeShortcut(boolean manual) {
    try {
      if (HomeShortcut.isPinned(this)) {
        prefs.edit().putBoolean("desktopShortcutPrompted", true).apply();
        if (manual) toast("桌面图标已添加");
      } else if (HomeShortcut.request(this)) {
        prefs.edit().putBoolean("desktopShortcutPrompted", true).apply();
      } else {
        prefs.edit().putBoolean("desktopShortcutPrompted", true).apply();
        if (manual) toast("请从应用列表长按“PDA扫码登记”，拖到桌面");
      }
    } catch (RuntimeException e) {
      if (manual) toast("无法添加桌面图标：" + e.getMessage());
    }
  }

  private void warningFeedback() {
    if (prefs.getBoolean("errorFeedback", true)) warningSound.play();
  }

  private void feedback() {
    try {
      ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 45);
      tone.startTone(ToneGenerator.TONE_PROP_ACK, 90);
      getWindow().getDecorView().postDelayed(tone::release, 200);
      Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
      if (vib != null && vib.hasVibrator())
        vib.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
    } catch (RuntimeException ignored) {
    }
  }

  private void manualEntry() {
    EditText value = input("", project().inventory ? "SN / 资产编号" : "一维条码 SN", false);
    value.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle(project().inventory ? "手动核对条码" : "手动录入 SN")
            .setView(value)
            .setPositiveButton(project().inventory ? "核对" : "登记", null)
            .setNegativeButton("取消", null)
            .create();
    d.setOnShowListener(
        v -> {
          d.getButton(-1)
              .setOnClickListener(
                  b -> {
                    try {
                      RegistryStore.normalizeSn(value.getText().toString());
                      d.dismiss();
                      register(value.getText().toString());
                    } catch (IllegalArgumentException e) {
                      value.setError(e.getMessage());
                    }
                  });
          value.requestFocus();
          d.getWindow()
              .setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        });
    d.show();
  }

  private void projectsPage() {
    header("项目", "按项目登记，各自独立统计 · 右滑可删除");
    List<Project> ps = store.projects();
    int total = 0;
    for (Project p : ps) total += p.count;
    LinearLayout summary = row();
    add(body, text("全部登记 / 台", 10.5f, MUTED, false));
    weight(summary, text(String.valueOf(total), 36, INK, true));
    summary.addView(text(ps.size() + " 个项目", 11, BLUE, false));
    add(body, summary);
    LinearLayout projects = col();
    Runnable show =
        () -> {
          projects.removeAllViews();
          openProjectRow = null;
          String q = projectSearch.trim().toLowerCase(Locale.ROOT);
          for (Project p : ps) {
            String code = String.format(Locale.ROOT, "PRJ-%03d", p.id);
            if (!q.isEmpty()
                && !p.name.toLowerCase(Locale.ROOT).contains(q)
                && !code.toLowerCase(Locale.ROOT).contains(q)) continue;
            LinearLayout card = panel(Color.WHITE);
            card.setBackground(outlined(Color.WHITE, p.id == projectId ? 0xFFBDCEFD : LINE, 10));
            LinearLayout title = row();
            TextView name = text(p.name, 15, INK, true);
            name.setMaxLines(2);
            weight(title, name);
            TextView badge =
                text(
                    p.id == projectId ? "当前项目" : p.archived ? "已归档" : "进行中",
                    10,
                    p.archived ? MUTED : BLUE,
                    false);
            badge.setPadding(dp(6), dp(3), dp(6), dp(3));
            badge.setBackground(shape(p.archived ? BG : SOFT, 4));
            title.addView(badge);
            card.addView(title);
            gap(card, 9);
            LinearLayout amount = row();
            amount.addView(text(String.valueOf(p.count), 27, INK, true));
            weight(amount, text("台  ·  今日 +" + p.today, 11, MUTED, false));
            amount.addView(
                chip(
                    p.archived ? "恢复" : "归档",
                    false,
                    () ->
                        new AlertDialog.Builder(this)
                            .setTitle(p.archived ? "恢复项目？" : "归档项目？")
                            .setMessage("保留全部记录和编号，归档后停止录入。")
                            .setPositiveButton(
                                "确定",
                                (d, w) -> {
                                  store.archive(p.id, !p.archived);
                                  render();
                                })
                            .setNegativeButton("取消", null)
                            .show()));
            card.addView(amount);
            gap(card, 7);
            LinearLayout meta = row();
            weight(meta, text(code, 10, MUTED, false));
            meta.addView(
                text(
                    p.inventory
                        ? "盘点 " + p.checked + " / " + p.count
                        : "下个设备名称 " + p.deviceName(p.nextNumber),
                    10,
                    MUTED,
                    false));
            card.addView(meta);
            card.setContentDescription("进入项目 " + p.name);
            card.setOnClickListener(
                v -> {
                  selectProject(p.id);
                  if (p.archived) navigate(2);
                });
            Button remove = button("删除", true, () -> confirmDeleteProject(p.id));
            remove.setBackground(shape(0xFFE5484D, 10));
            remove.setContentDescription("删除项目 " + p.name);
            SwipeDeleteLayout swipe =
                new SwipeDeleteLayout(
                    this,
                    card,
                    remove,
                    opened -> {
                      if (openProjectRow != null && openProjectRow != opened)
                        openProjectRow.close();
                      openProjectRow = opened;
                    });
            swipe.setTag("project-" + p.id);
            add(projects, swipe);
          }
          if (projects.getChildCount() == 0) add(projects, text("没有匹配的项目", 12, MUTED, false));
        };
    searchControls(summary, true, show);
    add(body, projects);
    show.run();
    LinearLayout actions = row();
    weight(actions, button("导入项目", false, this::chooseImport));
    weight(actions, button("＋  新建项目", true, this::newProject));
    add(footer, actions);
  }

  private void confirmDeleteProject(long id) {
    if (openProjectRow != null) openProjectRow.close();
    if (printer.isPrintingProject(id)) {
      toast("该项目正在打印，请先完成或取消打印任务");
      return;
    }
    Project target = store.project(id);
    if (target == null) return;
    boolean last = store.projects().size() == 1;
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("删除项目？")
            .setMessage(
                "“"
                    + target.name
                    + "”及其中 "
                    + target.count
                    + " 条登记记录、标签模板将一并删除，删除后无法恢复。"
                    + (last ? "\n\n删除后将创建一个空的“默认项目”。" : ""))
            .setNegativeButton("取消", null)
            .setPositiveButton("删除项目", null)
            .create();
    dialog.setOnShowListener(
        v -> {
          dialog.getButton(-1).setTextColor(0xFFE5484D);
          dialog
              .getButton(-1)
              .setOnClickListener(
                  button -> {
                    if (printer.isPrintingProject(id)) {
                      toast("该项目正在打印，请先完成或取消打印任务");
                      return;
                    }
                    try {
                      long fallback = store.deleteProject(id);
                      sessions.remove(id);
                      if (id == projectId) {
                        projectId = fallback;
                        prefs.edit().putLong("project", fallback).apply();
                        selected.clear();
                        printIds.clear();
                        preparedPrint = null;
                        printSource = printPreviewIndex = 0;
                        printQuantity = printCopies = "1";
                        pending = pendingError = query = "";
                        limit = 100;
                        loadTemplate();
                      }
                      if (last) projectSearch = "";
                      dialog.dismiss();
                      render();
                      toast(last ? "项目已删除，已创建空的默认项目" : "项目已删除");
                    } catch (RuntimeException error) {
                      toast(error.getMessage());
                    }
                  });
        });
    dialog.show();
  }

  private void newProject() {
    EditText name = input("", "项目名称", false);
    LinearLayout form = panel(Color.WHITE);
    add(form, name);
    RadioGroup modes = new RadioGroup(this);
    RadioButton registration = new RadioButton(this);
    registration.setId(View.generateViewId());
    registration.setText("扫码登记 · 扫码新增设备");
    modes.addView(registration);
    RadioButton inventory = new RadioButton(this);
    inventory.setId(View.generateViewId());
    inventory.setText("扫码盘点 · 按已有清单核对");
    modes.addView(inventory);
    modes.check(registration.getId());
    add(form, modes);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("新建项目")
            .setView(form)
            .setPositiveButton("创建", null)
            .setNegativeButton("取消", null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(-1)
                .setOnClickListener(
                    b -> {
                      try {
                        long id = store.createProject(name.getText().toString());
                        store.setInventoryMode(
                            id, modes.getCheckedRadioButtonId() == inventory.getId());
                        d.dismiss();
                        selectProject(id);
                      } catch (RuntimeException e) {
                        name.setError(
                            e instanceof android.database.sqlite.SQLiteConstraintException
                                ? "项目名称已存在"
                                : e.getMessage());
                      }
                    }));
    d.show();
  }

  private void showProjectMode() {
    Project p = project();
    new AlertDialog.Builder(this)
        .setTitle("项目工作模式 · " + p.name)
        .setSingleChoiceItems(
            new String[] {"扫码登记：扫描后新增设备", "扫码盘点：仅核对当前项目清单"},
            p.inventory ? 1 : 0,
            (dialog, which) -> {
              if (printer.active()) {
                toast("请先完成或取消打印任务");
                return;
              }
              store.setInventoryMode(projectId, which == 1);
              pending = pendingError = "";
              selected.clear();
              query = "";
              filter = "all";
              recordSearchExpanded = false;
              scanPaused = false;
              dialog.dismiss();
              render();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void inventoryScan(String value) {
    pending = RegistryStore.normalizeSn(value);
    ScanResult result = store.checkInventory(projectId, pending);
    pendingError = result == null ? "不属于当前项目" : result.duplicate ? "已盘点" : "";
    if (result == null || result.duplicate) warningFeedback();
    else {
      warningSound.stop();
      if (prefs.getBoolean("feedback", true)) feedback();
    }
    render();
    if (result != null && !result.duplicate) showSuccessNotice();
  }

  private View recentInventoryRecord(Project p) {
    LinearLayout last = panel(0xFFE8F6F0);
    last.setTag("scan-recent-record");
    Record r = store.latestInventory(projectId);
    if (r == null) {
      last.addView(text("尚未盘点设备", 13, INK, true));
      gap(last, 7);
      last.addView(
          text(
              p.count == 0 ? "请先导入项目清单" : "应盘 " + p.count + " 台 · 扫描 SN 或资产编号开始",
              11,
              MUTED,
              false));
      return last;
    }
    LinearLayout title = row();
    title.addView(icon(R.drawable.ui_check, GREEN, 13));
    weight(title, text("最近盘点成功", 10.5f, GREEN, true));
    title.addView(text(r.checkedAt.substring(11), 10, GREEN, false));
    last.addView(title);
    gap(last, 8);
    last.addView(
        text(
            r.equipmentName.isEmpty() ? r.deviceName() : r.equipmentName + " · " + r.deviceName(),
            14,
            INK,
            true));
    gap(last, 7);
    TextView code = text("条码  " + r.sn, 12, INK, false);
    code.setMaxLines(2);
    last.addView(code);
    if (!r.category.isEmpty()) {
      gap(last, 5);
      last.addView(text("分类  " + r.category, 10.5f, MUTED, false));
    }
    if (!r.assetNumber.isEmpty() && !r.assetNumber.equals(r.sn)) {
      gap(last, 5);
      last.addView(text("资产编号  " + r.assetNumber, 10.5f, MUTED, false));
    }
    return last;
  }

  private void inventorySummaryPage() {
    Project p = project();
    header("盘点结果", p.name + " · 按分类与设备名称汇总");
    LinearLayout heading = panel(Color.WHITE);
    add(
        heading,
        text(
            "应盘 " + p.count + " 台   已盘 " + p.checked + " 台   未盘 " + (p.count - p.checked) + " 台",
            13,
            INK,
            true));
    heading.addView(text("清单外条码不纳入当前项目统计。", 10.5f, MUTED, false));
    add(body, heading);
    for (String[] group : store.inventoryGroups(projectId)) {
      LinearLayout card = panel(Color.WHITE);
      add(card, text(group[0], 14, INK, true));
      int total = Integer.parseInt(group[1]), checked = Integer.parseInt(group[2]);
      card.addView(
          text(
              "应盘 " + total + "   已盘 " + checked + "   未盘 " + (total - checked), 12, MUTED, false));
      add(body, card);
    }
    LinearLayout actions = row();
    actions.addView(
        button(
            "重新盘点",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("重新盘点当前项目？")
                    .setMessage("仅清除本项目的已盘点状态和时间，保留全部设备清单。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton(
                        "重新盘点",
                        (d, w) -> {
                          store.resetInventory(projectId);
                          pending = pendingError = "";
                          render();
                        })
                    .show()));
    weight(actions, button("导出结果", true, this::showExport));
    add(footer, actions);
  }

  private void chooseImport() {
    if (importBusy) {
      toast("请稍候");
      return;
    }
    Intent choose = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    choose.addCategory(Intent.CATEGORY_OPENABLE);
    choose.setType("*/*");
    choose.putExtra(
        Intent.EXTRA_MIME_TYPES,
        new String[] {
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "text/csv",
          "text/comma-separated-values",
          "application/csv"
        });
    try {
      startActivityForResult(choose, IMPORT_REQUEST);
    } catch (android.content.ActivityNotFoundException e) {
      toast("本设备缺少系统文件选择器");
    }
  }

  private void readImport(boolean suggestName) {
    if (importBusy) return;
    importBusy = true;
    importRows = null;
    importSource = null;
    String source = importUri;
    boolean restorePreview = tab == 9;
    render();
    io.execute(
        () -> {
          try (java.io.InputStream stream =
              getContentResolver().openInputStream(Uri.parse(source))) {
            if (stream == null) throw new java.io.IOException("无法读取文件");
            TableImporter.Source data = TableImporter.readSource(stream);
            runOnUiThread(
                () -> {
                  if (isDestroyed()) return;
                  importBusy = false;
                  importSource = data;
                  if (importMapping == null || importMapping.length != TableImporter.FIELDS.length)
                    importMapping = TableImporter.suggest(data);
                  if (suggestName) {
                    importName = "";
                    try (android.database.Cursor c =
                        getContentResolver()
                            .query(
                                Uri.parse(source),
                                new String[] {android.provider.OpenableColumns.DISPLAY_NAME},
                                null,
                                null,
                                null)) {
                      if (c != null && c.moveToFirst())
                        importName = c.getString(0).replaceFirst("(?i)\\.(xlsx|csv)$", "");
                    } catch (RuntimeException ignored) {
                    }
                    if (importName.isEmpty()) importName = "盘点项目";
                  }
                  if (restorePreview && tab == 9) previewImport();
                  else if (tab == 11) render();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  importBusy = false;
                  if (isDestroyed()) return;
                  importUri = "";
                  if (tab == 9 || tab == 11) navigate(1);
                  new AlertDialog.Builder(this)
                      .setTitle("未导入项目")
                      .setMessage(e.getMessage())
                      .setPositiveButton("知道了", null)
                      .show();
                });
          }
        });
  }

  private void cancelImport() {
    if (importBusy) {
      toast("正在处理，请稍候");
      return;
    }
    importUri = "";
    importRows = null;
    importSource = null;
    importMapping = null;
    importError = "";
    navigate(1);
  }

  private void importMappingPage() {
    header("选择导入字段", "1 / 2 · 自动识别后，可点击修改对应列");
    if (importBusy || importSource == null) {
      add(body, text("正在读取表格…", 13, MUTED, false));
      return;
    }
    add(
        body,
        text(
            "已读取 " + importSource.rowCount() + " 行 · " + importSource.headers.size() + " 列",
            12,
            MUTED,
            false));
    add(body, text("资产ID 与 SN 至少选一项。每行一台，数量留空按 1 台。", 11, MUTED, false));
    for (int i = 0; i < TableImporter.FIELDS.length; i++) {
      if (i >= TableImporter.DATE && !importMoreFields) continue;
      final int field = i;
      int column = importMapping[i];
      LinearLayout item = col();
      LinearLayout heading = row();
      weight(heading, text(TableImporter.FIELDS[i], 13, INK, true));
      String label =
          column >= 0
              ? importSource.columnLabel(column)
              : i == TableImporter.QUANTITY ? "默认 1" : "不导入";
      Button choose = chip(label + "  ▾", column >= 0, () -> chooseImportField(field));
      choose.setContentDescription("选择导入字段 " + TableImporter.FIELDS[i]);
      choose.setMaxLines(1);
      choose.setEllipsize(android.text.TextUtils.TruncateAt.END);
      heading.addView(choose, new LinearLayout.LayoutParams(dp(195), dp(34)));
      item.addView(heading);
      gap(item, 3);
      item.addView(
          text(
              column >= 0
                  ? "示例：" + importSource.sample(column)
                  : i == TableImporter.QUANTITY ? "未选择数量列时，每行按 1 台" : "未对应此字段",
              10.5f,
              MUTED,
              false));
      add(body, item);
    }
    add(
        body,
        chip(
            importMoreFields ? "收起日期 / 项目字段" : "更多字段：登记日期 / 所属项目",
            false,
            () -> {
              importMoreFields = !importMoreFields;
              render();
            }));
    if (!importError.isEmpty()) add(body, text(importError, 11, 0xFFAE4C00, false));
    LinearLayout actions = row();
    actions.addView(button("取消", false, this::cancelImport));
    weight(actions, button("预览清单", true, this::previewImport));
    add(footer, actions);
  }

  private void chooseImportField(int field) {
    String[] options = new String[importSource.headers.size() + 1];
    options[0] = field == TableImporter.QUANTITY ? "不选择 · 每行默认 1 台" : "不导入此字段";
    for (int i = 0; i < importSource.headers.size(); i++)
      options[i + 1] = importSource.columnLabel(i);
    new AlertDialog.Builder(this)
        .setTitle("选择“" + TableImporter.FIELDS[field] + "”对应列")
        .setSingleChoiceItems(
            options,
            importMapping[field] + 1,
            (dialog, which) -> {
              importMapping[field] = which - 1;
              importRows = null;
              importError = "";
              dialog.dismiss();
              render();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void previewImport() {
    if (importBusy || importSource == null) return;
    try {
      TableImporter.validateMapping(importSource, importMapping);
    } catch (IllegalArgumentException e) {
      importError = e.getMessage();
      tab = 11;
      render();
      toast(importError);
      return;
    }
    TableImporter.Source source = importSource;
    int[] mapping = importMapping.clone();
    importBusy = true;
    render();
    io.execute(
        () -> {
          try {
            TableImporter.Result result = TableImporter.map(source, mapping);
            runOnUiThread(
                () -> {
                  importBusy = false;
                  if (isDestroyed()) return;
                  importRows = result.rows;
                  importError = "";
                  if (!importNameEdited && !result.projectName.isEmpty())
                    importName = result.projectName;
                  if (tab == 9 || tab == 11) {
                    tab = 9;
                    render();
                  }
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  importBusy = false;
                  if (isDestroyed()) return;
                  importError = e.getMessage();
                  if (tab == 9 || tab == 11) {
                    tab = 11;
                    render();
                    new AlertDialog.Builder(this)
                        .setTitle("请检查导入清单")
                        .setMessage(importError)
                        .setPositiveButton("知道了", null)
                        .show();
                  }
                });
          }
        });
  }

  private void importPage() {
    header("导入项目清单", "2 / 2 · 预览确认后再创建项目");
    if (importBusy || importRows == null) {
      add(body, text("正在读取并检查清单…", 13, MUTED, false));
      return;
    }
    add(body, text("项目名称", 12, INK, true));
    add(
        body,
        recordInput(
            importName,
            "导入项目名称",
            false,
            value -> {
              importName = value;
              importNameEdited = true;
            }));
    LinearLayout summary = panel(SOFT);
    add(summary, text("已识别 " + importRows.size() + " 台设备", 17, BLUE, true));
    summary.addView(text("创建为扫码盘点项目，导入后即可扫码核对。", 11, MUTED, false));
    add(body, summary);
    Map<String, Integer> groups = new LinkedHashMap<>();
    for (TableImporter.Row row : importRows)
      groups.put(
          (row.category.isEmpty() ? "" : row.category + " · ") + row.equipmentName,
          groups.getOrDefault(
                  (row.category.isEmpty() ? "" : row.category + " · ") + row.equipmentName, 0)
              + 1);
    int displayed = 0;
    for (Map.Entry<String, Integer> group : groups.entrySet()) {
      if (displayed++ >= 20) {
        add(body, text("其余 " + (groups.size() - 20) + " 类设备将在导入后显示", 11, MUTED, false));
        break;
      }
      LinearLayout item = row();
      weight(item, text(group.getKey().isEmpty() ? "未填写设备名称" : group.getKey(), 13, INK, true));
      item.addView(text(group.getValue() + " 台", 12, BLUE, false));
      add(body, item);
    }
    add(body, text("仅核对本项目的 SN / 资产编号。清单外条码提示“不属于当前项目”。", 11, MUTED, false));
    LinearLayout actions = row();
    actions.addView(button("上一步", false, () -> navigate(11)));
    weight(
        actions,
        button(
            "导入并开始盘点",
            true,
            () -> {
              if (importBusy) return;
              String name = importName.trim();
              List<TableImporter.Row> rows = new ArrayList<>(importRows);
              if (name.isEmpty() || name.length() > 60) {
                toast("项目名称为 1–60 个字符");
                return;
              }
              importBusy = true;
              render();
              io.execute(
                  () -> {
                    try (RegistryStore writer = new RegistryStore(getApplicationContext())) {
                      long id = writer.importProject(name, rows);
                      runOnUiThread(
                          () -> {
                            importBusy = false;
                            if (isDestroyed()) return;
                            importRows = null;
                            importSource = null;
                            importMapping = null;
                            importUri = "";
                            scanPaused = false;
                            selectProject(id);
                            toast("已导入 " + rows.size() + " 台设备");
                          });
                    } catch (Exception e) {
                      runOnUiThread(
                          () -> {
                            importBusy = false;
                            if (isDestroyed()) return;
                            if (tab == 9) render();
                            toast(
                                e instanceof android.database.sqlite.SQLiteConstraintException
                                    ? "项目名称已存在，请修改名称"
                                    : e.getMessage());
                          });
                    }
                  });
            }));
    add(footer, actions);
  }

  private void recordsPage() {
    header(
        project().inventory ? "盘点记录" : "登记记录",
        project().inventory ? "按 SN 或资产编号核对 · 已盘和未盘分别查看" : "点击记录编辑 · 右滑删除 · 勾选可打印");
    projectPickerCard();
    LinearLayout filters = row();
    LinearLayout filterOptions = row();
    String[] names = {"全部 " + project().count, "今日 " + project().today, "未打印"},
        values = {"all", "today", "unprinted"};
    if (project().inventory) {
      names =
          new String[] {
            "全部 " + project().count,
            "已盘 " + project().checked,
            "未盘 " + (project().count - project().checked)
          };
      values = new String[] {"all", "checked", "missing"};
    }
    for (int i = 0; i < 3; i++) {
      final String f = values[i];
      filterOptions.addView(
          chip(
              names[i],
              filter.equals(f),
              () -> {
                filter = f;
                limit = 100;
                render();
              }));
    }
    weight(filters, filterOptions);
    add(body, filters);
    searchControls(filters, false, this::renderRecordRows);
    LinearLayout selectionBar = row();
    Button selectAll =
        chip(
            "全选",
            false,
            () -> {
              for (Record r : store.records(projectId, query, filter, limit)) selected.add(r.id);
              renderRecordRows();
            });
    selectionBar.addView(selectAll);
    buttonIcon(selectAll, R.drawable.ui_select_all);
    selectAll.setTextColor(INK);
    selectAll.setTextSize(12 * designScale());
    selectAll.setBackgroundColor(Color.TRANSPARENT);
    View divider = new View(this);
    divider.setBackgroundColor(LINE);
    LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(dp(1), dp(14));
    dividerParams.setMargins(dp(4), 0, dp(4), 0);
    selectionBar.addView(divider, dividerParams);
    Button clearSelection =
        chip(
            "清空",
            false,
            () -> {
              selected.clear();
              renderRecordRows();
            });
    clearSelection.setTextColor(INK);
    clearSelection.setTextSize(12 * designScale());
    clearSelection.setBackgroundColor(Color.TRANSPARENT);
    selectionBar.addView(clearSelection);
    selectionText = text("", 11, BLUE, true);
    selectionText.setGravity(Gravity.END);
    weight(selectionBar, selectionText);
    add(footer, selectionBar);
    recordList = col();
    add(body, recordList);
    LinearLayout actions = row();
    weight(actions, button("导出表格", false, this::showExport));
    recordPrintButton = button("打印所选", true, this::showPrintSetup);
    weight(actions, recordPrintButton);
    add(footer, actions);
    renderRecordRows();
  }

  private void renderRecordRows() {
    if (recordList == null) return;
    openRecordRow = null;
    recordList.removeAllViews();
    List<Record> records = store.records(projectId, query, filter, limit);
    for (Record r : records) {
      LinearLayout card = row();
      card.setPadding(dp(8), dp(12), dp(12), dp(12));
      card.setBackground(outlined(Color.WHITE, selected.contains(r.id) ? 0xFFBDCEFD : LINE, 9));
      CheckBox check = new CheckBox(this);
      check.setButtonTintList(ColorStateList.valueOf(BLUE));
      check.setChecked(selected.contains(r.id));
      check.setPadding(0, 0, 0, 0);
      check.setMinWidth(0);
      check.setMinimumWidth(0);
      check.setContentDescription("选择设备 " + r.deviceName());
      card.addView(check, new LinearLayout.LayoutParams(dp(30), dp(38)));
      LinearLayout details = col();
      LinearLayout title = row();
      TextView sn = text(r.sn, 14, INK, true);
      sn.setMaxLines(2);
      weight(title, sn);
      TextView printed =
          text(
              project().inventory
                  ? (r.checkedAt.isEmpty() ? "未盘点" : "已盘点")
                  : (r.printed > 0 ? "已打印" : "未打印"),
              9.5f,
              project().inventory
                  ? (r.checkedAt.isEmpty() ? MUTED : GREEN)
                  : r.printed > 0 ? GREEN : MUTED,
              false);
      printed.setPadding(dp(5), dp(3), dp(5), dp(3));
      printed.setBackground(
          shape(
              (project().inventory ? !r.checkedAt.isEmpty() : r.printed > 0) ? 0xFFE8F6F0 : BG, 4));
      title.addView(printed);
      details.addView(title);
      gap(details, 6);
      details.addView(
          text(
              project().inventory
                  ? (r.equipmentName.isEmpty() ? "设备编号" : r.equipmentName) + " · " + r.deviceName()
                  : "设备名称（DevicesID）  " + r.deviceName(),
              10,
              MUTED,
              false));
      if (project().inventory && !r.category.isEmpty()) {
        gap(details, 4);
        details.addView(text("分类 " + r.category, 10, MUTED, false));
      }
      if (project().inventory && !r.assetNumber.isEmpty() && !r.assetNumber.equals(r.sn)) {
        gap(details, 4);
        details.addView(text("资产编号 " + r.assetNumber, 10, MUTED, false));
      }
      gap(details, 5);
      details.addView(
          text(
              project().inventory
                  ? (r.checkedAt.isEmpty() ? "尚未盘点" : "盘点于 " + r.checkedAt)
                  : "登记日期  " + r.date,
              10,
              MUTED,
              false));
      weight(card, details);
      ImageView edit = icon(R.drawable.ui_edit, MUTED, 16);
      LinearLayout.LayoutParams editParams = new LinearLayout.LayoutParams(dp(16), dp(16));
      editParams.leftMargin = dp(8);
      card.addView(edit, editParams);
      check.setOnCheckedChangeListener(
          (button, checked) -> {
            if (checked) selected.add(r.id);
            else selected.remove(r.id);
            card.setBackground(outlined(Color.WHITE, checked ? 0xFFBDCEFD : LINE, 9));
            updateSelection();
          });
      add(recordList, recordSwipe(card, r));
    }
    if (records.isEmpty()) add(recordList, text("没有符合条件的记录", 13, MUTED, false));
    else add(recordList, text("显示 " + records.size() + " 条记录", 10, MUTED, false));
    if (records.size() == limit)
      add(
          recordList,
          button(
              "显示更多记录",
              false,
              () -> {
                limit += 100;
                renderRecordRows();
              }));
    updateSelection();
  }

  private SwipeDeleteLayout recordSwipe(View card, Record r) {
    card.setContentDescription("编辑记录 " + r.deviceName());
    card.setOnClickListener(v -> editRecord(r.id));
    Button remove = button("删除", true, () -> confirmDeleteRecord(r.id));
    remove.setBackground(shape(0xFFE5484D, 9));
    remove.setContentDescription("删除记录 " + r.deviceName());
    SwipeDeleteLayout swipe =
        new SwipeDeleteLayout(
            this,
            card,
            remove,
            opened -> {
              if (openRecordRow != null && openRecordRow != opened) openRecordRow.close();
              openRecordRow = opened;
            });
    swipe.setTag("record-" + r.id);
    return swipe;
  }

  private boolean recordChangeAllowed() {
    if (!printer.isPrintingProject(projectId)) return true;
    toast("该项目正在打印，请先完成或取消打印任务");
    return false;
  }

  private void editRecord(String id) {
    if (!recordChangeAllowed()) return;
    Record r = store.record(projectId, id);
    if (r == null) {
      toast("记录不存在或已删除");
      return;
    }
    editingRecordId = id;
    recordReturnTab = tab == 0 ? 0 : 2;
    recordSn = r.sn;
    recordNumber = String.valueOf(r.number);
    recordDate = r.date;
    navigate(7);
  }

  private EditText recordInput(
      String value, String hint, boolean numeric, java.util.function.Consumer<String> changed) {
    EditText edit = input(value, hint, numeric);
    if (numeric) edit.setInputType(InputType.TYPE_CLASS_NUMBER);
    edit.setContentDescription(hint);
    edit.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int a, int b, int c) {
            changed.accept(s.toString());
            if (recordEditError != null) recordEditError.setText("");
            if (numeric && recordNamePreview != null) {
              try {
                recordNamePreview.setText(
                    "完整编号：" + project().deviceName(namingInteger(recordNumber, "设备编号")));
              } catch (IllegalArgumentException error) {
                recordNamePreview.setText("请输入有效的数字编号");
              }
            }
          }

          public void afterTextChanged(Editable e) {}
        });
    return edit;
  }

  private void recordEditPage() {
    Record r = store.record(projectId, editingRecordId);
    if (r == null) {
      header("记录已删除", "返回查看最新内容");
      add(
          footer,
          button(recordReturnTab == 0 ? "返回扫码" : "返回记录", true, () -> navigate(recordReturnTab)));
      return;
    }
    header("编辑记录", project().name + " · " + r.deviceName());
    add(body, text("设备编号（数字部分）", 12, INK, true));
    add(body, recordInput(recordNumber, "设备编号（数字部分）", true, v -> recordNumber = v));
    recordNamePreview = text("完整编号：" + r.deviceName(), 11, BLUE, false);
    try {
      recordNamePreview.setText(
          "完整编号：" + project().deviceName(namingInteger(recordNumber, "设备编号")));
    } catch (IllegalArgumentException ignored) {
      recordNamePreview.setText("请输入有效的数字编号");
    }
    add(body, recordNamePreview);
    add(body, text("SN", 12, INK, true));
    add(body, recordInput(recordSn, "设备 SN", false, v -> recordSn = v));
    add(body, text("登记日期", 12, INK, true));
    LinearLayout dateRow = row();
    EditText date = recordInput(recordDate, "YYYY-MM-DD", false, v -> recordDate = v);
    date.setInputType(InputType.TYPE_CLASS_DATETIME | InputType.TYPE_DATETIME_VARIATION_DATE);
    weight(dateRow, date);
    Button pick =
        chip(
            "选择日期",
            false,
            () -> {
              LocalDate initial;
              try {
                initial = LocalDate.parse(recordDate);
              } catch (RuntimeException ignored) {
                initial = LocalDate.now();
              }
              new DatePickerDialog(
                      this,
                      (view, year, month, day) ->
                          date.setText(LocalDate.of(year, month + 1, day).toString()),
                      initial.getYear(),
                      initial.getMonthValue() - 1,
                      initial.getDayOfMonth())
                  .show();
            });
    LinearLayout.LayoutParams pickParams = new LinearLayout.LayoutParams(-2, dp(42));
    pickParams.leftMargin = dp(8);
    dateRow.addView(pick, pickParams);
    add(body, dateRow);
    add(body, text("创建于 " + r.createdAt + " · 已打印 " + r.printed + " 次", 10, MUTED, false));
    add(body, text("编号前缀和位数沿用当前项目规则，修改后同步到标签与导出。", 10.5f, MUTED, false));
    recordEditError = text("", 11, 0xFFB42318, false);
    add(body, recordEditError);
    Button remove = button("删除", false, () -> confirmDeleteRecord(r.id));
    remove.setContentDescription("删除这条记录");
    remove.setTextColor(0xFFE5484D);
    LinearLayout actions = row();
    actions.addView(remove);
    Button cancel = button("取消", false, () -> navigate(recordReturnTab));
    LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-2, -2);
    cancelParams.leftMargin = dp(8);
    actions.addView(cancel, cancelParams);
    weight(
        actions,
        button(
            "保存修改",
            true,
            () -> {
              if (!recordChangeAllowed()) return;
              try {
                Record before = store.record(projectId, editingRecordId);
                store.updateRecord(
                    projectId,
                    editingRecordId,
                    namingInteger(recordNumber, "设备编号"),
                    recordSn,
                    recordDate.trim());
                preparedPrint = null;
                printPreviewRecord = null;
                if (before != null && pending.equals(before.sn)) {
                  pending = RegistryStore.normalizeSn(recordSn);
                  pendingError = "";
                }
                navigate(recordReturnTab);
                toast("修改已保存");
              } catch (RuntimeException error) {
                recordEditError.setText(error.getMessage());
              }
            }));
    add(footer, actions);
  }

  private void confirmDeleteRecord(String id) {
    if (openRecordRow != null) openRecordRow.close();
    if (!recordChangeAllowed()) return;
    Record r = store.record(projectId, id);
    if (r == null) return;
    final long pid = projectId;
    final int returnTab = tab == 7 ? recordReturnTab : tab == 0 ? 0 : 2;
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("删除记录？")
            .setMessage("设备编号：" + r.deviceName() + "\nSN：" + r.sn + "\n\n删除后无法恢复，其他记录编号保持不变。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除记录", null)
            .create();
    dialog.setOnShowListener(
        v -> {
          dialog.getButton(-1).setTextColor(0xFFE5484D);
          dialog
              .getButton(-1)
              .setOnClickListener(
                  button -> {
                    if (printer.isPrintingProject(pid)) {
                      toast("该项目正在打印，请先完成或取消打印任务");
                      return;
                    }
                    try {
                      store.deleteRecord(pid, id);
                      selected.remove(id);
                      printIds.remove(id);
                      preparedPrint = null;
                      printPreviewRecord = null;
                      if (pending.equals(r.sn)) pending = pendingError = "";
                      dialog.dismiss();
                      navigate(returnTab);
                      toast("记录已删除");
                    } catch (RuntimeException error) {
                      toast(error.getMessage());
                    }
                  });
        });
    dialog.show();
  }

  private void updateSelection() {
    selectionText.setText("已选 " + selected.size() + " 条");
    if (recordPrintButton != null)
      recordPrintButton.setText(selected.isEmpty() ? "标签打印" : "打印 " + selected.size() + " 条");
  }

  private Record sampleRecord() {
    List<Record> current = store.records(projectId, "", "all", 1);
    if (!current.isEmpty()) return current.get(0);
    Record r = new Record();
    r.id = "preview";
    r.projectId = projectId;
    Project p = project();
    r.number = p.nextNumber;
    r.namePrefix = p.namePrefix;
    r.numberDigits = p.numberDigits;
    r.sn = "002611120001";
    r.date = LocalDate.now().toString();
    r.createdAt = r.date + " 00:00:00";
    r.projectName = project().name;
    return r;
  }

  private void labelPage() {
    header("标签编辑", "30 × 20 mm 不干胶 · " + project().name);
    LinearLayout dimensions = row();
    for (String[] dimension : new String[][] {{"纸宽", "30 mm"}, {"纸高", "20 mm"}}) {
      LinearLayout cell = col();
      cell.addView(text(dimension[0], 10, MUTED, false));
      gap(cell, 5);
      TextView value = text(dimension[1], 13, INK, false);
      value.setPadding(dp(12), dp(10), dp(12), dp(10));
      value.setBackground(shape(Color.WHITE, 6));
      cell.addView(value, new LinearLayout.LayoutParams(-1, -2));
      weight(dimensions, cell);
    }
    add(body, dimensions);
    LinearLayout preview = panel(0xFFE9EEF6);
    preview.setGravity(Gravity.CENTER_HORIZONTAL);
    LinearLayout previewHeader = row();
    weight(previewHeader, text("标签预览", 10.5f, MUTED, false));
    previewHeader.addView(text(project().count == 0 ? "示例数据" : "30 × 20 mm", 10, MUTED, false));
    add(preview, previewHeader);
    labelView = new LabelView(this, template, sampleRecord());
    labelView.selected = editingLine;
    labelView.onMove =
        i -> {
          editingLine = i;
          templateDirty = true;
          render();
        };
    preview.addView(labelView, new LinearLayout.LayoutParams(-1, -2));
    add(body, preview);
    LinearLayout toolsHeader = row();
    weight(toolsHeader, text("内容拼接", 13, INK, true));
    toolsHeader.addView(
        chip(
            "＋ 添加行",
            false,
            () -> {
              if (template.lines.size() >= 12) toast("最多 12 行");
              else editLine(-1);
            }));
    add(body, toolsHeader);
    for (int i = 0; i < template.lines.size(); i++) {
      final int index = i;
      LabelTemplate.Line labelLine = template.lines.get(i);
      LinearLayout lineRow = row();
      lineRow.setPadding(dp(10), dp(10), dp(10), dp(10));
      lineRow.setBackground(outlined(Color.WHITE, i == editingLine ? 0xFFBDCEFD : LINE, 7));
      lineRow.addView(text("第 " + (i + 1) + " 行", 10, MUTED, false));
      TextView expression = text(labelLine.expression, 12, BLUE, false);
      expression.setMaxLines(2);
      weight(lineRow, expression);
      lineRow.addView(
          text((int) labelLine.pt + " pt · " + (labelLine.bold ? "加粗" : "常规"), 10, MUTED, false));
      lineRow.setOnClickListener(
          v -> {
            editingLine = index;
            render();
          });
      add(body, lineRow);
    }
    LabelTemplate.Line selectedLine = template.lines.get(editingLine);
    LinearLayout properties = panel(Color.WHITE);
    LinearLayout propertyHeading = row();
    weight(propertyHeading, text("第 " + (editingLine + 1) + " 行 · 样式", 13, INK, true));
    propertyHeading.addView(chip("编辑内容", false, () -> editLine(editingLine)));
    add(properties, propertyHeading);
    LinearLayout sizes = row();
    weight(sizes, button((int) selectedLine.pt + " pt  字号", false, () -> editLine(editingLine)));
    weight(
        sizes, button(format(selectedLine.width) + " mm  宽度", false, () -> editLine(editingLine)));
    add(properties, sizes);
    LinearLayout style = row();
    weight(
        style,
        chip(
            "加粗",
            selectedLine.bold,
            () -> {
              selectedLine.bold = !selectedLine.bold;
              templateDirty = true;
              render();
            }));
    String[] aligns = {"左对齐", "居中", "右对齐"};
    for (int i = 0; i < 3; i++) {
      final int align = i;
      weight(
          style,
          chip(
              aligns[i],
              selectedLine.align == i,
              () -> {
                selectedLine.align = align;
                templateDirty = true;
                render();
              }));
    }
    add(properties, style);
    TextView coords =
        text(
            "X  " + format(selectedLine.x) + " mm       Y  " + format(selectedLine.y) + " mm",
            11,
            MUTED,
            false);
    coords.setOnClickListener(v -> editLine(editingLine));
    properties.addView(coords);
    gap(properties, 9);
    properties.addView(text("每行可拼接字段和文字，拖动预览调整位置", 10, MUTED, false));
    add(body, properties);
    add(
        body,
        chip(
            "恢复默认两行",
            false,
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("恢复默认模板？")
                    .setMessage("第一行设备名称加粗放大，第二行 SN。")
                    .setPositiveButton(
                        "恢复",
                        (d, w) -> {
                          template = LabelTemplate.defaults();
                          templateDirty = true;
                          editingLine = 0;
                          render();
                        })
                    .setNegativeButton("取消", null)
                    .show()));
    LinearLayout actions = row();
    weight(
        actions,
        button(
            templateDirty ? "保存模板 *" : "保存模板",
            false,
            () -> {
              if (saveTemplate(true)) render();
            }));
    Button print =
        button(
            "标签打印",
            true,
            () -> {
              if (saveTemplate(false)) showPrintSetup();
            });
    buttonIcon(print, R.drawable.ui_printer);
    weight(actions, print);
    add(footer, actions);
  }

  private void editLine(int index) {
    LabelTemplate.Line source =
        index < 0
            ? new LabelTemplate.Line("{SN}", 7, false, 2, 14, 26, 1)
            : template.lines.get(index);
    LinearLayout form = col();
    form.setPadding(dp(18), dp(12), dp(18), dp(12));
    add(form, text("文字可直接输入；点字段插入到光标处", 12, MUTED, false));
    EditText content = input(source.expression, "拼接内容", false);
    content.setTextSize(14);
    add(form, content);
    LinearLayout fields = row();
    for (String field : new String[] {"设备名称", "SN", "日期", "项目"}) {
      Button insert =
          button(
              field,
              false,
              () -> {
                int at = Math.max(0, content.getSelectionStart());
                content.getText().insert(at, "{" + field + "}");
              });
      insert.setTextSize(11);
      weight(fields, insert);
    }
    add(form, fields);
    LinearLayout sizes = row();
    EditText pt = input(format(source.pt), "字号 pt", true),
        width = input(format(source.width), "宽度 mm", true);
    weight(sizes, pt);
    weight(sizes, width);
    add(form, text("字号（pt） / 文本框宽度（mm）", 12, MUTED, false));
    add(form, sizes);
    LinearLayout coords = row();
    EditText x = input(format(source.x), "X mm", true), y = input(format(source.y), "Y mm", true);
    weight(coords, x);
    weight(coords, y);
    add(form, text("左边距 X / 上边距 Y（mm）", 12, MUTED, false));
    add(form, coords);
    CheckBox bold = new CheckBox(this);
    bold.setText("加粗");
    bold.setChecked(source.bold);
    add(form, bold);
    Spinner align = new Spinner(this);
    align.setAdapter(
        new ArrayAdapter<>(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            new String[] {"左对齐", "居中", "右对齐"}));
    align.setSelection(source.align);
    add(form, align);
    ScrollView sc = new ScrollView(this);
    sc.addView(form);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(index < 0 ? "添加标签行" : "编辑第 " + (index + 1) + " 行")
            .setView(sc)
            .setPositiveButton("应用", null)
            .setNegativeButton("取消", null)
            .setNeutralButton(index < 0 ? "" : "删除本行", null)
            .create();
    dialog.setOnShowListener(
        v -> {
          dialog
              .getButton(-1)
              .setOnClickListener(
                  b -> {
                    try {
                      LabelTemplate.Line next =
                          new LabelTemplate.Line(
                              content.getText().toString(),
                              Float.parseFloat(pt.getText().toString()),
                              bold.isChecked(),
                              Float.parseFloat(x.getText().toString()),
                              Float.parseFloat(y.getText().toString()),
                              Float.parseFloat(width.getText().toString()),
                              align.getSelectedItemPosition());
                      LabelTemplate copy = LabelTemplate.fromJson(template.toJson());
                      if (index < 0) copy.lines.add(next);
                      else copy.lines.set(index, next);
                      copy.validate();
                      template = copy;
                      editingLine = index < 0 ? template.lines.size() - 1 : index;
                      templateDirty = true;
                      dialog.dismiss();
                      render();
                    } catch (RuntimeException e) {
                      content.setError(
                          e instanceof NumberFormatException ? "请输入正确的字号和毫米数值" : e.getMessage());
                    }
                  });
          if (index >= 0)
            dialog
                .getButton(-3)
                .setOnClickListener(
                    b -> {
                      if (template.lines.size() == 1) {
                        toast("至少保留一行");
                        return;
                      }
                      template.lines.remove(index);
                      editingLine = 0;
                      templateDirty = true;
                      dialog.dismiss();
                      render();
                    });
          else dialog.getButton(-3).setVisibility(View.GONE);
        });
    dialog.show();
  }

  private String format(float value) {
    return String.format(Locale.ROOT, "%.1f", value);
  }

  private void settingsPage() {
    header("设备与设置", "Hikrobot MV-IDP5204 · DP30S");
    LinearLayout device = panel(Color.WHITE);
    LinearLayout title = row();
    title.addView(icon(R.drawable.ui_printer, BLUE, 30));
    LinearLayout printerName = col();
    printerName.addView(text("DP30S", 17, INK, true));
    gap(printerName, 4);
    printerName.addView(text("蓝牙标签打印机", 11, MUTED, false));
    weight(title, printerName);
    title.addView(
        text(printer.connected() ? "已连接" : "未连接", 10, printer.connected() ? GREEN : MUTED, false));
    device.addView(title);
    gap(device, 8);
    settingRow(device, "标签规格", "30 × 20 mm", null);
    if (!printer.connected() && !printer.status.equals("未连接"))
      device.addView(text(printer.status, 10, MUTED, false));
    add(body, device);
    if (printer.active() || printer.total() > 0)
      add(footer, button("查看打印进度", false, this::showPrintProgress));
    add(footer, button("搜索并连接打印机", true, this::connectPrinter));
    LinearLayout scan = col();
    scan.setBackground(shape(Color.WHITE, 10));
    settingRow(
        scan,
        "扫码结果接收",
        prefs.getInt("scanMode", 0) == 0 ? "直接接收（推荐）" : "填入输入框",
        "将结果交给本软件",
        this::showScanReception);
    line(scan);
    Project p = project();
    settingRow(
        scan, "项目工作模式", p.inventory ? "扫码盘点" : "扫码登记", "仅当前项目：" + p.name, this::showProjectMode);
    line(scan);
    settingRow(scan, "设备编号规则", "下个 " + p.deviceName(p.nextNumber), this::showNaming);
    line(scan);
    settingRow(scan, "重复 SN 规则", "项目内禁止重复", null);
    line(scan);
    settingRow(scan, "登记日期格式", "yyyy-MM-dd", null);
    line(scan);
    Switch feedback = new Switch(this);
    feedback.setText("成功提示 · 声音与震动");
    feedback.setTextSize(12 * designScale());
    feedback.setTextColor(INK);
    feedback.setPadding(dp(14), dp(10), dp(14), dp(10));
    feedback.setChecked(prefs.getBoolean("feedback", true));
    feedback.setOnCheckedChangeListener(
        (b, value) -> prefs.edit().putBoolean("feedback", value).apply());
    scan.addView(feedback);
    line(scan);
    LinearLayout warning = row();
    Switch warningToggle = new Switch(this);
    warningToggle.setText("异常提示音");
    warningToggle.setTextSize(12 * designScale());
    warningToggle.setTextColor(INK);
    warningToggle.setPadding(dp(14), dp(10), dp(8), dp(10));
    warningToggle.setContentDescription("异常提示音开关");
    warningToggle.setChecked(prefs.getBoolean("errorFeedback", true));
    warningToggle.setOnCheckedChangeListener(
        (b, value) -> {
          prefs.edit().putBoolean("errorFeedback", value).apply();
          if (!value) warningSound.stop();
        });
    weight(warning, warningToggle);
    Button previewWarning = chip("试听", false, warningSound::play);
    previewWarning.setContentDescription("试听异常提示音");
    warning.addView(previewWarning);
    scan.addView(warning);
    TextView warningHint = text("重复登记、重复盘点或非本项目条码", 10, MUTED, false);
    warningHint.setPadding(dp(14), 0, dp(14), dp(10));
    scan.addView(warningHint);
    line(scan);
    settingRow(scan, "数据保存", "本机离线保存", null);
    add(body, scan);
    LinearLayout advanced = col();
    advanced.setBackground(shape(Color.WHITE, 10));
    settingRow(advanced, "扫码设备设置", "启用直接接收", this::configureScanner);
    line(advanced);
    settingRow(advanced, "高级接收设置", "通常无需修改", this::scannerParameters);
    line(advanced);
    settingRow(advanced, "桌面入口", "添加桌面图标", () -> requestHomeShortcut(true));
    add(body, advanced);
    add(body, text("PDA扫码登记  v" + BuildConfig.VERSION_NAME, 10, MUTED, false));
  }

  private void showNaming() {
    Project p = project();
    namingPrefix = p.namePrefix;
    namingDigits = String.valueOf(p.numberDigits);
    namingStart = String.valueOf(p.startNumber);
    navigate(6);
  }

  private EditText namingInput(
      String value, String hint, boolean numeric, java.util.function.Consumer<String> changed) {
    EditText edit = input(value, hint, numeric);
    if (numeric) edit.setInputType(InputType.TYPE_CLASS_NUMBER);
    edit.setSelectAllOnFocus(numeric);
    edit.setContentDescription(hint);
    edit.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            changed.accept(s.toString());
            updateNamingPreview();
          }

          public void afterTextChanged(Editable e) {}
        });
    return edit;
  }

  private int namingInteger(String value, String label) {
    if (!value.matches("[0-9]+")) throw new IllegalArgumentException(label + "须为整数");
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException error) {
      throw new IllegalArgumentException(label + "过大");
    }
  }

  private void namingPage() {
    Project p = project();
    LinearLayout heading = row();
    LinearLayout caption = col();
    caption.addView(text("设备编号规则", 20, INK, true));
    gap(caption, 5);
    caption.addView(text("当前项目 · " + p.name, 10.5f, MUTED, false));
    weight(heading, caption);
    heading.addView(chip("返回", false, () -> navigate(4)));
    add(body, heading);

    LinearLayout fields = panel(Color.WHITE);
    add(fields, text("名称前缀", 11, MUTED, false));
    add(fields, namingInput(namingPrefix, "可留空，例如 SB-", false, value -> namingPrefix = value));
    LinearLayout numbers = row();
    LinearLayout digits = col();
    add(digits, text("编号位数", 11, MUTED, false));
    digits.addView(namingInput(namingDigits, "编号位数（1–10）", true, value -> namingDigits = value));
    weight(numbers, digits);
    LinearLayout start = col();
    add(start, text("起始编号", 11, MUTED, false));
    start.addView(namingInput(namingStart, "起始编号（可为 0）", true, value -> namingStart = value));
    weight(numbers, start);
    add(fields, numbers);
    fields.addView(text("位数不足时补 0，超过设定位数后继续递增。", 10, MUTED, false));
    add(body, fields);

    LinearLayout preview = panel(SOFT);
    add(preview, text("名称预览", 11, MUTED, false));
    namingPreview = text("", 21, BLUE, true);
    add(preview, namingPreview);
    namingNext = text("", 11, INK, false);
    preview.addView(namingNext);
    add(body, preview);
    add(body, text("仅应用到当前项目，其他项目各自设置。", 11, MUTED, false));
    add(body, text("保存后，已有 " + p.count + " 条记录按原设备顺序重新命名；SN、登记日期和打印次数保留。", 11, MUTED, false));
    namingError = text("", 11, 0xFFB45309, false);
    add(footer, namingError);
    namingSave =
        button(
            "保存并应用",
            true,
            () -> {
              if (printer.isPrintingProject(projectId)) {
                toast("当前项目正在打印，请先完成或取消打印任务");
                return;
              }
              try {
                store.saveNaming(
                    projectId,
                    namingPrefix,
                    namingInteger(namingDigits, "编号位数"),
                    namingInteger(namingStart, "起始编号"));
                preparedPrint = null;
                printPreviewIndex = 0;
                navigate(4);
                toast("设备编号规则已保存，已有记录已更新");
              } catch (RuntimeException error) {
                namingError.setText(error.getMessage());
                namingError.setVisibility(View.VISIBLE);
              }
            });
    add(footer, namingSave);
    updateNamingPreview();
    body.setFocusableInTouchMode(true);
    body.requestFocus();
  }

  private void updateNamingPreview() {
    if (tab != 6 || namingSave == null) return;
    try {
      int digits = namingInteger(namingDigits, "编号位数"), start = namingInteger(namingStart, "起始编号");
      String prefix = RegistryStore.validateNaming(namingPrefix, digits, start);
      int count = project().count;
      if ((long) start + count >= Integer.MAX_VALUE)
        throw new IllegalArgumentException("起始编号过大，无法容纳现有记录及下次登记");
      StringBuilder examples = new StringBuilder();
      for (int i = 0; i < 3 && (long) start + i < Integer.MAX_VALUE; i++) {
        if (i > 0) examples.append("、");
        examples.append(Models.deviceName(prefix, digits, start + i));
      }
      namingPreview.setText(examples.toString());
      namingNext.setText(
          "已有 " + count + " 条记录 · 下次扫码：" + Models.deviceName(prefix, digits, start + count));
      namingError.setVisibility(View.GONE);
      namingSave.setEnabled(true);
      namingSave.setAlpha(1f);
    } catch (IllegalArgumentException error) {
      namingPreview.setText("请完善命名规则");
      namingNext.setText("填写后可预览生成的设备名称");
      namingError.setText(error.getMessage());
      namingError.setVisibility(View.VISIBLE);
      namingSave.setEnabled(false);
      namingSave.setAlpha(0.45f);
    }
  }

  private void showScanReception() {
    LinearLayout form = col();
    form.setPadding(dp(18), dp(4), dp(18), dp(4));
    add(form, text("选择 PDA 如何把扫码结果交给本软件。", 12, MUTED, false));
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("扫码结果接收")
            .setView(form)
            .setNegativeButton("取消", null)
            .create();
    String[] titles = {"直接接收（推荐）", "填入输入框（备用）"};
    String[] descriptions = {"按侧键扫码，结果直接接收并自动登记。", "像键盘打字一样输入 SN，回车后自动登记。"};
    for (int i = 0; i < titles.length; i++) {
      final int mode = i;
      boolean checked = prefs.getInt("scanMode", 0) == i;
      LinearLayout option = col();
      option.setPadding(dp(10), dp(8), dp(12), dp(10));
      option.setBackground(outlined(checked ? SOFT : Color.WHITE, checked ? 0xFFBDCEFD : LINE, 8));
      RadioButton radio = new RadioButton(this);
      radio.setText(titles[i]);
      radio.setTextSize(13 * designScale());
      radio.setTextColor(INK);
      radio.setButtonTintList(ColorStateList.valueOf(BLUE));
      radio.setChecked(checked);
      radio.setClickable(false);
      radio.setFocusable(false);
      radio.setMinimumHeight(dp(32));
      radio.setIncludeFontPadding(false);
      option.addView(radio);
      TextView detail = text(descriptions[i], 11, MUTED, false);
      detail.setPadding(dp(32), dp(2), 0, 0);
      option.addView(detail, new LinearLayout.LayoutParams(-1, -2));
      option.setContentDescription(titles[i]);
      option.setOnClickListener(
          v -> {
            prefs.edit().putInt("scanMode", mode).apply();
            dialog.dismiss();
            startScanner();
            render();
          });
      add(form, option);
    }
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    dialog.setView(scroll);
    dialog.show();
  }

  private void configureScanner() {
    new AlertDialog.Builder(this)
        .setTitle("启用直接接收")
        .setMessage("设置 PDA 将扫码结果直接交给本软件。启用后，回到扫码页按侧键扫码即可自动登记。")
        .setPositiveButton(
            "启用",
            (d, w) -> {
              scanner.enableBroadcastOutput();
              toast("已发送设置，请回到扫码页按侧键试扫");
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void scannerParameters() {
    LinearLayout form = panel(Color.WHITE);
    EditText
        action =
            input(
                prefs.getString("scanAction", ScannerBridge.DEFAULT_ACTION), "接收标识（Action）", false),
        key = input(prefs.getString("scanKey", ScannerBridge.DEFAULT_KEY), "数据字段", false);
    add(form, text("接收标识（Action）", 12, MUTED, false));
    add(form, action);
    add(form, text("条码字段（SN）", 12, MUTED, false));
    add(form, key);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("高级接收设置")
            .setView(form)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(-1)
                .setOnClickListener(
                    b -> {
                      if (action.getText().toString().trim().isEmpty()
                          || key.getText().toString().trim().isEmpty()) {
                        action.setError("参数不能为空");
                        return;
                      }
                      prefs
                          .edit()
                          .putString("scanAction", action.getText().toString().trim())
                          .putString("scanKey", key.getText().toString().trim())
                          .apply();
                      startScanner();
                      d.dismiss();
                    }));
    d.show();
  }

  private void showExport() {
    if (exportBusy) {
      toast("正在导出，请稍候");
      return;
    }
    if (project().count == 0) {
      toast("当前项目没有记录");
      return;
    }
    int columnCount =
        project().inventory ? TableExporter.INVENTORY_HEADERS.length : TableExporter.HEADERS.length;
    if (exportColumns.length != columnCount) {
      exportColumns = new boolean[columnCount];
      Arrays.fill(exportColumns, true);
    }
    exportScope = selected.isEmpty() ? 1 : 0;
    exportProject = projectId;
    exportQuery = query;
    exportFilter = filter;
    exportIds = new ArrayList<>(selected);
    exportFilename =
        project().name.replaceAll("[\\\\/:*?\"<>|]", "_")
            + "_"
            + LocalDate.now().toString().replace("-", "")
            + (exportCsv ? ".csv" : ".xlsx");
    navigate(8);
  }

  private List<Record> exportRows() {
    return exportScope == 0
        ? store.recordsByIds(exportIds)
        : store.records(
            exportProject,
            exportScope == 1 ? exportQuery : "",
            exportScope == 1 ? exportFilter : "all",
            0);
  }

  private void exportPage() {
    int count = exportRows().size();
    header("导出数据", project().name + " · 本次 " + count + " 条记录");
    LinearLayout range = panel(Color.WHITE);
    add(range, text("导出范围", 13, INK, true));
    LinearLayout choices = row();
    String[] scopes = {"已选 " + exportIds.size() + " 条", "当前筛选", "整个项目"};
    for (int i = 0; i < scopes.length; i++) {
      if (i == 0 && exportIds.isEmpty()) continue;
      final int value = i;
      weight(
          choices,
          chip(
              scopes[i],
              exportScope == i,
              () -> {
                exportScope = value;
                render();
              }));
    }
    range.addView(choices);
    add(body, range);
    add(body, text("文件格式", 13, INK, true));
    LinearLayout formats = row();
    for (int i = 0; i < 2; i++) {
      final boolean csv = i == 1;
      boolean active = exportCsv == csv;
      LinearLayout option = panel(active ? SOFT : Color.WHITE);
      option.setBackground(outlined(active ? SOFT : Color.WHITE, active ? BLUE : LINE, 10));
      add(option, text(csv ? "通用表格" : "Excel", 15, active ? BLUE : INK, true));
      option.addView(text(csv ? "CSV" : "XLSX", 11, MUTED, false));
      option.setContentDescription(csv ? "导出为 CSV" : "导出为 Excel");
      option.setOnClickListener(
          v -> {
            hideKeyboard();
            exportCsv = csv;
            exportFilename =
                exportFilename.replaceFirst("(?i)\\.(xlsx|csv)$", "") + (csv ? ".csv" : ".xlsx");
            render();
          });
      weight(formats, option);
    }
    add(body, formats);
    LinearLayout fields = panel(Color.WHITE);
    LinearLayout fieldTitle = row();
    weight(fieldTitle, text("导出字段", 13, INK, true));
    TextView fieldCount = text("", 11, MUTED, false);
    fieldTitle.addView(fieldCount);
    add(fields, fieldTitle);
    Button export = button("导出 " + count + " 条记录", true, this::startExport);
    export.setContentDescription("导出登记表");
    Runnable refreshFields =
        () -> {
          int n = 0;
          for (boolean value : exportColumns) if (value) n++;
          fieldCount.setText("已选 " + n + " 项");
          export.setEnabled(n > 0 && count > 0 && !exportBusy);
          export.setAlpha(export.isEnabled() ? 1f : 0.45f);
        };
    String[] exportHeaders =
        exportColumns.length > 4 ? TableExporter.INVENTORY_HEADERS : TableExporter.HEADERS;
    for (int i = 0; i < exportHeaders.length; i++) {
      final int index = i;
      CheckBox column = new CheckBox(this);
      column.setText(exportHeaders[i]);
      column.setTextColor(INK);
      column.setTextSize(12 * designScale());
      column.setButtonTintList(ColorStateList.valueOf(BLUE));
      column.setMinHeight(dp(40));
      column.setChecked(exportColumns[i]);
      column.setOnCheckedChangeListener(
          (button, value) -> {
            exportColumns[index] = value;
            refreshFields.run();
          });
      fields.addView(column);
    }
    add(body, fields);
    add(body, text("文件名", 11, MUTED, false));
    add(body, recordInput(exportFilename, "导出文件名", false, value -> exportFilename = value));
    add(
        body,
        text(
            exportCsv ? "CSV 在 Excel 中请按文本导入，保留开头的 0。" : "SN 按文本导出，保留开头的 0。", 10.5f, MUTED, false));
    LinearLayout actions = row();
    actions.addView(button("取消", false, () -> navigate(2)));
    weight(actions, export);
    add(footer, actions);
    refreshFields.run();
  }

  private void startExport() {
    if (exportBusy) return;
    boolean any = false;
    for (boolean value : exportColumns) any |= value;
    if (!any || exportRows().isEmpty()) {
      toast("请选择导出字段和记录");
      return;
    }
    String filename = exportFilename.trim();
    if (filename.isEmpty() || filename.matches(".*[\\\\/:*?\"<>|].*")) {
      toast("请输入有效的文件名");
      return;
    }
    filename = filename.replaceFirst("(?i)\\.(xlsx|csv)$", "") + (exportCsv ? ".csv" : ".xlsx");
    Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    create.addCategory(Intent.CATEGORY_OPENABLE);
    create.setType(
        exportCsv
            ? "text/csv"
            : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    create.putExtra(Intent.EXTRA_TITLE, filename);
    hideKeyboard();
    try {
      startActivityForResult(create, EXPORT_REQUEST);
    } catch (android.content.ActivityNotFoundException e) {
      toast("本设备缺少系统文件选择器");
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == IMPORT_REQUEST) {
      if (result != RESULT_OK || data == null || data.getData() == null) return;
      importUri = data.getData().toString();
      importName = "";
      importRows = null;
      importSource = null;
      importMapping = null;
      importError = "";
      importMoreFields = false;
      importNameEdited = false;
      try {
        getContentResolver()
            .takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
      } catch (SecurityException ignored) {
      }
      tab = 11;
      readImport(true);
      return;
    }
    if (request == ENABLE_BT_REQUEST) {
      if (result == RESULT_OK) connectPrinter();
      return;
    }
    if (request != EXPORT_REQUEST || result != RESULT_OK || data == null || data.getData() == null)
      return;
    Uri uri = data.getData();
    exportBusy = true;
    if (tab == 8) render();
    toast("正在生成表格…");
    final long pid = exportProject;
    final int scope = exportScope;
    final String q = exportQuery, f = exportFilter;
    final List<String> ids = new ArrayList<>(exportIds);
    final boolean isCsv = exportCsv;
    final boolean[] columns = exportColumns.clone();
    io.execute(
        () -> {
          try (RegistryStore exportStore = new RegistryStore(getApplicationContext())) {
            List<Record> records =
                scope == 0
                    ? exportStore.recordsByIds(ids)
                    : exportStore.records(pid, scope == 1 ? q : "", scope == 1 ? f : "all", 0);
            records.sort(Comparator.comparingInt(r -> r.number));
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
              if (output == null) throw new java.io.IOException("无法打开保存位置");
              String[] headers =
                  columns.length > 4 ? TableExporter.INVENTORY_HEADERS : TableExporter.HEADERS;
              if (isCsv) TableExporter.csv(output, records, columns, headers);
              else TableExporter.xlsx(output, records, columns, headers);
            }
            runOnUiThread(
                () -> {
                  exportBusy = false;
                  if (tab == 8 && !isFinishing() && !isDestroyed()) navigate(2);
                  if (!isFinishing() && !isDestroyed())
                    new AlertDialog.Builder(this)
                        .setTitle("导出完成")
                        .setMessage("已导出 " + records.size() + " 条记录。")
                        .setPositiveButton("完成", null)
                        .setNeutralButton(
                            "分享文件",
                            (d, w) -> {
                              Intent share = new Intent(Intent.ACTION_SEND);
                              share.setType(
                                  isCsv
                                      ? "text/csv"
                                      : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                              share.putExtra(Intent.EXTRA_STREAM, uri);
                              share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                              startActivity(Intent.createChooser(share, "分享登记表"));
                            })
                        .show();
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  exportBusy = false;
                  if (tab == 8 && !isFinishing() && !isDestroyed()) render();
                  if (!isDestroyed()) toast("导出失败：" + e.getMessage());
                });
          }
        });
  }

  private boolean bluetoothAllowed() {
    String[] permissions =
        Build.VERSION.SDK_INT >= 31
            ? new String[] {
              Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT
            }
            : new String[] {Manifest.permission.ACCESS_FINE_LOCATION};
    List<String> missing = new ArrayList<>();
    for (String p : permissions)
      if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) missing.add(p);
    if (!missing.isEmpty()) {
      requestPermissions(missing.toArray(new String[0]), BLUETOOTH_REQUEST);
      return false;
    }
    return true;
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == BLUETOOTH_REQUEST) {
      for (int g : grants)
        if (g != PackageManager.PERMISSION_GRANTED) {
          toast("需要蓝牙搜索权限才能连接打印机");
          return;
        }
      if (grants.length > 0) connectPrinter();
    }
  }

  @SuppressLint("MissingPermission")
  private void connectPrinter() {
    if (!bluetoothAllowed()) return;
    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
    if (adapter == null) {
      toast("设备不支持蓝牙");
      return;
    }
    if (!adapter.isEnabled()) {
      startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), ENABLE_BT_REQUEST);
      return;
    }
    for (BluetoothDevice device : adapter.getBondedDevices()) {
      PrinterAddress a = LPAPI.Factory.getPrinterAddress(device);
      if (a != null) printer.discovered.put(a.key(), a);
    }
    LinearLayout form = panel(Color.WHITE);
    discoveryAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
    ListView list = new ListView(this);
    list.setAdapter(discoveryAdapter);
    form.addView(list, new LinearLayout.LayoutParams(-1, dp(240)));
    add(
        form,
        button(
            "搜索附近打印机",
            false,
            () -> {
              try {
                printer.discover();
              } catch (RuntimeException e) {
                toast(e.getMessage());
              }
            }));
    discoveryDialog =
        new AlertDialog.Builder(this)
            .setTitle("连接 DP30S")
            .setView(form)
            .setNegativeButton("取消", null)
            .setNeutralButton("输入蓝牙地址", (d, w) -> printerAddress())
            .create();
    list.setOnItemClickListener(
        (p, v, position, id) -> {
          try {
            printer.connect(discoveryAddresses.get(position));
            discoveryDialog.dismiss();
          } catch (RuntimeException e) {
            toast(e.getMessage());
          }
        });
    discoveryDialog.setOnDismissListener(
        d -> {
          printer.stopDiscovery();
          discoveryDialog = null;
          discoveryAdapter = null;
        });
    discoveryDialog.show();
    updateDiscovery();
  }

  private void printerAddress() {
    EditText address = input("", "蓝牙 MAC 地址，例如 AA:BB:CC:DD:EE:FF", false);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle("打印机蓝牙地址")
            .setView(address)
            .setPositiveButton("连接", null)
            .setNegativeButton("取消", null)
            .create();
    d.setOnShowListener(
        v ->
            d.getButton(-1)
                .setOnClickListener(
                    b -> {
                      try {
                        String a = address.getText().toString().trim();
                        if (!BluetoothAdapter.checkBluetoothAddress(a.toUpperCase(Locale.ROOT)))
                          throw new IllegalArgumentException("蓝牙地址格式不正确");
                        printer.connect(a.toUpperCase(Locale.ROOT));
                        d.dismiss();
                      } catch (RuntimeException e) {
                        address.setError(e.getMessage());
                      }
                    }));
    d.show();
  }

  private void updateDiscovery() {
    if (discoveryAdapter == null) return;
    discoveryAddresses.clear();
    discoveryAddresses.addAll(printer.discovered.values());
    discoveryAdapter.clear();
    for (PrinterAddress a : discoveryAddresses)
      discoveryAdapter.add(a.shownName + "\n" + a.macAddress);
    discoveryAdapter.notifyDataSetChanged();
  }

  private void printerChanged() {
    if (isFinishing() || isDestroyed()) return;
    updateDiscovery();
    if (tab == 5) updatePrintPlan();
    if (printProgressText != null) {
      printProgressText.setText(printStatus());
      if (printPauseButton != null)
        printPauseButton.setText(printer.paused ? "继续未完成标签" : "暂停（当前张完成后）");
    } else if (tab == 4) render();
  }

  private void showPrintSetup() {
    if (!saveTemplate(false)) return;
    if (printer.active()) {
      showPrintProgress();
      return;
    }
    printReturnTab = tab == 2 ? 2 : 3;
    printIds = tab == 2 ? new ArrayList<>(selected) : new ArrayList<>();
    printSource = printIds.isEmpty() ? 0 : 1;
    printStart = String.valueOf(sampleRecord().number);
    printQuantity = printIds.isEmpty() ? "1" : String.valueOf(printIds.size());
    printCopies = "1";
    printPreviewIndex = 0;
    navigate(5);
  }

  private List<Record> printCandidates() {
    List<Record> rows =
        printSource == 1
            ? store.recordsByIds(printIds)
            : store.records(projectId, "", printSource == 2 ? "unprinted" : "all", 0);
    rows.sort(Comparator.comparingInt(r -> r.number));
    return rows;
  }

  private int printInteger(String value, String name) {
    if (!value.matches("[0-9]+")) throw new IllegalArgumentException(name + "须为大于 0 的整数");
    try {
      int number = Integer.parseInt(value);
      if (number > 0) return number;
    } catch (NumberFormatException ignored) {
    }
    throw new IllegalArgumentException(name + "须为大于 0 的整数，且不能过大");
  }

  private EditText printInput(
      String value, String hint, java.util.function.Consumer<String> changed) {
    EditText edit = input(value, hint, true);
    edit.setInputType(InputType.TYPE_CLASS_NUMBER);
    edit.setSelectAllOnFocus(true);
    edit.setContentDescription(hint);
    edit.addTextChangedListener(
        new TextWatcher() {
          public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

          public void onTextChanged(CharSequence s, int start, int before, int count) {
            changed.accept(s.toString());
            printPreviewIndex = 0;
            updatePrintPlan();
          }

          public void afterTextChanged(Editable e) {}
        });
    return edit;
  }

  private void printPage() {
    LinearLayout heading = row();
    LinearLayout caption = col();
    caption.addView(text("标签打印", 20, INK, true));
    gap(caption, 5);
    caption.addView(text(project().name + " · 30 × 20 mm", 10.5f, MUTED, false));
    weight(heading, caption);
    heading.addView(chip("返回", false, () -> navigate(printReturnTab)));
    add(body, heading);

    LinearLayout connection = row();
    connection.setPadding(dp(14), dp(10), dp(14), dp(10));
    connection.setBackground(shape(Color.WHITE, 10));
    connection.addView(icon(R.drawable.ui_printer, BLUE, 20));
    LinearLayout connectionInfo = col();
    connectionInfo.addView(text("DP30S", 13, INK, true));
    gap(connectionInfo, 4);
    printConnection = text("", 10, MUTED, false);
    connectionInfo.addView(printConnection);
    weight(connection, connectionInfo);
    add(body, connection);

    LinearLayout settings = panel(Color.WHITE);
    add(settings, text("选择设备", 12, INK, true));
    LinearLayout sources = row();
    String[] names = {"按设备编号", "已勾选记录", "未打印设备"};
    for (int i = 0; i < names.length; i++) {
      final int source = i;
      Button option =
          chip(
              names[i],
              printSource == i,
              () -> {
                printSource = source;
                printPreviewIndex = 0;
                List<Record> rows = printCandidates();
                printStart =
                    rows.isEmpty()
                        ? String.valueOf(project().nextNumber)
                        : String.valueOf(rows.get(0).number);
                printQuantity = source == 1 && !rows.isEmpty() ? String.valueOf(rows.size()) : "1";
                render();
              });
      option.setEnabled(i != 1 || !printIds.isEmpty());
      if (!option.isEnabled()) option.setAlpha(0.45f);
      weight(sources, option);
    }
    add(settings, sources);
    LinearLayout numbers = row();
    if (printSource != 1) {
      LinearLayout start = col();
      add(start, text("起始设备号", 10.5f, MUTED, false));
      start.addView(printInput(printStart, "起始设备号", value -> printStart = value));
      weight(numbers, start);
    }
    LinearLayout quantity = col();
    add(quantity, text("标签张数", 10.5f, MUTED, false));
    quantity.addView(printInput(printQuantity, "标签张数", value -> printQuantity = value));
    weight(numbers, quantity);
    LinearLayout copies = col();
    add(copies, text("每张份数", 10.5f, MUTED, false));
    copies.addView(printInput(printCopies, "每张份数", value -> printCopies = value));
    weight(numbers, copies);
    add(settings, numbers);
    printRange = text("", 10.5f, MUTED, false);
    settings.addView(printRange);
    add(body, settings);

    LinearLayout preview = panel(0xFFE9EEF6);
    add(preview, text("本次标签预览", 11, MUTED, false));
    LinearLayout previewRow = row();
    printPreview = new LabelView(this, template, sampleRecord());
    previewRow.addView(printPreview, new LinearLayout.LayoutParams(dp(140), dp(94)));
    LinearLayout info = col();
    printPreviewCaption = text("", 11, INK, true);
    info.addView(printPreviewCaption);
    gap(info, 7);
    info.addView(text("样稿只出当前预览 1 张", 10, MUTED, false));
    gap(info, 8);
    info.addView(
        chip(
            "切换预览 ›",
            false,
            () -> {
              if (preparedPrint != null) {
                printPreviewIndex = (printPreviewIndex + 1) % preparedPrint.size();
                updatePrintPlan();
              }
            }));
    weight(previewRow, info);
    preview.addView(previewRow);
    add(body, preview);
    add(
        body,
        text(
            project().namePrefix.isEmpty()
                ? "张数 = 不同设备数量；份数 = 每张重复数量。"
                : "起始号只填数字部分；张数 × 份数 = 总张数。",
            10,
            MUTED,
            false));

    printSummary = text("", 12, BLUE, true);
    LinearLayout totalRow = row();
    weight(totalRow, printSummary);
    totalRow.addView(chip("连接打印机", false, this::connectPrinter));
    add(footer, totalRow);
    LinearLayout actions = row();
    printSampleButton = button("测试样稿 1 张", false, () -> startPrint(true));
    printSubmitButton = button("打印 1 张", true, () -> startPrint(false));
    weight(actions, printSampleButton);
    weight(actions, printSubmitButton);
    add(footer, actions);
    updatePrintPlan();
    body.setFocusableInTouchMode(true);
    body.requestFocus();
  }

  private void updatePrintPlan() {
    if (tab != 5 || printSummary == null || printPreview == null) return;
    preparedPrint = null;
    List<Record> candidates = printCandidates();
    printPreviewRecord = candidates.isEmpty() ? sampleRecord() : candidates.get(0);
    printConnection.setText(printer.status);
    printConnection.setTextColor(printer.connected() ? GREEN : MUTED);
    try {
      int start = printSource == 1 ? 0 : namingInteger(printStart, "起始设备号");
      for (Record candidate : candidates)
        if (candidate.number >= start) {
          printPreviewRecord = candidate;
          break;
        }
      int quantity = printInteger(printQuantity, "标签张数");
      int copies = printInteger(printCopies, "每张份数");
      preparedPrint =
          PrintRequest.records(candidates, projectId, start, quantity, copies, printSource == 0);
      printPreviewIndex = Math.min(printPreviewIndex, preparedPrint.size() - 1);
      printPreviewRecord = preparedPrint.record(printPreviewIndex);
      printSummary.setText(quantity + " 张 × " + copies + " 份 = " + preparedPrint.total + " 张");
      printSummary.setTextColor(BLUE);
      printRange.setText(
          "本次设备 "
              + preparedPrint.record(0).deviceName()
              + " 至 "
              + preparedPrint.record(preparedPrint.size() - 1).deviceName()
              + " · "
              + quantity
              + " 台（按编号顺序）");
      printSubmitButton.setText("打印 " + preparedPrint.total + " 张");
      printSubmitButton.setEnabled(true);
      printSubmitButton.setAlpha(1f);
    } catch (IllegalArgumentException error) {
      printSummary.setText(error.getMessage());
      printSummary.setTextColor(0xFFB45309);
      printRange.setText("当前来源共 " + candidates.size() + " 台设备");
      printSubmitButton.setText("打印标签");
      printSubmitButton.setEnabled(false);
      printSubmitButton.setAlpha(0.45f);
    }
    boolean example = candidates.isEmpty();
    printPreview.update(template, printPreviewRecord, -1);
    printPreviewCaption.setText(
        (example ? "示例设备 " : "设备 ")
            + printPreviewRecord.deviceName()
            + (preparedPrint == null
                ? ""
                : " · " + (printPreviewIndex + 1) + "/" + preparedPrint.size()));
  }

  private void startPrint(boolean sample) {
    if (printer.active()) {
      showPrintProgress();
      return;
    }
    updatePrintPlan();
    if (!sample && preparedPrint == null) return;
    if (!printer.connected()) {
      connectPrinter();
      return;
    }
    try {
      printer.start(sample ? PrintRequest.sample(printPreviewRecord) : preparedPrint, template);
      showPrintProgress();
    } catch (RuntimeException error) {
      toast(error.getMessage());
    }
  }

  private String printStatus() {
    return (printer.isTestSample() ? "测试样稿 · " : "")
        + "已确认完成 "
        + printer.completed
        + " / "
        + printer.total()
        + " 张\n"
        + (printer.active()
            ? (printer.paused ? "任务已暂停" : printer.inFlight ? "正在打印…" : "等待打印机…")
            : "任务结束")
        + (printer.message.isEmpty() ? "" : "\n\n" + printer.message);
  }

  private void showPrintProgress() {
    LinearLayout form = panel(Color.WHITE);
    printProgressText = text(printStatus(), 16);
    add(form, printProgressText);
    printPauseButton =
        button(printer.paused ? "继续未完成标签" : "暂停（当前张完成后）", false, () -> printer.togglePause());
    add(form, printPauseButton);
    AlertDialog d =
        new AlertDialog.Builder(this)
            .setTitle(printer.isTestSample() ? "测试样稿" : "打印任务")
            .setView(form)
            .setPositiveButton("返回", null)
            .setNegativeButton("取消未完成任务", (x, w) -> printer.cancel())
            .create();
    d.setOnDismissListener(
        x -> {
          printProgressText = null;
          printPauseButton = null;
          if (tab == 2 || tab == 4 || tab == 5) render();
        });
    d.show();
  }
}
