package com.syhros.ae2declogger.client;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.syhros.ae2declogger.Mc;
import com.syhros.ae2declogger.Text;
import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.logic.Formatting;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** Writes the current jobs and the history to a CSV file in {@code <game dir>/ae2declogger/exports}. */
public final class Exporter {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter CELL_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private Exporter() {
    }

    @Nullable
    public static Path export(List<JobView> jobs, List<HistoryEntry> history) {
        Minecraft mc = Minecraft.getInstance();
        Path dir = mc.gameDirectory.toPath().resolve("ae2declogger").resolve("exports");
        Path file = dir.resolve("declogger-" + LocalDateTime.now().format(FILE_TIME) + ".csv");
        long now = ClientState.serverNow();
        try {
            Files.createDirectories(dir);
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(Formatting.csvLine(List.of("type", "item", "item_id", "amount", "state", "progress",
                        "duration_seconds", "idle_seconds", "reason", "detail", "cpu", "machine", "x", "y", "z",
                        "finished_at")));
                w.write('\n');
                for (JobView job : jobs) {
                    MachineRef m = job.primaryMachine();
                    w.write(Formatting.csvLine(List.of("running", job.output().getDisplayName().getString(),
                            job.output().getId().toString(), Long.toString(job.amount()), job.state().name(),
                            Formatting.percent(job.progress()), seconds(now - job.startedAtMs()),
                            seconds(now - job.lastActivityAtMs()), job.reason().name(),
                            job.detail() == null ? "" : job.detail().getString(), job.cpuName().getString(),
                            m == null ? "" : m.name().getString(), m == null ? "" : Integer.toString(m.pos().getX()),
                            m == null ? "" : Integer.toString(m.pos().getY()),
                            m == null ? "" : Integer.toString(m.pos().getZ()), "")));
                    w.write('\n');
                }
                for (HistoryEntry h : history) {
                    // A step's detail column names the job it was part of.
                    w.write(Formatting.csvLine(List.of(h.isStep() ? "step" : "history",
                            h.output().getDisplayName().getString(),
                            h.output().getId().toString(), Long.toString(h.amount()),
                            h.completed() ? "COMPLETE" : "CANCELLED", "", seconds(h.durationMs()), "", "",
                            h.isStep() ? "part of " + h.partOf().getDisplayName().getString() : "",
                            h.cpuName().getString(), "", "", "", "",
                            LocalDateTime.ofInstant(Instant.ofEpochMilli(h.finishedAtMs()), ZoneId.systemDefault())
                                    .format(CELL_TIME))));
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            Ae2Declogger.LOG.warn("Export failed", e);
            if (mc.player != null) {
                Mc.message(mc.player, Text.translatable("ae2declogger.export.failed", e.getMessage())
                        .withStyle(ChatFormatting.RED), false);
            }
            return null;
        }
        if (mc.player != null) {
            Mc.message(mc.player, Text.translatable("ae2declogger.export.done",
                    Text.literal(file.getFileName().toString()).withStyle(Style.EMPTY
                            .withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(Text.openFileOnClick(file.toString())))),
                    false);
        }
        return file;
    }

    private static String seconds(long ms) {
        return Long.toString(Math.max(0, ms) / 1000);
    }
}
