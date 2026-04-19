package com.intervalrunner;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.intervalrunner.databinding.ActivityMainBinding;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private PaceStore paceStore;

    private int walkMinutes = 4;
    private int runMinutes  = 1;
    private int repetitions = 3;

    // Service
    private TimerService timerService;
    private boolean serviceBound = false;

    // UI polls the service directly — no reliance on TICK broadcasts
    private final Handler  uiHandler   = new Handler(Looper.getMainLooper());
    private final Runnable uiPollTick  = new Runnable() {
        @Override public void run() {
            if (serviceBound && timerService != null && timerService.isRunning()) {
                updateTimerUI(
                    timerService.getSecondsLeft(),
                    timerService.getCurrentRep(),
                    timerService.isCurrentlyWalking());
                uiHandler.postDelayed(this, 250); // 4× per second for smooth display
            }
        }
    };

    private static final int LOCATION_PERMISSION_REQUEST     = 100;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 101;

    // -------------------------------------------------------------------------
    // Broadcasts — phase events and finish only (no TICK)
    // -------------------------------------------------------------------------

    private final BroadcastReceiver eventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getStringExtra("action");
            if (action == null) return;
            switch (action) {
                case "PHASE_CHANGE":
                    updatePhaseUI(intent.getBooleanExtra("isWalking", true));
                    break;
                case "PAUSE_STATE":
                    updatePauseButton(intent.getBooleanExtra("paused", false));
                    break;
                case "FINISHED":
                    onWorkoutFinished(
                        intent.getFloatExtra("walkKmh", 0),
                        intent.getFloatExtra("runKmh",  0));
                    break;
            }
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            timerService = ((TimerService.LocalBinder) service).getService();
            serviceBound = true;
            if (timerService.isRunning()) {
                showTimerUI();
                updatePhaseUI(timerService.isCurrentlyWalking());
                updatePauseButton(timerService.isPaused());
                startUiPolling();
            }
        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
        }
    };

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Show on lock screen when workout is running, but don't keep screen on
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }

        paceStore = new PaceStore(this);

        requestNotificationPermission();
        // Location is requested lazily, only when Start is tapped

        setupClickListeners();
        updateSetupUI();
        showSetupUI();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, TimerService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);

        IntentFilter filter = new IntentFilter("com.intervalrunner.TIMER_UPDATE");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(eventReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiHandler.removeCallbacks(uiPollTick);
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false; }
        unregisterReceiver(eventReceiver);
    }

    // -------------------------------------------------------------------------
    // UI polling (fixes frozen countdown on Nothing OS and other OEMs)
    // -------------------------------------------------------------------------

    private void startUiPolling() {
        uiHandler.removeCallbacks(uiPollTick);
        uiHandler.post(uiPollTick);
    }

    private void stopUiPolling() {
        uiHandler.removeCallbacks(uiPollTick);
    }

    // -------------------------------------------------------------------------
    // Click listeners
    // -------------------------------------------------------------------------

    private void setupClickListeners() {
        binding.btnWalkMinus.setOnClickListener(v -> { if (walkMinutes > 1)  { walkMinutes--; updateSetupUI(); } });
        binding.btnWalkPlus .setOnClickListener(v -> { if (walkMinutes < 30) { walkMinutes++; updateSetupUI(); } });
        binding.btnRunMinus .setOnClickListener(v -> { if (runMinutes > 1)   { runMinutes--;  updateSetupUI(); } });
        binding.btnRunPlus  .setOnClickListener(v -> { if (runMinutes < 30)  { runMinutes++;  updateSetupUI(); } });
        binding.btnRepsMinus.setOnClickListener(v -> { if (repetitions > 1)  { repetitions--; updateSetupUI(); } });
        binding.btnRepsPlus .setOnClickListener(v -> { if (repetitions < 20) { repetitions++; updateSetupUI(); } });

        binding.btnStart.setOnClickListener(v -> requestLocationThenStart());
        binding.btnStop .setOnClickListener(v -> confirmStop());
        binding.btnPause.setOnClickListener(v -> togglePause());
        binding.btnDone .setOnClickListener(v -> showSetupUI());
    }

    // -------------------------------------------------------------------------
    // Setup UI
    // -------------------------------------------------------------------------

    private void updateSetupUI() {
        binding.tvWalkValue.setText(String.valueOf(walkMinutes));
        binding.tvRunValue .setText(String.valueOf(runMinutes));
        binding.tvRepsValue.setText(String.valueOf(repetitions));
        binding.tvTotalDuration.setText("= " + (walkMinutes + runMinutes) * repetitions + " min total");
        updateDistanceHint();
    }

    private void updateDistanceHint() {
        binding.tvDistanceHint.setText(
                paceStore.distanceSummary(walkMinutes, runMinutes, repetitions));
    }

    // -------------------------------------------------------------------------
    // Timer UI
    // -------------------------------------------------------------------------

    private void updateTimerUI(int secondsLeft, int currentRep, boolean isWalking) {
        binding.tvTimer.setText(String.format("%d:%02d", secondsLeft / 60, secondsLeft % 60));
        binding.tvRepCounter.setText("rep " + currentRep + " of " + repetitions);
    }

    private void updatePhaseUI(boolean isWalking) {
        binding.tvPhaseLabel.setText(isWalking ? "WALK" : "RUN");
        binding.timerCard.setBackgroundResource(isWalking ? R.drawable.bg_walk : R.drawable.bg_run);
    }

    private void updatePauseButton(boolean paused) {
        binding.btnPause.setText(paused ? "Resume" : "Pause");
    }

    // -------------------------------------------------------------------------
    // Screen switching
    // -------------------------------------------------------------------------

    private void showSetupUI() {
        stopUiPolling();
        binding.layoutSetup   .setVisibility(View.VISIBLE);
        binding.layoutTimer   .setVisibility(View.GONE);
        binding.layoutFinished.setVisibility(View.GONE);
        updateDistanceHint();
    }

    private void showTimerUI() {
        binding.layoutSetup   .setVisibility(View.GONE);
        binding.layoutTimer   .setVisibility(View.VISIBLE);
        binding.layoutFinished.setVisibility(View.GONE);
    }

    private void showFinishedUI() {
        stopUiPolling();
        binding.layoutSetup   .setVisibility(View.GONE);
        binding.layoutTimer   .setVisibility(View.GONE);
        binding.layoutFinished.setVisibility(View.VISIBLE);
    }

    // -------------------------------------------------------------------------
    // Workout control
    // -------------------------------------------------------------------------

    private void requestLocationThenStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            // Explain why, then ask
            new AlertDialog.Builder(this)
                .setTitle("Location for pace tracking")
                .setMessage("The app uses GPS during your run to measure your actual walking and running speed. This improves the distance estimate each week.\n\nYou can deny — the timer still works perfectly.")
                .setPositiveButton("Allow GPS", (d, w) ->
                    ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        LOCATION_PERMISSION_REQUEST))
                .setNegativeButton("Skip, just start", (d, w) -> startWorkout())
                .show();
        } else {
            startWorkout();
        }
    }

    private void startWorkout() {
        Intent si = new Intent(this, TimerService.class);
        si.setAction(TimerService.ACTION_START);
        si.putExtra("walkMinutes", walkMinutes);
        si.putExtra("runMinutes",  runMinutes);
        si.putExtra("repetitions", repetitions);
        ContextCompat.startForegroundService(this, si);

        showTimerUI();
        updatePhaseUI(true);
        binding.tvTimer.setText(walkMinutes + ":00");
        binding.tvRepCounter.setText("rep 1 of " + repetitions);
        updatePauseButton(false);
        startUiPolling();
    }

    /** Accidental-touch protection: requires a deliberate confirmation. */
    private void confirmStop() {
        new AlertDialog.Builder(this)
            .setTitle("Stop workout?")
            .setMessage("This will end your session early.")
            .setPositiveButton("Stop", (d, w) -> {
                if (serviceBound && timerService != null) timerService.stopTimer();
                stopService(new Intent(this, TimerService.class));
                showSetupUI();
            })
            .setNegativeButton("Keep going", null)
            .show();
    }

    /** Accidental-touch protection for Pause: requires confirmation. */
    private void togglePause() {
        if (!serviceBound || timerService == null) return;
        if (!timerService.isPaused()) {
            new AlertDialog.Builder(this)
                .setTitle("Pause workout?")
                .setMessage("Timer will stop until you resume.")
                .setPositiveButton("Pause", (d, w) -> sendPauseResume())
                .setNegativeButton("Keep going", null)
                .show();
        } else {
            // Resuming needs no confirmation
            sendPauseResume();
        }
    }

    private void sendPauseResume() {
        Intent si = new Intent(this, TimerService.class);
        si.setAction(timerService.isPaused() ? TimerService.ACTION_RESUME : TimerService.ACTION_PAUSE);
        startService(si);
    }

    private void onWorkoutFinished(float walkKmh, float runKmh) {
        float dist = paceStore.estimateDistanceKm(walkMinutes, runMinutes, repetitions);
        String summary;
        if (walkKmh > 0 && runKmh > 0) {
            summary = String.format("~%.1f km covered\nwalk %.1f km/h  ·  run %.1f km/h",
                    dist, walkKmh, runKmh);
        } else {
            summary = String.format("~%.1f km covered\n(pace tracking improves each session)", dist);
        }
        binding.tvFinishSummary.setText(summary);
        showFinishedUI();
    }

    // -------------------------------------------------------------------------
    // Permissions
    // -------------------------------------------------------------------------

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.POST_NOTIFICATIONS},
                NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == LOCATION_PERMISSION_REQUEST) {
            // Whether granted or not, start the workout — GPS is best-effort
            startWorkout();
        }
    }
}
