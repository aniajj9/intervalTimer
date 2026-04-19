package com.intervalrunner;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists learned walk pace and run pace SEPARATELY.
 * Walk pace is measured only during walk phases.
 * Run pace is measured only during run phases.
 * They never influence each other.
 */
public class PaceStore {

    private static final String PREFS        = "pace_prefs";
    private static final String KEY_WALK_KMH = "walk_kmh";
    private static final String KEY_RUN_KMH  = "run_kmh";
    private static final String KEY_SESSIONS = "sessions";

    private static final float DEFAULT_WALK_KMH = 5.0f;
    private static final float DEFAULT_RUN_KMH  = 8.0f;

    // EMA: new session counts 35%, history 65%
    private static final float ALPHA = 0.35f;

    // Sanity bounds to discard GPS noise
    private static final float MIN_WALK = 1.5f;
    private static final float MAX_WALK = 10.0f;
    private static final float MIN_RUN  = 4.0f;
    private static final float MAX_RUN  = 22.0f;

    private final SharedPreferences prefs;

    public PaceStore(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public float getWalkKmh() { return prefs.getFloat(KEY_WALK_KMH, DEFAULT_WALK_KMH); }
    public float getRunKmh()  { return prefs.getFloat(KEY_RUN_KMH,  DEFAULT_RUN_KMH);  }
    public int   getSessionCount() { return prefs.getInt(KEY_SESSIONS, 0); }
    public boolean hasRealData()   { return getSessionCount() > 0; }

    /**
     * Call at end of session with GPS-measured averages for each phase separately.
     * Pass 0 if a phase couldn't be measured.
     */
    public void recordSession(float measuredWalkKmh, float measuredRunKmh) {
        SharedPreferences.Editor ed = prefs.edit();

        if (measuredWalkKmh >= MIN_WALK && measuredWalkKmh <= MAX_WALK) {
            float updated = ALPHA * measuredWalkKmh + (1f - ALPHA) * getWalkKmh();
            ed.putFloat(KEY_WALK_KMH, updated);
        }
        if (measuredRunKmh >= MIN_RUN && measuredRunKmh <= MAX_RUN) {
            float updated = ALPHA * measuredRunKmh + (1f - ALPHA) * getRunKmh();
            ed.putFloat(KEY_RUN_KMH, updated);
        }

        ed.putInt(KEY_SESSIONS, getSessionCount() + 1);
        ed.apply();
    }

    /**
     * Estimate total distance for a session config.
     * Walk distance and run distance calculated independently with their own paces.
     */
    public float estimateDistanceKm(int walkMinutes, int runMinutes, int reps) {
        float walkKm = (getWalkKmh() * walkMinutes * reps) / 60f;
        float runKm  = (getRunKmh()  * runMinutes  * reps) / 60f;
        return walkKm + runKm;
    }

    /** Human-readable label for the setup screen distance hint. */
    public String distanceSummary(int walkMinutes, int runMinutes, int reps) {
        float dist = estimateDistanceKm(walkMinutes, runMinutes, reps);
        if (hasRealData()) {
            return String.format("~%.1f km  (walk %.1f · run %.1f km/h, %d sessions)",
                    dist, getWalkKmh(), getRunKmh(), getSessionCount());
        } else {
            return String.format("~%.1f km  (estimated — updates after 1st session)", dist);
        }
    }
}
