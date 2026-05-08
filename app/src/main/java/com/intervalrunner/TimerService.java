package com.intervalrunner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.location.Location;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

/**
 * Foreground service that owns the timer state.
 * The UI binds to this and polls state every second via its own Handler —
 * no TICK broadcasts, which fixes the "frozen countdown" bug on Nothing OS / OEM Androids.
 * PHASE_CHANGE and FINISHED are still broadcast because the UI needs to react immediately.
 */
public class TimerService extends Service {

    public static final String ACTION_START  = "ACTION_START";
    public static final String ACTION_PAUSE  = "ACTION_PAUSE";
    public static final String ACTION_RESUME = "ACTION_RESUME";

    private static final String CHANNEL_ID      = "interval_runner_channel";
    private static final int    NOTIFICATION_ID  = 1;
    private static final int    TICK_MS          = 1000;

    // ---- Config ----
    private int walkSeconds;
    private int runSeconds;
    private int repetitions;

    // ---- Timer state (read by UI via getters) ----
    private int     currentRep  = 1;
    private boolean isWalking   = true;
    private boolean timerActive = false; // true while counting down (not paused, not stopped)
    private boolean isPaused    = false;
    private int     secondsLeft = 0;

    // ---- Internal ticker ----
    private final Handler  handler      = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            if (!timerActive || isPaused) return;

            if (isWalking) walkSecondsTotal++; else runSecondsTotal++;

            secondsLeft--;
            updateNotification();

            if (secondsLeft > 0 && secondsLeft <= 10) playTickSound();

            if (!isWalking && secondsLeft > 0 && secondsLeft % 60 == 0) playMinuteBeep();

            if (secondsLeft <= 0) {
                advancePhase();
            } else {
                handler.postDelayed(this, TICK_MS);
            }
        }
    };

    private void playMinuteBeep() {
        playSamples(buildPcm(
                new int[]{880, 0, 880},
                new int[]{80, 60, 80}
        ), false);
    }

    // ---- GPS / pace ----
    private FusedLocationProviderClient fusedLocation;
    private LocationCallback locationCallback;
    private Location lastLocation;
    private float walkMetresTotal  = 0f, runMetresTotal  = 0f;
    private int   walkSecondsTotal = 0,  runSecondsTotal = 0;
    private PaceStore paceStore;

    // ---- Sound ----
    private AudioManager audioManager;

    // ---- Wake lock ----
    private PowerManager.WakeLock wakeLock;

    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        TimerService getService() { return TimerService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        paceStore = new PaceStore(this);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        fusedLocation = LocationServices.getFusedLocationProviderClient(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IntervalRunner::WakeLock");
        prebuildSounds();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        switch (intent.getAction() == null ? "" : intent.getAction()) {
            case ACTION_START:
                walkSeconds  = intent.getIntExtra("walkMinutes", 4) * 60;
                runSeconds   = intent.getIntExtra("runMinutes",  1) * 60;
                repetitions  = intent.getIntExtra("repetitions", 3);
                startForeground(NOTIFICATION_ID, buildNotification());
                beginWorkout();
                break;
            case ACTION_PAUSE:  pauseTimer();  break;
            case ACTION_RESUME: resumeTimer(); break;
        }
        return START_NOT_STICKY;
    }

    // =========================================================================
    // Workout flow
    // =========================================================================

    private void beginWorkout() {
        timerActive = true;
        isPaused    = false;
        currentRep  = 1;
        isWalking   = true;
        secondsLeft = walkSeconds;
        walkMetresTotal = runMetresTotal = 0;
        walkSecondsTotal = runSecondsTotal = 0;

        long totalMs = (long)(walkSeconds + runSeconds) * repetitions * 1000L + 60_000L;
        if (!wakeLock.isHeld()) wakeLock.acquire(totalMs);

        startLocationUpdates();
        playWalkSound();
        broadcastPhaseChange();
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    private void advancePhase() {
        if (isWalking) {
            isWalking   = false;
            secondsLeft = runSeconds;
            playRunSound();
            broadcastPhaseChange();
        } else {
            if (currentRep >= repetitions) {
                finishWorkout();
                return;
            }
            currentRep++;
            isWalking   = true;
            secondsLeft = walkSeconds;
            playWalkSound();
            broadcastPhaseChange();
        }
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    private void finishWorkout() {
        timerActive = false;
        handler.removeCallbacks(tickRunnable);
        stopLocationUpdates();
        savePaceData();
        playFinishSound();
        if (wakeLock.isHeld()) wakeLock.release();
        broadcastFinished();
        // Delay stopSelf so the broadcast has time to be delivered
        handler.postDelayed(this::stopSelf, 3000);
    }

    // =========================================================================
    // Pause / Resume / Stop
    // =========================================================================

    public void pauseTimer() {
        if (!timerActive || isPaused) return;
        isPaused = true;
        handler.removeCallbacks(tickRunnable);
        stopLocationUpdates();
        updateNotification();
        broadcastPauseState(true);
    }

    public void resumeTimer() {
        if (!timerActive || !isPaused) return;
        isPaused = false;
        startLocationUpdates();
        broadcastPauseState(false);
        handler.postDelayed(tickRunnable, TICK_MS);
    }

    public void stopTimer() {
        timerActive = false;
        isPaused    = false;
        handler.removeCallbacks(tickRunnable);
        stopLocationUpdates();
        if (wakeLock.isHeld()) wakeLock.release();
    }

    // =========================================================================
    // Sound — AudioTrack on USAGE_ALARM, works through lock screen
    // =========================================================================

    // Pre-built PCM buffers so there's no synthesis delay at phase-change time
    private short[] samplesWalk;
    private short[] samplesRun;
    private short[] samplesFinish;
    private short[] samplesTick;

    private static final int SAMPLE_RATE = 44100;

    private void prebuildSounds() {
        // Walk: E5→C5 played TWICE — calm descending, "slow down"
        short[] walkOnce = buildPcm(
                new int[]{659,  0, 523,  0, 392},
                new int[]{220, 70, 320, 100, 280});
        samplesWalk = concat(walkOnce,
                buildPcm(new int[]{0}, new int[]{200}), // gap between repeats
                walkOnce);

        // Run: C5→E5→G5 played TWICE — ascending, energetic
        short[] runOnce = buildPcm(
                new int[]{523,  0, 659,  0, 784},
                new int[]{180, 70, 180,  70, 420});
        samplesRun = concat(runOnce,
                buildPcm(new int[]{0}, new int[]{180}),
                runOnce);

        // Finish: C→E→G→C5 fanfare (only once, already satisfying)
        samplesFinish = buildPcm(
                new int[]{523,  0, 659,  0, 784,  0, 1047},
                new int[]{200, 70, 200,  70, 200, 70,  900});

        // Tick: very short soft click (50 ms at 880 Hz, low amplitude)
        samplesTick = buildPcm(new int[]{880}, new int[]{45});
        // halve amplitude so it's subtle
        for (int i = 0; i < samplesTick.length; i++) samplesTick[i] /= 4;
    }

    /** Concatenate multiple PCM buffers into one. */
    private short[] concat(short[]... parts) {
        int total = 0;
        for (short[] p : parts) total += p.length;
        short[] out = new short[total];
        int pos = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, pos, p.length); pos += p.length; }
        return out;
    }

    /** Synthesise a sequence of sine-wave tones into one PCM buffer. freq=0 → silence. */
    private short[] buildPcm(int[] freqsHz, int[] durationsMs) {
        int totalSamples = 0;
        for (int d : durationsMs) totalSamples += SAMPLE_RATE * d / 1000;
        short[] out = new short[totalSamples];
        int pos = 0;
        for (int i = 0; i < freqsHz.length; i++) {
            int freq    = freqsHz[i];
            int numSamp = SAMPLE_RATE * durationsMs[i] / 1000;
            int fadeLen = Math.min(1500, numSamp / 5);
            for (int j = 0; j < numSamp; j++) {
                double env = 1.0;
                if (j < 80) env = j / 80.0;                        // attack
                else if (j > numSamp - fadeLen) env = (double)(numSamp - j) / fadeLen; // release
                double sample = (freq == 0) ? 0.0
                        : env * 0.9 * Math.sin(2 * Math.PI * freq * j / SAMPLE_RATE);
                out[pos++] = (short)(sample * Short.MAX_VALUE);
            }
        }
        return out;
    }

    private void playWalkSound()   { playSamples(samplesWalk,   true); }
    private void playRunSound()    { playSamples(samplesRun,    true); }
    private void playFinishSound() { playSamples(samplesFinish, true); }
    private void playTickSound()   { playSamples(samplesTick,   false); }

    /**
     * Plays pre-built PCM on USAGE_ALARM stream.
     * requestFocus=true → full audio-focus + wake-lock treatment (melodies).
     * requestFocus=false → lightweight path for the per-second tick.
     */
    private void playSamples(short[] samples, boolean requestFocus) {
        if (samples == null) return;

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        PowerManager.WakeLock soundWake = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "IntervalRunner::SoundWake");
        soundWake.acquire(5000L);

        AudioManager.OnAudioFocusChangeListener focusListener = focusChange -> {};
        if (requestFocus) {
            int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0);
            audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }

        new Thread(() -> {
            try {
                android.media.AudioTrack track = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
                        .build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.length * 2)
                    .build();

                track.write(samples, 0, samples.length);
                track.play();

                long durationMs = (long) samples.length * 1000 / SAMPLE_RATE;
                Thread.sleep(durationMs + 50);

                track.stop();
                track.release();
                if (requestFocus) audioManager.abandonAudioFocus(focusListener);
            } catch (Exception ignored) {
            } finally {
                if (soundWake.isHeld()) soundWake.release();
            }
        }).start();
    }

    // =========================================================================
    // GPS
    // =========================================================================

    private void startLocationUpdates() {
        LocationRequest req = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3000)
                .setMinUpdateIntervalMillis(2000).build();
        locationCallback = new LocationCallback() {
            @Override public void onLocationResult(LocationResult r) {
                Location loc = r.getLastLocation();
                if (loc == null || loc.getAccuracy() > 25f) return;
                if (lastLocation != null) {
                    float dist = lastLocation.distanceTo(loc);
                    if (dist < 100f) {
                        if (isWalking) walkMetresTotal += dist;
                        else           runMetresTotal  += dist;
                    }
                }
                lastLocation = loc;
            }
        };
        try {
            fusedLocation.requestLocationUpdates(req, locationCallback, Looper.getMainLooper());
        } catch (SecurityException ignored) {}
    }

    private void stopLocationUpdates() {
        if (locationCallback != null) fusedLocation.removeLocationUpdates(locationCallback);
    }

    private void savePaceData() {
        float wKmh = walkSecondsTotal >= 30
                ? (walkMetresTotal / 1000f) / (walkSecondsTotal / 3600f) : 0f;
        float rKmh = runSecondsTotal  >= 30
                ? (runMetresTotal  / 1000f) / (runSecondsTotal  / 3600f) : 0f;
        paceStore.recordSession(wKmh, rKmh);
    }

    // =========================================================================
    // Broadcasts  (phase events only — no TICK)
    // =========================================================================

    private void broadcastPhaseChange() {
        Intent i = new Intent("com.intervalrunner.TIMER_UPDATE");
        i.putExtra("action", "PHASE_CHANGE");
        i.putExtra("isWalking",   isWalking);
        i.putExtra("secondsLeft", secondsLeft);
        i.putExtra("currentRep",  currentRep);
        sendBroadcast(i);
    }

    private void broadcastPauseState(boolean paused) {
        Intent i = new Intent("com.intervalrunner.TIMER_UPDATE");
        i.putExtra("action", "PAUSE_STATE");
        i.putExtra("paused", paused);
        sendBroadcast(i);
    }

    private void broadcastFinished() {
        Intent i = new Intent("com.intervalrunner.TIMER_UPDATE");
        i.putExtra("action", "FINISHED");
        i.putExtra("walkKmh", paceStore.getWalkKmh());
        i.putExtra("runKmh",  paceStore.getRunKmh());
        sendBroadcast(i);
    }

    // =========================================================================
    // Notification
    // =========================================================================

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "Interval Timer", NotificationManager.IMPORTANCE_LOW);
        ch.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent tap = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String phase = isPaused ? "Paused" : (isWalking ? "Walk" : "Run");
        String text  = phase + "  " + secondsLeft/60 + ":"
                + String.format("%02d", secondsLeft%60)
                + "  —  rep " + currentRep + "/" + repetitions;

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Interval Runner")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setSilent(true)
                // Show on lock screen
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification());
    }

    // =========================================================================
    // Getters polled by UI every second
    // =========================================================================

    public boolean isRunning()          { return timerActive; }
    public boolean isPaused()           { return isPaused; }
    public boolean isCurrentlyWalking() { return isWalking; }
    public int     getSecondsLeft()     { return secondsLeft; }
    public int     getCurrentRep()      { return currentRep; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        stopLocationUpdates();
        if (wakeLock.isHeld()) wakeLock.release();
    }
}
