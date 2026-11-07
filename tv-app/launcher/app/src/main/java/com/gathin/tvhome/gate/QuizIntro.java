package com.gathin.tvhome.gate;

/** One welcome per quiz round; monotonic clock, retained across Activity recreation. */
final class QuizIntro {
    static final long DURATION_MS = 5000;
    private long startedAt = -1;
    private boolean completed;
    private boolean audioClaimed;

    void start(long now) {
        if (startedAt < 0) startedAt = now;
    }

    long remainingMs(long now) {
        if (completed) return 0;
        if (startedAt < 0) return DURATION_MS;
        return Math.max(0, DURATION_MS - Math.max(0, now - startedAt));
    }

    boolean shouldShow(long now) {
        return remainingMs(now) > 0;
    }

    void complete() {
        completed = true;
    }

    /** Reopening this round must not repeat the welcome voice. */
    boolean claimAudio() {
        if (completed || audioClaimed) return false;
        audioClaimed = true;
        return true;
    }
}