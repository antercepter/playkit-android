package com.kaltura.playkit.player;

import android.content.Context;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.kaltura.playkit.PKDrmParams;
import com.kaltura.playkit.PKMediaConfig;
import com.kaltura.playkit.PKMediaEntry;
import com.kaltura.playkit.PKMediaSource;
import com.kaltura.playkit.PlayKitManager;
import com.kaltura.playkit.Utils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Date;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

class ConfigFile {
    String putLogURL;
    float sendPercentage;
}

class DefaultProfiler extends Profiler {

    private static final boolean devMode = true;

    static final String SEPARATOR = "\t";
    private static final int SEND_INTERVAL_SEC = devMode ? 30 : 300;   // Report every 5 minutes

    private static String currentExperiment;
    private static DisplayMetrics metrics;
    private static File externalFilesDir;   // for debug logs

    private ExoPlayerProfilingListener analyticsListener;

    private String sessionId;
    long startTime;
    private final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();

    DefaultProfiler() {

        ioHandler.post(new Runnable() {
            @Override
            public void run() {

                // Send queue content to the server
                sendLogChunk();

                ioHandler.postDelayed(this, SEND_INTERVAL_SEC * 1000);
            }
        });

    }

    @Override
    public void newSession(final String sessionId) {

        if (this.sessionId != null) {
            // close current session
            closeSession();
        }

        this.sessionId = sessionId;
        if (sessionId == null) {
            return;     // the null profiler
        }

        this.startTime = SystemClock.elapsedRealtime();
        this.logQueue.clear();

        pkLog.d("New profiler with sessionId: " + sessionId);

        log("StartSession",
                field("now", System.currentTimeMillis()),
                field("strNow", new Date().toString()),
                field("sessionId", sessionId),
                // TODO: remove screenSize and screenDpi after backend is updated
                field("screenSize", metrics.widthPixels + "x" + metrics.heightPixels),
                field("screenDpi", metrics.xdpi + "x" + metrics.ydpi)
        );

        log("PlayKit",
                field("version", PlayKitManager.VERSION_STRING),
                field("clientTag", PlayKitManager.CLIENT_TAG)
        );

        log("Platform",
                field("name", "Android"),
                field("apiLevel", Build.VERSION.SDK_INT),
                field("chipset", MediaSupport.DEVICE_CHIPSET),
                field("brand", Build.BRAND),
                field("model", Build.MODEL),
                field("manufacturer", Build.MANUFACTURER),
                field("device", Build.DEVICE),
                field("tags", Build.TAGS),
                field("fingerprint", Build.FINGERPRINT),
                field("screenSize", metrics.widthPixels + "x" + metrics.heightPixels),
                field("screenDpi", metrics.xdpi + "x" + metrics.ydpi)
        );


        if (currentExperiment != null) {
            log("Experiment", field("info", currentExperiment));
        }
    }

    @Override
    public void startListener(ExoPlayerWrapper playerEngine) {
        if (analyticsListener == null) {
            analyticsListener = new ExoPlayerProfilingListener(this, playerEngine);
        }
        playerEngine.addAnalyticsListener(analyticsListener);
    }

    @Override
    public void stopListener(ExoPlayerWrapper playerEngine) {
        playerEngine.removeAnalyticsListener(analyticsListener);
    }


    static void initMembers(final Context context) {

        metrics = context.getResources().getDisplayMetrics();

        if (devMode) {
            externalFilesDir = context.getExternalFilesDir(null);
        }
    }


    private void sendLogChunk() {

        if (sessionId == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        Iterator<String> iterator = logQueue.iterator();
        while (iterator.hasNext()) {
            String entry = iterator.next();
            sb.append(entry).append('\n');
            iterator.remove();
        }

        if (sb.length() == 0) {
            return;
        }

        final String string = sb.toString();

        if (Looper.myLooper() == ioHandler.getLooper()) {
            postChunk(string);
        } else {
            ioHandler.post(new Runnable() {
                @Override
                public void run() {
                    postChunk(string);
                }
            });
        }

        if (devMode && externalFilesDir != null) {
            // Write to disk
            BufferedWriter writer = null;
            try {
                writer = new BufferedWriter(new FileWriter(new File(externalFilesDir, sessionId.replace(':', '_') + ".txt"), true));
                writer.append(string);
                writer.newLine();
                writer.flush();

            } catch (IOException e) {
                pkLog.e("Failed saving local log", e);
            } finally {
                Utils.safeClose(writer);
            }
        }
    }

    private void postChunk(String string) {
        try {
            Utils.executePost(postURL + "?mode=addChunk&sessionId=" + sessionId, string.getBytes(), null);
        } catch (IOException e) {
            // FIXME: 03/09/2018 Is it bad that we lost this log chunk?
            pkLog.e("Failed sending log", e);
            pkLog.e(string);
        }
    }

    @Override
    public void setCurrentExperiment(String currentExperiment) {
        DefaultProfiler.currentExperiment = currentExperiment;
    }

    private void log(String event, String... strings) {
        StringBuilder sb = startLog(event);
        logPayload(sb, strings);
        endLog(sb);
    }

    private StringBuilder startLog(String event) {
//        pkLog.v("Profiler.startLog: " + sessionId + " " + event);

        StringBuilder sb = new StringBuilder(100);
        sb
                .append(SystemClock.elapsedRealtime() - startTime)
                .append(SEPARATOR)
                .append(event);

        return sb;
    }

    private void logPayload(StringBuilder sb, String... strings) {
        for (String s : strings) {
            if (s == null) {
                continue;
            }
            sb.append(SEPARATOR).append(s);
        }
    }

    private void endLog(final StringBuilder sb) {
        logQueue.add(sb.toString());
    }

    void logWithPlaybackInfo(String event, PlayerEngine playerEngine, String... strings) {

        StringBuilder sb = startLog(event);

        logPayload(sb, timeField("pos", playerEngine.getCurrentPosition()), timeField("buf", playerEngine.getBufferedPosition()));
        logPayload(sb, strings);

        endLog(sb);
    }

    private static String toString(Enum e) {
        if (e == null) {
            return "null";
        }
        return e.name();
    }

    private static JsonObject toJSON(PKMediaEntry entry) {

        if (entry == null) {
            return null;
        }

        JsonObject json = new JsonObject();

        json.addProperty("id", entry.getId());
        json.addProperty("duration", entry.getDuration());
        json.addProperty("type", toString(entry.getMediaType()));

        if (entry.hasSources()) {
            JsonArray array = new JsonArray();
            for (PKMediaSource source : entry.getSources()) {
                array.add(toJSON(source));
            }
            json.add("sources", array);
        }

        return json;
    }

    private static JsonObject toJSON(PKMediaSource source) {
        JsonObject json = new JsonObject();

        json.addProperty("id", source.getId());
        json.addProperty("format", source.getMediaFormat().name());
        json.addProperty("url", source.getUrl());

        if (source.hasDrmParams()) {
            JsonArray array = new JsonArray();
            for (PKDrmParams params : source.getDrmData()) {
                PKDrmParams.Scheme scheme = params.getScheme();
                if (scheme != null) {
                    array.add(scheme.name());
                }
            }
            json.add("drm", array);
        }

        return json;
    }

    @Override
    public void onSetMedia(PlayerController playerController, PKMediaConfig mediaConfig) {
        JsonObject json = new JsonObject();
        json.add("entry", toJSON(mediaConfig.getMediaEntry()));
        json.addProperty("startPosition", mediaConfig.getStartPosition());

        log("SetMedia", field("config", json.toString()));
    }

    @Override
    public void onPrepareStarted(final PlayerEngine playerEngine, final PKMediaSourceConfig sourceConfig) {
        log("PrepareStarted", field("engine", playerEngine.getClass().getSimpleName()), field("source", sourceConfig.getUrl().toString()), field("useTextureView", sourceConfig.playerSettings.useTextureView()));
    }

    @Override
    public void onSeekRequested(PlayerEngine playerEngine, long position) {
        logWithPlaybackInfo("SeekRequested", playerEngine, timeField("targetPosition", position));
    }

    @Override
    public void onPauseRequested(PlayerEngine playerEngine) {
        logWithPlaybackInfo("PauseRequested", playerEngine);
    }

    @Override
    public void onReplayRequested(PlayerEngine playerEngine) {
        logWithPlaybackInfo("ReplayRequested", playerEngine);
    }

    @Override
    public void onPlayRequested(PlayerEngine playerEngine) {
        logWithPlaybackInfo("PlayRequested", playerEngine);
    }

    @Override
    public void onBandwidthSample(PlayerEngine playerEngine, long bitrate) {
        log("BandwidthSample", field("bandwidth", bitrate));
    }

    @Override
    public void onSessionFinished() {
        closeSession();
    }

    private void closeSession() {
        sendLogChunk();
    }

    @Override
    public void onViewportSizeChange(PlayerEngine playerEngine, int width, int height) {
        log("ViewportSizeChange", field("width", width), field("height", height));
    }

    @Override
    public void onDurationChanged(long duration) {
        log("DurationChanged", timeField("duration", duration));
    }
}
