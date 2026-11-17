package com.sanduo.pdaregistry;

import java.util.Locale;

public final class Models {
  private Models() {}

  public static String deviceName(int number) {
    return deviceName("", 3, number);
  }

  public static String deviceName(String prefix, int digits, int number) {
    return prefix + String.format(Locale.ROOT, "%0" + digits + "d", number);
  }

  public static final class Project {
    public long id;
    public String name;
    public boolean archived;
    public boolean inventory;
    public int checked;
    public int nextNumber, count, today;
    public String template;
    public String namePrefix = "";
    public int numberDigits = 3, startNumber = 1;

    public String deviceName(int number) {
      return Models.deviceName(namePrefix, numberDigits, number);
    }
  }

  public static final class Record {
    public String id, sn, date, createdAt, projectName;
    public String equipmentName = "",
        assetNumber = "",
        category = "",
        importedName = "",
        checkedAt = "";
    public long projectId;
    public int number, printed;
    public String namePrefix = "";
    public int numberDigits = 3;

    public String deviceName() {
      if (!importedName.isEmpty()) return importedName;
      return Models.deviceName(namePrefix, numberDigits, number);
    }
  }

  public static final class ScanResult {
    public final Record record;
    public final boolean duplicate;

    public ScanResult(Record record, boolean duplicate) {
      this.record = record;
      this.duplicate = duplicate;
    }
  }
}
