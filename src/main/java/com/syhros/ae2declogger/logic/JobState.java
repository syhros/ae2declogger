package com.syhros.ae2declogger.logic;

/**
 * Health of a crafting job or of one step inside it. Ordered by severity so the worst step decides the job.
 */
public enum JobState {
    IN_PROGRESS(0, 0x55FF55),
    SLOW(1, 0xFFDD33),
    STALLED(2, 0xFF5555),
    BLOCKED(3, 0xFF3333),
    COMPLETE(-1, 0x55FFFF),
    CANCELLED(-1, 0xAAAAAA);

    private final int severity;
    private final int color;

    JobState(int severity, int color) {
        this.severity = severity;
        this.color = color;
    }

    public int severity() {
        return severity;
    }

    /** RGB colour used for the status label and row marker. */
    public int color() {
        return color;
    }

    public boolean isProblem() {
        return this == STALLED || this == BLOCKED;
    }

    public boolean isFinished() {
        return this == COMPLETE || this == CANCELLED;
    }

    public String translationKey() {
        return "ae2declogger.state." + name().toLowerCase(java.util.Locale.ROOT);
    }

    public static JobState worst(JobState a, JobState b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.severity > a.severity ? b : a;
    }

    public static JobState byOrdinal(int ordinal) {
        JobState[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : IN_PROGRESS;
    }
}
