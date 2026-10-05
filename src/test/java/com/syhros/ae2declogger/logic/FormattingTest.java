package com.syhros.ae2declogger.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class FormattingTest {

    @Test
    void durations() {
        assertEquals("-", Formatting.duration(-1));
        assertEquals("0s", Formatting.duration(0));
        assertEquals("59s", Formatting.duration(59_999));
        assertEquals("1m 15s", Formatting.duration(75_000));
        assertEquals("1h 02m", Formatting.duration(3_725_000));
        assertEquals("100h 00m", Formatting.duration(360_000_000));
    }

    @Test
    void compactAmounts() {
        assertEquals("0", Formatting.compactAmount(0));
        assertEquals("999", Formatting.compactAmount(999));
        assertEquals("1k", Formatting.compactAmount(1_000));
        assertEquals("1.5k", Formatting.compactAmount(1_500));
        assertEquals("1.9k", Formatting.compactAmount(1_999));
        assertEquals("150k", Formatting.compactAmount(150_000));
        assertEquals("2M", Formatting.compactAmount(2_000_000));
        assertEquals("9.2E", Formatting.compactAmount(Long.MAX_VALUE));
        assertEquals("-1.5k", Formatting.compactAmount(-1_500));
    }

    @Test
    void percentClamps() {
        assertEquals("0%", Formatting.percent(-1));
        assertEquals("50%", Formatting.percent(0.5));
        assertEquals("100%", Formatting.percent(3));
        assertEquals("0%", Formatting.percent(Double.NaN));
    }

    @Test
    void csvEscaping() {
        assertEquals("plain", Formatting.csvField("plain"));
        assertEquals("\"a,b\"", Formatting.csvField("a,b"));
        assertEquals("\"say \"\"hi\"\"\"", Formatting.csvField("say \"hi\""));
        assertEquals("\"two\nlines\"", Formatting.csvField("two\nlines"));
        assertEquals("", Formatting.csvField(null));
        assertEquals("a,\"b,c\",", Formatting.csvLine(List.of("a", "b,c", "")));
    }

    @Test
    void arrowsFollowMinecraftYaw() {
        // Yaw 0 faces south (+z). A target due south is straight ahead.
        assertEquals("↑", Formatting.directionArrow(0, 0, 10));
        // Target due north is behind.
        assertEquals("↓", Formatting.directionArrow(0, 0, -10));
        // Facing south, east (+x) is on the left.
        assertEquals("←", Formatting.directionArrow(0, 10, 0));
        assertEquals("→", Formatting.directionArrow(0, -10, 0));
        // Facing east (yaw -90), a target to the east is ahead.
        assertEquals("↑", Formatting.directionArrow(-90, 10, 0));
        // Yaw values outside -180..180 still work.
        assertEquals("↑", Formatting.directionArrow(720, 0, 10));
        assertEquals("●", Formatting.directionArrow(0, 0, 0));
    }

    @Test
    void fileNames() {
        assertEquals("my_server.example.com_25565", Formatting.safeFileName("my server.example.com:25565"));
        assertEquals("unknown", Formatting.safeFileName("  "));
        assertEquals("unknown", Formatting.safeFileName(".."));
        assertEquals("New_World", Formatting.safeFileName("New World"));
        assertEquals(64, Formatting.safeFileName("x".repeat(200)).length());
    }
}
