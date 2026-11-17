package com.sanduo.pdaregistry;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/** Preloads the short offline warning; repeated scans replace the previous sound. */
final class WarningSound implements AutoCloseable {
  private final Handler handler = new Handler(Looper.getMainLooper());
  private SoundPool pool;
  private int sampleId, streamId;
  private boolean loaded, pending, closed;
  private ToneGenerator fallback;
  private final Runnable releaseFallback = this::releaseFallback;

  WarningSound(Context context) {
    try {
      pool =
          new SoundPool.Builder()
              .setMaxStreams(1)
              .setAudioAttributes(
                  new AudioAttributes.Builder()
                      .setUsage(AudioAttributes.USAGE_MEDIA)
                      .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                      .build())
              .build();
      pool.setOnLoadCompleteListener(
          (soundPool, sample, status) -> {
            if (closed || sample != sampleId) return;
            loaded = status == 0;
            if (!loaded) {
              pool.release();
              pool = null;
            }
            if (pending) play();
          });
      sampleId = pool.load(context, R.raw.scan_warning, 1);
      if (sampleId == 0) {
        pool.release();
        pool = null;
      }
    } catch (RuntimeException ignored) {
      if (pool != null) {
        pool.release();
        pool = null;
      }
    }
  }

  void play() {
    if (closed) return;
    stop();
    if (pool != null) {
      if (!loaded) {
        pending = true;
        return;
      }
      try {
        streamId = pool.play(sampleId, 1f, 1f, 1, 0, 1f);
      } catch (RuntimeException ignored) {
      }
      if (streamId != 0) return;
    }
    try {
      fallback = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
      fallback.startTone(ToneGenerator.TONE_PROP_NACK, 300);
      handler.postDelayed(releaseFallback, 400);
    } catch (RuntimeException ignored) {
      releaseFallback();
    }
  }

  void stop() {
    pending = false;
    if (pool != null && streamId != 0) {
      try {
        pool.stop(streamId);
      } catch (RuntimeException ignored) {
      }
    }
    streamId = 0;
    handler.removeCallbacks(releaseFallback);
    releaseFallback();
  }

  private void releaseFallback() {
    if (fallback != null) {
      fallback.release();
      fallback = null;
    }
  }

  @Override
  public void close() {
    if (closed) return;
    stop();
    closed = true;
    if (pool != null) {
      pool.release();
      pool = null;
    }
  }
}
