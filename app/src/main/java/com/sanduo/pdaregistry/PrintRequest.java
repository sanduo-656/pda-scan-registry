package com.sanduo.pdaregistry;

import com.sanduo.pdaregistry.Models.Record;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** An exact, immutable print selection. No printer code expands it to a project. */
public final class PrintRequest {
  private final List<Record> rows;
  public final int copies, total;
  public final boolean testSample;

  private PrintRequest(List<Record> rows, int copies, boolean testSample) {
    this.rows = rows;
    this.copies = copies;
    this.total = Math.multiplyExact(rows.size(), copies);
    this.testSample = testSample;
  }

  public static PrintRequest records(
      List<Record> candidates,
      long projectId,
      int startNumber,
      int quantity,
      int copies,
      boolean requireExactStart) {
    if (quantity < 1) throw new IllegalArgumentException("标签张数须为大于 0 的整数");
    if (copies < 1 || copies > 99) throw new IllegalArgumentException("每张份数须为 1–99 的整数");
    if (startNumber < 0) throw new IllegalArgumentException("起始设备号不正确");
    List<Record> eligible = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Record r : candidates) {
      if (r.projectId != projectId) throw new IllegalArgumentException("打印记录须属于当前项目");
      if (r.id == null || !ids.add(r.id)) throw new IllegalArgumentException("打印记录无效或重复");
      if (r.number >= startNumber) eligible.add(snapshot(r));
    }
    eligible.sort(Comparator.comparingInt(r -> r.number));
    if (requireExactStart && (eligible.isEmpty() || eligible.get(0).number != startNumber))
      throw new IllegalArgumentException("设备 " + Models.deviceName(startNumber) + " 尚未登记");
    if (eligible.size() < quantity)
      throw new IllegalArgumentException("当前范围只有 " + eligible.size() + " 张标签，请减小张数");
    if ((long) quantity * copies > Integer.MAX_VALUE)
      throw new IllegalArgumentException("打印总量过大，请分批打印");
    return new PrintRequest(new ArrayList<>(eligible.subList(0, quantity)), copies, false);
  }

  public static PrintRequest sample(Record preview) {
    if (preview == null) throw new IllegalArgumentException("请先查看标签预览");
    Record sample = snapshot(preview);
    sample.id = null;
    return new PrintRequest(Collections.singletonList(sample), 1, true);
  }

  public int size() {
    return rows.size();
  }

  public Record record(int index) {
    return snapshot(rows.get(index));
  }

  private static Record snapshot(Record r) {
    Record copy = new Record();
    copy.id = r.id;
    copy.sn = r.sn;
    copy.date = r.date;
    copy.createdAt = r.createdAt;
    copy.projectName = r.projectName;
    copy.projectId = r.projectId;
    copy.number = r.number;
    copy.namePrefix = r.namePrefix;
    copy.numberDigits = r.numberDigits;
    copy.printed = r.printed;
    return copy;
  }
}
