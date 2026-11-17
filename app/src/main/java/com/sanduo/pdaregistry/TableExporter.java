package com.sanduo.pdaregistry;

import com.sanduo.pdaregistry.Models.Record;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class TableExporter {
  public static final String[] HEADERS = {"设备名称（DevicesID）", "SN", "登记日期", "所属项目"};

  public static final String[] INVENTORY_HEADERS = {
    "设备编号", "SN / 条码", "登记日期", "所属项目", "设备名称", "资产ID", "盘点状态", "盘点时间", "分类", "数量"
  };

  private TableExporter() {}

  public static String value(Record r, int column) {
    switch (column) {
      case 0:
        return r.deviceName();
      case 1:
        return r.sn;
      case 2:
        return r.date;
      case 3:
        return r.projectName;
      case 4:
        return r.equipmentName;
      case 5:
        return r.assetNumber;
      case 6:
        return r.checkedAt.isEmpty() ? "未盘点" : "已盘点";
      case 7:
        return r.checkedAt;
      case 8:
        return r.category;
      case 9:
        return "1";
      default:
        throw new IllegalArgumentException("未知导出字段");
    }
  }

  public static String xml(String text) {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < text.length(); ) {
      int c = text.codePointAt(i);
      i += Character.charCount(c);
      switch (c) {
        case '&':
          b.append("&amp;");
          break;
        case '<':
          b.append("&lt;");
          break;
        case '>':
          b.append("&gt;");
          break;
        case '"':
          b.append("&quot;");
          break;
        default:
          if (c == '\n'
              || c == '\r'
              || c == '\t'
              || c >= 0x20 && c <= 0xD7FF
              || c >= 0xE000 && c <= 0xFFFD
              || c >= 0x10000 && c <= 0x10FFFF) b.appendCodePoint(c);
      }
    }
    return b.toString();
  }

  public static String csvCell(String value) {
    String safe = value;
    if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) safe = "'" + safe;
    return "\"" + safe.replace("\"", "\"\"") + "\"";
  }

  public static void csv(OutputStream output, List<Record> records, boolean[] columns)
      throws IOException {
    csv(output, records, columns, HEADERS);
  }

  public static void csv(
      OutputStream output, List<Record> records, boolean[] columns, String[] headers)
      throws IOException {
    Writer w = new OutputStreamWriter(output, StandardCharsets.UTF_8);
    w.write('\uFEFF');
    boolean first = true;
    for (int c = 0; c < headers.length; c++)
      if (columns[c]) {
        if (!first) w.write(',');
        w.write(csvCell(headers[c]));
        first = false;
      }
    w.write("\r\n");
    for (Record r : records) {
      first = true;
      for (int c = 0; c < headers.length; c++)
        if (columns[c]) {
          if (!first) w.write(',');
          w.write(csvCell(value(r, c)));
          first = false;
        }
      w.write("\r\n");
    }
    w.flush();
  }

  private static void entry(ZipOutputStream z, String name, String content) throws IOException {
    z.putNextEntry(new ZipEntry(name));
    z.write(content.getBytes(StandardCharsets.UTF_8));
    z.closeEntry();
  }

  public static void xlsx(OutputStream output, List<Record> records, boolean[] columns)
      throws IOException {
    xlsx(output, records, columns, HEADERS);
  }

  public static void xlsx(
      OutputStream output, List<Record> records, boolean[] columns, String[] headers)
      throws IOException {
    if (records.size() > 1048575) throw new IOException("超过 Excel 单工作表行数上限，请分批导出");
    ZipOutputStream z = new ZipOutputStream(output, StandardCharsets.UTF_8);
    entry(
        z,
        "[Content_Types].xml",
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types"
            + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
            + " Extension=\"rels\""
            + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
            + " Extension=\"xml\" ContentType=\"application/xml\"/><Override"
            + " PartName=\"/xl/workbook.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override"
            + " PartName=\"/xl/worksheets/sheet1.xml\""
            + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
    entry(
        z,
        "_rels/.rels",
        "<Relationships"
            + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
            + " Id=\"rId1\""
            + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
            + " Target=\"xl/workbook.xml\"/></Relationships>");
    entry(
        z,
        "xl/workbook.xml",
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet"
            + " name=\"扫码登记\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
    entry(
        z,
        "xl/_rels/workbook.xml.rels",
        "<Relationships"
            + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
            + " Id=\"rId1\""
            + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
            + " Target=\"worksheets/sheet1.xml\"/></Relationships>");
    z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
    Writer w = new OutputStreamWriter(z, StandardCharsets.UTF_8);
    w.write(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet"
            + " xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetViews><sheetView"
            + " workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\""
            + " state=\"frozen\"/></sheetView></sheetViews><cols><col min=\"1\" max=\"10\""
            + " width=\"28\" customWidth=\"1\"/></cols><sheetData>");
    row(w, 1, null, columns, headers);
    int index = 2;
    for (Record r : records) row(w, index++, r, columns, headers);
    w.write("</sheetData></worksheet>");
    w.flush();
    z.closeEntry();
    z.finish();
    z.flush();
  }

  private static void row(Writer w, int row, Record r, boolean[] columns, String[] headers)
      throws IOException {
    w.write("<row r=\"" + row + "\">");
    int index = 0;
    for (int c = 0; c < headers.length; c++)
      if (columns[c]) {
        w.write(
            "<c r=\""
                + (char) ('A' + index++)
                + row
                + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + xml(r == null ? headers[c] : value(r, c))
                + "</t></is></c>");
      }
    w.write("</row>");
  }
}
