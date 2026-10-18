package com.syhros.ae2declogger.client;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import com.syhros.ae2declogger.Ae2Declogger;
import com.syhros.ae2declogger.data.HistoryEntry;
import com.syhros.ae2declogger.data.JobView;
import com.syhros.ae2declogger.data.MachineRef;
import com.syhros.ae2declogger.logic.Formatting;

/** Writes the current jobs and the history to a CSV file in {@code <game dir>/ae2declogger/exports}. */
public final class Exporter {

    private Exporter() {
    }

    public static Path export(List<JobView> jobs, List<HistoryEntry> history) {
        File dir = new File(ClientPlat.gameDir(), "ae2declogger/exports");
        Path file = new File(dir, "declogger-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".csv")
                .toPath();
        SimpleDateFormat cellTime = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
        long now = ClientState.serverNow();
        try {
            Files.createDirectories(dir.toPath());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(Formatting.csvLine(Arrays.asList("type", "item", "amount", "state", "progress",
                        "duration_seconds", "idle_seconds", "reason", "detail", "cpu", "machine", "x", "y", "z",
                        "finished_at")));
                w.write('\n');
                for (JobView job : jobs) {
                    MachineRef m = job.primaryMachine;
                    w.write(Formatting.csvLine(Arrays.asList("running", job.output.displayName(),
                            Long.toString(job.amount), job.state.name(), Formatting.percent(job.progress),
                            seconds(now - job.startedAtMs), seconds(now - job.lastActivityAtMs), job.reason.name(),
                            Lang.of(job.detail), Lang.of(job.cpuName), m == null ? "" : Lang.of(m.name),
                            m == null ? "" : Integer.toString(m.pos.x), m == null ? "" : Integer.toString(m.pos.y),
                            m == null ? "" : Integer.toString(m.pos.z), "")));
                    w.write('\n');
                }
                for (HistoryEntry h : history) {
                    w.write(Formatting.csvLine(Arrays.asList(h.isStep() ? "step" : "history", h.output.displayName(),
                            Long.toString(h.amount), h.completed ? "COMPLETE" : "CANCELLED", "",
                            seconds(h.durationMs), "", "", h.isStep() ? "part of " + h.partOf.displayName() : "",
                            Lang.of(h.cpuName), "", "", "", "", cellTime.format(new Date(h.finishedAtMs)))));
                    w.write('\n');
                }
            }
        } catch (IOException e) {
            Ae2Declogger.LOG.warn("Export failed", e);
            ClientPlat.chat(UiUtil.code(UiUtil.RED) + Lang.t("ae2declogger.export.failed", e.getMessage()));
            return null;
        }
        ClientPlat.chat(Lang.t("ae2declogger.export.done", file.getFileName().toString()));
        return file;
    }

    private static String seconds(long ms) {
        return Long.toString(Math.max(0, ms) / 1000);
    }
}
