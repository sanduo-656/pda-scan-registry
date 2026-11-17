package com.sanduo.pdaregistry;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import com.dothantech.lpapi.LPAPI;
import com.dothantech.printer.IDzPrinter;
import com.dothantech.printer.IDzPrinter.PrintProgress;
import com.dothantech.printer.IDzPrinter.PrinterAddress;
import com.dothantech.printer.IDzPrinter.PrinterState;
import com.sanduo.pdaregistry.Models.Record;
import java.util.LinkedHashMap;
import java.util.Map;

/** One SDK job per physical copy; only SDK Success advances the queue. */
public final class PrinterController {
  public interface Listener {
    void changed();
  }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final RegistryStore store;
  private final Listener listener;
  private LPAPI api;
  public final Map<String, PrinterAddress> discovered = new LinkedHashMap<>();
  public String status = "未连接", message = "";
  private PrintRequest request;
  private final JobTransport transport;

  interface JobTransport {
    boolean connected();

    void submit(Record record, LabelTemplate template, int key);

    void cancel();

    void close();
  }

  private LabelTemplate template;
  private int copies, total, jobSequence, currentJobKey;
  private final Runnable advance = this::next;
  public int completed;
  public boolean paused, inFlight;
  private boolean closed;

  public PrinterController(RegistryStore store, Listener listener) {
    this(store, listener, null);
  }

  PrinterController(RegistryStore store, Listener listener, JobTransport transport) {
    this.store = store;
    this.listener = listener;
    this.transport = transport;
  }

  private LPAPI api() {
    if (api == null)
      api =
          LPAPI.Factory.createInstance(
              new LPAPI.Callback() {
                @Override
                public void onProgressInfo(IDzPrinter.ProgressInfo info, Object extra) {}

                @Override
                public void onStateChange(PrinterAddress address, PrinterState state) {
                  handler.post(
                      () -> {
                        if (closed) return;
                        switch (state) {
                          case Connecting:
                            status = "连接中";
                            break;
                          case Connected:
                          case Connected2:
                            status = "已连接 " + (address == null ? "打印机" : address.shownName);
                            break;
                          case Disconnected:
                            status = "未连接";
                            if (active()) {
                              paused = true;
                              if (inFlight) {
                                inFlight = false;
                                message = "连接中断，请核对最后一张标签后重试";
                              }
                            }
                            break;
                          default:
                            break;
                        }
                        listener.changed();
                      });
                }

                @Override
                public void onPrinterDiscovery(PrinterAddress address, Object extra) {
                  handler.post(
                      () -> {
                        if (closed || address == null) return;
                        discovered.put(address.key(), address);
                        listener.changed();
                      });
                }

                @Override
                public void onPrintProgress(
                    PrinterAddress address,
                    IDzPrinter.PrintData data,
                    PrintProgress progress,
                    Object extra) {
                  handler.post(
                      () -> {
                        if (closed || !inFlight || data == null) return;
                        int key =
                            data.printParam == null
                                ? data.pageKey
                                : data.printParam.getInt(
                                    IDzPrinter.PrintParamName.PAGE_KEY, data.pageKey);
                        handleProgress(key, progress);
                      });
                }
              });
    return api;
  }

  // Both the SDK callback and the isolated device test transport enter here on the main thread.
  void handleProgress(int key, PrintProgress progress) {
    if (closed || !inFlight || request == null || key != currentJobKey) return;
    if (progress == PrintProgress.Success) {
      inFlight = false;
      try {
        if (!request.testSample) store.markPrinted(request.record(completed / copies).id);
        completed++;
        message = completed == total ? (request.testSample ? "测试样稿打印完成" : "打印完成") : "";
        handler.postDelayed(advance, 250);
      } catch (RuntimeException error) {
        paused = true;
        message = "标签已打印，但保存状态失败；请核对后再操作：" + error.getMessage();
      }
      listener.changed();
    } else if (progress == PrintProgress.Failed) {
      inFlight = false;
      paused = true;
      message = "打印失败，请检查纸张、上盖和连接；核对最后一张后重试";
      listener.changed();
    }
  }

  public boolean isTestSample() {
    return request != null && request.testSample;
  }

  public boolean isPrintingProject(long projectId) {
    return active() && request != null && request.record(0).projectId == projectId;
  }

  public boolean connected() {
    return transport != null ? transport.connected() : api != null && api.isPrinterOpened();
  }

  public boolean active() {
    return completed < total;
  }

  public int total() {
    return total;
  }

  public void discover() {
    discovered.clear();
    if (!api().discovery()) throw new IllegalStateException("无法搜索，请开启蓝牙及定位服务");
    message = "正在搜索附近打印机";
    listener.changed();
  }

  public void stopDiscovery() {
    if (api != null) api.stopDiscovery();
  }

  public void connect(PrinterAddress address) {
    stopDiscovery();
    if (!api().openPrinterByAddress(address)) throw new IllegalStateException("无法连接打印机");
    status = "连接中";
    listener.changed();
  }

  public void connect(String address) {
    stopDiscovery();
    if (!api().openPrinter(address)) throw new IllegalStateException("无法连接，请检查打印机地址");
    status = "连接中";
    listener.changed();
  }

  public void start(PrintRequest request, LabelTemplate value) {
    if (active()) throw new IllegalStateException("请先完成或取消当前打印任务");
    if (!connected()) throw new IllegalStateException("请先连接 DP30S 打印机");
    value.validate();
    if (request == null || request.size() == 0) throw new IllegalArgumentException("请选择标签");
    handler.removeCallbacks(advance);
    template = LabelTemplate.fromJson(value.toJson());
    this.request = request;
    copies = request.copies;
    total = request.total;
    completed = 0;
    paused = false;
    message = "";
    next();
  }

  private void next() {
    if (closed || !active() || paused || inFlight) return;
    if (!connected()) {
      paused = true;
      message = "打印机未连接，请连接后继续";
      listener.changed();
      return;
    }
    try {
      Record r = request.record(completed / copies);
      currentJobKey = ++jobSequence;
      inFlight = true;
      if (transport != null) {
        transport.submit(r, template, currentJobKey);
        listener.changed();
        return;
      }
      if (!api.startJob(30, 20, 0)) throw new IllegalStateException("无法创建打印任务");
      for (LabelTemplate.Line line : template.lines) {
        String text = line.resolve(r);
        Paint p = new Paint();
        p.setTypeface(Typeface.create("sans-serif", line.bold ? Typeface.BOLD : Typeface.NORMAL));
        float mm = LabelView.fitFont(p, text, line.width * 100, line.fontMm() * 100) / 100;
        api.setItemHorizontalAlignment(line.align);
        api.setItemVerticalAlignment(0);
        if (!api.drawTextRegular(
            text, line.x, line.y, line.width, line.boxHeight(), mm, line.bold ? 1 : 0))
          throw new IllegalStateException("无法绘制标签内容");
      }
      Bundle params = new Bundle();
      params.putInt(IDzPrinter.PrintParamName.PAGE_KEY, currentJobKey);
      params.putInt(IDzPrinter.PrintParamName.PRINT_COPIES, 1);
      inFlight = true;
      if (!api.commitJobWithParam(params)) {
        inFlight = false;
        throw new IllegalStateException("打印任务提交失败");
      }
    } catch (RuntimeException error) {
      inFlight = false;
      paused = true;
      message = error.getMessage();
      if (api != null) api.abortJob();
    }
    listener.changed();
  }

  public void togglePause() {
    if (!active()) return;
    paused = !paused;
    if (!paused) {
      message = "";
      next();
    }
    listener.changed();
  }

  public void cancel() {
    handler.removeCallbacks(advance);
    paused = true;
    inFlight = false;
    message = "任务已取消，已确认完成 " + completed + " 张；已打印记录保留状态";
    request = null;
    completed = total = 0;
    if (transport != null) transport.cancel();
    else if (api != null) api.cancel();
    listener.changed();
  }

  public void close() {
    closed = true;
    handler.removeCallbacksAndMessages(null);
    if (transport != null) transport.close();
    else if (api != null) api.quit();
  }
}
