package com.sanduo.pdaregistry;

import com.sanduo.pdaregistry.Models.Record;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class LabelTemplate {
  private static final Pattern FIELD = Pattern.compile("\\{(设备名称|DevicesID|SN|日期|项目)\\}");
  public static final float WIDTH = 30, HEIGHT = 20;
  public final List<Line> lines = new ArrayList<>();

  public static final class Line {
    public String expression;
    public float pt, x, y, width;
    public boolean bold;
    public int align;

    public Line(
        String expression, float pt, boolean bold, float x, float y, float width, int align) {
      this.expression = expression;
      this.pt = pt;
      this.bold = bold;
      this.x = x;
      this.y = y;
      this.width = width;
      this.align = align;
    }

    public String resolve(Record r) {
      Matcher m = FIELD.matcher(expression);
      StringBuffer result = new StringBuffer();
      while (m.find()) {
        String value;
        switch (m.group(1)) {
          case "SN":
            value = r.sn;
            break;
          case "日期":
            value = r.date;
            break;
          case "项目":
            value = r.projectName;
            break;
          default:
            value = r.deviceName();
        }
        m.appendReplacement(result, Matcher.quoteReplacement(value));
      }
      m.appendTail(result);
      return result.toString();
    }

    public float fontMm() {
      return pt * 25.4f / 72f;
    }

    public float boxHeight() {
      return fontMm() * 1.35f;
    }
  }

  public static LabelTemplate defaults() {
    LabelTemplate t = new LabelTemplate();
    t.lines.add(new Line("{设备名称}", 24, true, 2, 1.5f, 26, 1));
    t.lines.add(new Line("SN{SN}", 7, false, 2, 14, 26, 1));
    return t;
  }

  public String toJson() {
    try {
      JSONArray array = new JSONArray();
      for (Line l : lines)
        array.put(
            new JSONObject()
                .put("text", l.expression)
                .put("pt", l.pt)
                .put("bold", l.bold)
                .put("x", l.x)
                .put("y", l.y)
                .put("width", l.width)
                .put("align", l.align));
      return new JSONObject()
          .put("version", 1)
          .put("width", 30)
          .put("height", 20)
          .put("lines", array)
          .toString();
    } catch (JSONException e) {
      throw new IllegalStateException(e);
    }
  }

  public static LabelTemplate fromJson(String json) {
    if (json == null || json.isEmpty()) return defaults();
    try {
      JSONObject obj = new JSONObject(json);
      if (obj.getInt("version") != 1) throw new JSONException("模板版本不兼容");
      LabelTemplate t = new LabelTemplate();
      JSONArray array = obj.getJSONArray("lines");
      if (array.length() == 0 || array.length() > 12) throw new JSONException("行数错误");
      for (int i = 0; i < array.length(); i++) {
        JSONObject l = array.getJSONObject(i);
        t.lines.add(
            new Line(
                l.getString("text"),
                (float) l.getDouble("pt"),
                l.getBoolean("bold"),
                (float) l.getDouble("x"),
                (float) l.getDouble("y"),
                (float) l.getDouble("width"),
                l.getInt("align")));
      }
      t.validate();
      return t;
    } catch (JSONException | IllegalArgumentException e) {
      throw new IllegalArgumentException("标签模板无效：" + e.getMessage(), e);
    }
  }

  public void validate() {
    if (lines.isEmpty() || lines.size() > 12) throw new IllegalArgumentException("标签须有 1–12 行内容");
    for (Line l : lines) {
      if (l.expression == null
          || l.expression.length() > 512
          || l.expression.contains("\n")
          || l.expression.contains("\r"))
        throw new IllegalArgumentException("每行内容须在 512 字以内，换行请添加新行");
      if (!Float.isFinite(l.pt) || l.pt < 4 || l.pt > 40)
        throw new IllegalArgumentException("字号范围为 4–40 pt");
      if (!Float.isFinite(l.x)
          || !Float.isFinite(l.y)
          || !Float.isFinite(l.width)
          || l.x < 0
          || l.y < 0
          || l.width <= 0
          || l.x + l.width > WIDTH + .001f
          || l.y + l.boxHeight() > HEIGHT + .001f)
        throw new IllegalArgumentException("内容超出 30 × 20 mm 标签，请调整位置或字号");
      if (l.align < 0 || l.align > 2) throw new IllegalArgumentException("对齐方式无效");
      String literals =
          l.expression
              .replace("{设备名称}", "")
              .replace("{DevicesID}", "")
              .replace("{SN}", "")
              .replace("{日期}", "")
              .replace("{项目}", "");
      if (literals.contains("{") || literals.contains("}"))
        throw new IllegalArgumentException("存在未识别字段，请使用插入字段按钮");
    }
  }
}
