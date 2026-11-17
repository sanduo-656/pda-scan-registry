package com.sanduo.pdaregistry;

import android.util.Xml;
import java.io.*;
import java.nio.charset.*;
import java.time.LocalDate;
import java.util.*;
import java.util.zip.*;
import org.xmlpull.v1.XmlPullParser;

/** Reads a worksheet or CSV without modifying application data. */
public final class TableImporter {
  private static final int MAX_BYTES = 32 * 1024 * 1024, MAX_ROWS = 50000;

  public static final class Row {
    public String sn = "",
        assetNumber = "",
        equipmentName = "",
        category = "",
        deviceNumber = "",
        date = "";
    public int sourceLine;
  }

  public static final class Result {
    public final List<Row> rows;
    public final String projectName;

    Result(List<Row> rows, String name) {
      this.rows = rows;
      projectName = name;
    }
  }

  public static final int ASSET = 0,
      NAME = 1,
      CATEGORY = 2,
      QUANTITY = 3,
      SN = 4,
      NUMBER = 5,
      DATE = 6,
      PROJECT = 7;
  public static final String[] FIELDS = {"资产ID", "设备名称", "分类", "数量", "SN", "设备编号", "登记日期", "所属项目"};

  public static final class Source {
    public final List<String> headers;
    private final List<List<String>> data;
    private final int headerLine;

    Source(List<List<String>> table) {
      int first = 0;
      while (first < table.size() && table.get(first).stream().allMatch(v -> v.trim().isEmpty()))
        first++;
      if (first == table.size()) throw new IllegalArgumentException("清单为空");
      headerLine = first + 1;
      headers = new ArrayList<>(table.get(first));
      if (headers.isEmpty() || headers.size() > 256)
        throw new IllegalArgumentException("表头列数须为 1–256");
      data = new ArrayList<>(table.subList(first + 1, table.size()));
    }

    public int rowCount() {
      int count = 0;
      for (List<String> row : data) if (row.stream().anyMatch(v -> !v.trim().isEmpty())) count++;
      return count;
    }

    public String columnLabel(int index) {
      int n = index + 1;
      String letters = "";
      while (n > 0) {
        n--;
        letters = (char) ('A' + n % 26) + letters;
        n /= 26;
      }
      String name = headers.get(index).trim();
      return letters + " · " + (name.isEmpty() ? "未命名列" : name);
    }

    public String sample(int column) {
      List<String> samples = new ArrayList<>();
      for (List<String> row : data) {
        String v = cell(row, column);
        if (v.startsWith("\u0001") || v.startsWith("\u0002")) v = v.substring(1);
        if (!v.isEmpty()) {
          samples.add(v.length() > 28 ? v.substring(0, 28) + "…" : v);
          if (samples.size() == 2) break;
        }
      }
      return samples.isEmpty() ? "此列暂无内容" : String.join("、", samples);
    }
  }

  private TableImporter() {}

  public static Result read(InputStream stream) throws Exception {
    Source source = readSource(stream);
    return map(source, suggest(source, true));
  }

  public static Source readSource(InputStream stream) throws Exception {
    byte[] bytes = bounded(stream, MAX_BYTES);
    if (bytes.length == 0) throw new IllegalArgumentException("文件为空");
    List<List<String>> table;
    if (bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K') table = xlsx(bytes);
    else {
      if (bytes.length > 1 && (bytes[0] & 255) == 0xD0 && (bytes[1] & 255) == 0xCF)
        throw new IllegalArgumentException("请将 XLS 另存为 XLSX 或 CSV 后导入");
      String content;
      try {
        content =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
      } catch (CharacterCodingException e) {
        content = Charset.forName("GB18030").decode(java.nio.ByteBuffer.wrap(bytes)).toString();
      }
      table = csv(content);
    }
    return new Source(table);
  }

  private static byte[] bounded(InputStream stream, int max) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int n;
    while ((n = stream.read(buffer)) != -1) {
      if (out.size() + n > max) throw new IOException("清单超过 32 MB，请拆分导入");
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }

  private static List<List<String>> csv(String text) {
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder value = new StringBuilder();
    boolean quoted = false, ended = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            value.append('"');
            i++;
          } else {
            quoted = false;
            ended = true;
          }
        } else value.append(c);
      } else if (c == '"') {
        if (value.length() != 0 || ended) throw new IllegalArgumentException("CSV 引号格式错误");
        quoted = true;
      } else if (c == ',' || c == '\n' || c == '\r') {
        row.add(value.toString());
        value.setLength(0);
        ended = false;
        if (c != ',') {
          rows.add(row);
          row = new ArrayList<>();
          if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        }
      } else {
        if (ended && !Character.isWhitespace(c)) throw new IllegalArgumentException("CSV 引号后有多余内容");
        if (!ended) value.append(c);
      }
      if (rows.size() > MAX_ROWS + 1 || row.size() > 256)
        throw new IllegalArgumentException("清单行列过多");
    }
    if (quoted) throw new IllegalArgumentException("CSV 引号未闭合");
    if (value.length() > 0 || !row.isEmpty() || ended) {
      row.add(value.toString());
      rows.add(row);
    }
    return rows;
  }

  private static XmlPullParser parser(byte[] bytes) throws Exception {
    XmlPullParser p = Xml.newPullParser();
    p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
    p.setInput(new ByteArrayInputStream(bytes), "UTF-8");
    return p;
  }

  private static String attr(XmlPullParser p, String name) {
    for (int i = 0; i < p.getAttributeCount(); i++)
      if (name.equals(p.getAttributeName(i))) return p.getAttributeValue(i);
    return "";
  }

  private static int next(XmlPullParser p) throws Exception {
    int event = p.next();
    if (event == XmlPullParser.DOCDECL) throw new IllegalArgumentException("不支持包含 DTD 的表格");
    return event;
  }

  private static List<List<String>> xlsx(byte[] bytes) throws Exception {
    Map<String, byte[]> zip = new HashMap<>();
    long expanded = 0;
    int entries = 0;
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = in.getNextEntry()) != null) {
        if (++entries > 2000) throw new IllegalArgumentException("表格过于复杂");
        byte[] data = bounded(in, MAX_BYTES);
        expanded += data.length;
        if (expanded > MAX_BYTES) throw new IllegalArgumentException("表格解压后超过 32 MB，请拆分");
        if (zip.put(entry.getName(), data) != null) throw new IllegalArgumentException("表格包含重复文件");
      }
    }
    byte[] workbook = zip.get("xl/workbook.xml"), rels = zip.get("xl/_rels/workbook.xml.rels");
    if (workbook == null || rels == null) throw new IllegalArgumentException("不是有效的 XLSX 文件");
    String sheetId = "", sheetPath = "";
    XmlPullParser p = parser(workbook);
    while (next(p) != XmlPullParser.END_DOCUMENT)
      if (p.getEventType() == XmlPullParser.START_TAG && p.getName().equals("sheet")) {
        sheetId = attr(p, "id");
        break;
      }
    p = parser(rels);
    while (next(p) != XmlPullParser.END_DOCUMENT)
      if (p.getEventType() == XmlPullParser.START_TAG
          && p.getName().equals("Relationship")
          && attr(p, "Id").equals(sheetId)) {
        String target = attr(p, "Target");
        if (!attr(p, "TargetMode").isEmpty()) throw new IllegalArgumentException("不支持外部工作表");
        sheetPath = target.startsWith("/") ? target.substring(1) : "xl/" + target;
        break;
      }
    byte[] sheet = zip.get(sheetPath);
    if (sheet == null) throw new IllegalArgumentException("无法读取第一个工作表");
    List<String> shared = new ArrayList<>();
    if (zip.containsKey("xl/sharedStrings.xml")) {
      p = parser(zip.get("xl/sharedStrings.xml"));
      StringBuilder item = null;
      while (next(p) != XmlPullParser.END_DOCUMENT) {
        if (p.getEventType() == XmlPullParser.START_TAG && p.getName().equals("si"))
          item = new StringBuilder();
        else if (p.getEventType() == XmlPullParser.START_TAG
            && p.getName().equals("t")
            && item != null) item.append(p.nextText());
        else if (p.getEventType() == XmlPullParser.END_TAG && p.getName().equals("si")) {
          shared.add(item.toString());
          item = null;
        }
      }
    }
    List<List<String>> rows = new ArrayList<>();
    List<String> row = null;
    p = parser(sheet);
    String type = "";
    StringBuilder cell = null;
    int column = 0;
    boolean formula = false;
    while (next(p) != XmlPullParser.END_DOCUMENT) {
      if (p.getEventType() == XmlPullParser.START_TAG) {
        switch (p.getName()) {
          case "row":
            row = new ArrayList<>();
            break;
          case "c":
            type = attr(p, "t");
            cell = new StringBuilder();
            formula = false;
            String ref = attr(p, "r");
            column = 0;
            for (int i = 0; i < ref.length() && Character.isLetter(ref.charAt(i)); i++)
              column = column * 26 + Character.toUpperCase(ref.charAt(i)) - 'A' + 1;
            if (column == 0) column = row == null ? 1 : row.size() + 1;
            if (column > 256) throw new IllegalArgumentException("表格超过 256 列");
            break;
          case "f":
            formula = true;
            break;
          case "v":
          case "t":
            if (cell != null) cell.append(p.nextText());
            break;
        }
      } else if (p.getEventType() == XmlPullParser.END_TAG) {
        if (p.getName().equals("c") && row != null && cell != null) {
          String value = cell.toString();
          if (type.equals("s")) {
            int index = Integer.parseInt(value);
            if (index < 0 || index >= shared.size())
              throw new IllegalArgumentException("表格字符串索引无效");
            value = shared.get(index);
          } else if (!value.isEmpty() && (type.isEmpty() || type.equals("n")))
            value = "\u0001" + value;
          if (formula) value = "\u0002" + value;
          while (row.size() < column) row.add("");
          row.set(column - 1, value);
          cell = null;
        } else if (p.getName().equals("row") && row != null) {
          rows.add(row);
          row = null;
          if (rows.size() > MAX_ROWS + 1) throw new IllegalArgumentException("最多导入 50000 台设备");
        }
      }
    }
    return rows;
  }

  private static String cell(List<String> row, int index) {
    if (index < 0 || index >= row.size()) return "";
    String value = row.get(index);
    if (value.startsWith("\u0001") || value.startsWith("\u0002"))
      return value.substring(0, 1) + value.substring(1).trim();
    return value.trim();
  }

  private static String literal(String value, int line) {
    if (value.startsWith("\u0002"))
      throw new IllegalArgumentException("第 " + line + " 行：导入字段不能使用公式，请粘贴为文本值");
    return value.startsWith("\u0001")
        ? new java.math.BigDecimal(value.substring(1)).toPlainString()
        : value;
  }

  public static int[] suggest(Source source) {
    return suggest(source, false);
  }

  private static int[] suggest(Source source, boolean strict) {
    int[] mapping = new int[FIELDS.length];
    Arrays.fill(mapping, -1);
    for (int i = 0; i < source.headers.size(); i++) {
      String key =
          source
              .headers
              .get(i)
              .trim()
              .toLowerCase(Locale.ROOT)
              .replace(" ", "")
              .replace("（", "(")
              .replace("）", ")");
      int field = -1;
      if (Arrays.asList("sn", "sn/条码", "序列号", "条码", "条码编号", "设备sn").contains(key)) field = SN;
      else if (Arrays.asList(
              "资产id",
              "固定资产编码",
              "固定资产编号",
              "资产编号",
              "资产编码",
              "资产号",
              "assetid",
              "asset_id",
              "assetno",
              "assetnumber",
              "asset_no")
          .contains(key)) field = ASSET;
      else if (Arrays.asList("设备名称", "设备名字", "设备名", "资产名称", "名字", "名称", "equipmentname")
          .contains(key)) field = NAME;
      else if (Arrays.asList("分类", "资产分类", "设备分类", "类别", "资产类别", "设备类别", "category").contains(key))
        field = CATEGORY;
      else if (Arrays.asList("数量", "设备数量", "资产数量", "quantity", "qty").contains(key))
        field = QUANTITY;
      else if (Arrays.asList("设备编号", "devicesid", "设备名称(devicesid)", "设备编号(devicesid)")
          .contains(key)) field = NUMBER;
      else if (Arrays.asList("登记日期", "日期").contains(key)) field = DATE;
      else if (Arrays.asList("所属项目", "项目", "项目名称").contains(key)) field = PROJECT;
      if (field >= 0) {
        if (mapping[field] >= 0) {
          if (strict) throw new IllegalArgumentException("表头重复：" + source.headers.get(i));
        } else mapping[field] = i;
      }
    }
    return mapping;
  }

  public static void validateMapping(Source source, int[] mapping) {
    if (mapping == null || mapping.length != FIELDS.length)
      throw new IllegalArgumentException("请选择字段对应关系");
    Set<Integer> used = new HashSet<>();
    for (int i = 0; i < mapping.length; i++) {
      if (mapping[i] < -1 || mapping[i] >= source.headers.size())
        throw new IllegalArgumentException("字段列已失效，请重新选择");
      if (mapping[i] >= 0 && !used.add(mapping[i]))
        throw new IllegalArgumentException("同一列只能对应一个字段，请检查“" + FIELDS[i] + "”");
    }
    if (mapping[ASSET] < 0 && mapping[SN] < 0)
      throw new IllegalArgumentException("资产ID 与 SN 至少对应一列");
  }

  public static Result map(Source source, int[] mapping) {
    validateMapping(source, mapping);
    List<Row> result = new ArrayList<>();
    String lastName = "", lastCategory = "", project = "";
    for (int i = 0; i < source.data.size(); i++) {
      List<String> values = source.data.get(i);
      if (values.stream().allMatch(v -> v.trim().isEmpty())) continue;
      Row row = new Row();
      row.sourceLine = source.headerLine + i + 1;
      row.sn = cell(values, mapping[SN]);
      row.assetNumber = cell(values, mapping[ASSET]);
      row.equipmentName = literal(cell(values, mapping[NAME]), row.sourceLine);
      row.category = literal(cell(values, mapping[CATEGORY]), row.sourceLine);
      if (!row.equipmentName.isEmpty()) lastName = row.equipmentName;
      else row.equipmentName = lastName;
      if (!row.category.isEmpty()) lastCategory = row.category;
      else row.category = lastCategory;
      row.deviceNumber = literal(cell(values, mapping[NUMBER]), row.sourceLine);
      row.date = cell(values, mapping[DATE]);
      String quantity = literal(cell(values, mapping[QUANTITY]), row.sourceLine);
      if (!quantity.isEmpty()) {
        try {
          if (new java.math.BigDecimal(quantity).compareTo(java.math.BigDecimal.ONE) != 0)
            throw new NumberFormatException();
        } catch (NumberFormatException e) {
          throw new IllegalArgumentException("第 " + row.sourceLine + " 行：每台设备须独立一行，数量只能为 1 或留空");
        }
      }
      for (String code : new String[] {row.sn, row.assetNumber})
        if (code.startsWith("\u0001") || code.startsWith("\u0002"))
          throw new IllegalArgumentException("第 " + row.sourceLine + " 行：SN / 资产ID请设为文本，不能使用数值或公式");
      String group = literal(cell(values, mapping[PROJECT]), row.sourceLine);
      if (!group.isEmpty()) {
        if (!project.isEmpty() && !project.equals(group))
          throw new IllegalArgumentException("一个文件只能导入一个项目，请按项目拆分");
        project = group;
      }
      result.add(row);
    }
    validate(result);
    return new Result(result, project);
  }

  public static void validate(List<Row> rows) {
    if (rows.isEmpty() || rows.size() > MAX_ROWS)
      throw new IllegalArgumentException("清单须包含 1–50000 台设备");
    Set<String> codes = new HashSet<>(), names = new HashSet<>();
    int number = 0;
    for (Row row : rows) {
      number++;
      try {
        row.sn = row.sn.trim();
        row.assetNumber = row.assetNumber.trim();
        if (row.sn.isEmpty() && row.assetNumber.isEmpty())
          throw new IllegalArgumentException("缺少 SN 或资产编号");
        Set<String> own = new HashSet<>();
        for (String code : new String[] {row.sn, row.assetNumber})
          if (!code.isEmpty()) own.add(RegistryStore.normalizeSn(code));
        for (String code : own)
          if (!codes.add(code)) throw new IllegalArgumentException("SN / 资产编号重复：" + code);
        if (row.equipmentName.length() > 120
            || row.category.length() > 120
            || row.deviceNumber.length() > 60) throw new IllegalArgumentException("名称或编号过长");
        if (!row.date.isEmpty()) LocalDate.parse(row.date);
        String name = row.deviceNumber.isEmpty() ? Models.deviceName(number) : row.deviceNumber;
        if (!names.add(name)) throw new IllegalArgumentException("设备编号重复：" + name);
      } catch (RuntimeException e) {
        throw new IllegalArgumentException(
            "第 " + (row.sourceLine > 0 ? row.sourceLine : number + 1) + " 行：" + e.getMessage());
      }
    }
  }
}
