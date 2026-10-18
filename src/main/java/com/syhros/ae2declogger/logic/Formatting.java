package com.syhros.ae2declogger.logic;

import java.util.List;
import java.util.Locale;

/**
 * Number, duration and CSV helpers shared by the UI and the log export.
 */
public final class Formatting {

    private Formatting() {
    }

    /** 0 -> "0s", 75_000 -> "1m 15s", 3_725_000 -> "1h 02m". Negative means unknown and gives "-". */
    public static String duration(long ms) {
        if (ms < 0) {
            return "-";
        }
        long totalSeconds = ms / 1000;
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (hours > 0) {
            return String.format(Locale.ROOT, "%dh %02dm", hours, minutes);
        }
        if (minutes > 0) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, seconds);
        }
        return seconds + "s";
    }

    /** 999 -> "999", 1_500 -> "1.5k", 2_000_000 -> "2M". */
    public static String compactAmount(long amount) {
        if (amount < 0) {
            return "-" + compactAmount(-amount);
        }
        if (amount < 1000) {
            return Long.toString(amount);
        }
        String[] units = { "k", "M", "G", "T", "P", "E" };
        double value = amount;
        int unit = -1;
        while (value >= 1000 && unit < units.length - 1) {
            value /= 1000;
            unit++;
        }
        String number = value >= 100 ? String.format(Locale.ROOT, "%.0f", Math.floor(value))
                : String.format(Locale.ROOT, "%.1f", Math.floor(value * 10) / 10);
        if (number.endsWith(".0")) {
            number = number.substring(0, number.length() - 2);
        }
        return number + units[unit];
    }

    /** Percentage with no decimals, clamped to 0..100. */
    public static String percent(double fraction) {
        if (Double.isNaN(fraction)) {
            return "0%";
        }
        double clamped = Math.max(0, Math.min(1, fraction));
        return Math.round(clamped * 100) + "%";
    }

    /** Quotes a value for CSV when it contains a separator, quote or line break. */
    public static String csvField(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        String escaped = value.replace("\"", "\"\"");
        return needsQuotes ? "\"" + escaped + "\"" : escaped;
    }

    public static String csvLine(List<String> fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csvField(fields.get(i)));
        }
        return sb.toString();
    }

    /**
     * Arrow pointing from the player towards a target, relative to where the player looks.
     *
     * @param playerYawDeg Minecraft yaw (0 = south, 90 = west)
     * @param dx           target x minus player x
     * @param dz           target z minus player z
     */
    public static String directionArrow(float playerYawDeg, double dx, double dz) {
        if (Math.abs(dx) < 1e-6 && Math.abs(dz) < 1e-6) {
            return "●";
        }
        // Minecraft yaw: facing vector is (-sin(yaw), cos(yaw)).
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = wrapDegrees(targetYaw - playerYawDeg);
        String[] arrows = { "↑", "↗", "→", "↘", "↓", "↙", "←", "↖" };
        int index = (int) Math.floorMod(Math.round(relative / 45.0), 8L);
        return arrows[index];
    }

    static double wrapDegrees(double degrees) {
        double d = degrees % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        }
        if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }

    /** Makes a string safe to use as a file name on Windows, macOS and Linux. */
    public static String safeFileName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "unknown";
        }
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9._-]+", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        if (cleaned.length() > 64) {
            cleaned = cleaned.substring(0, 64);
        }
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "unknown";
        }
        return cleaned;
    }
}
