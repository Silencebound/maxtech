package dev.silencebound.maxtech.storage.research.source;

/**
 * One machine observer's count: runs since its last point, the day it last counted and the points it gave that day. A
 * new day (any other day number, so a clock set back counts too) starts the daily cap again. Pure logic, the owning
 * block entity's thread.
 */
final class ObserverTally {
    private int runs;
    private long day;
    private int pointsToday;

    ObserverTally() {
    }

    ObserverTally(int runs, long day, int pointsToday) {
        this.runs = runs;
        this.day = day;
        this.pointsToday = pointsToday;
    }

    /**
     * Counts one finished run on {@code day}; returns 1 when it completes {@code runsPerPoint} runs and the day's
     * {@code dailyCap} is not reached yet, otherwise 0. Runs past the cap still count towards the next point, which
     * then gives nothing until the day changes.
     */
    int record(long day, int runsPerPoint, int dailyCap) {
        if (day != this.day) {
            this.day = day;
            pointsToday = 0;
        }
        runs++;
        if (runs < Math.max(1, runsPerPoint)) {
            return 0;
        }
        runs = 0;
        if (pointsToday >= dailyCap) {
            return 0;
        }
        pointsToday++;
        return 1;
    }

    int runs() {
        return runs;
    }

    long day() {
        return day;
    }

    int pointsToday() {
        return pointsToday;
    }
}
