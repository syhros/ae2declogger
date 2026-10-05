package com.syhros.ae2declogger.logic;

import java.util.Locale;

/**
 * Why a step is not moving. {@link #isProblem()} separates real blockers from normal waiting.
 */
public enum BlockReason {
    NONE(false, 0),
    /** Inputs for this step are still being crafted by an earlier step. Normal. */
    WAITING_INGREDIENTS(false, 0),
    /** An input is neither stored in the CPU nor being crafted. */
    MISSING_INGREDIENT(true, 60),
    /** No pattern provider on the network holds the pattern any more. */
    NO_PROVIDER(true, 90),
    /** Every provider still has items it could not hand to its machine. */
    PROVIDERS_BUSY(true, 70),
    /** The pattern provider holding the pattern has no channel or power. */
    PROVIDER_OFFLINE(true, 89),
    /** The machine is still working on the previous batch of this pattern (another output is still out). Normal. */
    MACHINE_BUSY(false, 0),
    /** Provider is waiting for a redstone signal or for a result (lock crafting mode). */
    PROVIDER_LOCKED(true, 65),
    /** Provider is in blocking mode and its machine still holds the previous inputs. */
    PROVIDER_BLOCKING(true, 55),
    /** Inputs and providers are ready but the push keeps failing (target full or no valid target). */
    PUSH_REJECTED(true, 50),
    /** Machine reports it is waiting (GT "Recipe Waiting"). */
    MACHINE_WAITING(true, 75),
    /** Machine has no power (GT energy buffer empty). */
    MACHINE_NO_POWER(true, 95),
    /** Machine is idle and could not find a recipe for what it was given. */
    MACHINE_NO_RECIPE(true, 80),
    /** Machine is switched off (soft mallet / controller disabled). */
    MACHINE_DISABLED(true, 85),
    /** Multiblock structure is not formed. */
    MACHINE_NOT_FORMED(true, 92),
    /** Multiblock has maintenance problems. */
    MACHINE_MAINTENANCE(true, 78),
    /** Machine output slots are full. */
    MACHINE_OUTPUT_FULL(true, 88),
    /** Machine input inventory is full. */
    MACHINE_INPUT_FULL(true, 72),
    /** Items were sent out but the result never came back to the network. */
    OUTPUT_NOT_RETURNING(true, 40),
    /** The crafting CPU is offline (no power or channel). */
    CPU_OFFLINE(true, 99),
    /** The ME network does not have enough stored AE to push patterns. */
    NETWORK_POWER(true, 98),
    UNKNOWN(true, 10);

    private final boolean problem;
    private final int priority;

    BlockReason(boolean problem, int priority) {
        this.problem = problem;
        this.priority = priority;
    }

    public boolean isProblem() {
        return problem;
    }

    /** Higher wins when several reasons apply to the same step. */
    public int priority() {
        return priority;
    }

    /** True for reasons that come from inspecting the machine itself rather than AE2. */
    public boolean isMachineReason() {
        return name().startsWith("MACHINE_");
    }

    /**
     * Machine faults that do not clear on their own (no power, switched off, not formed, maintenance, output full).
     * Softer machine states such as "waiting" or "no matching recipe" can resolve by themselves.
     */
    public boolean isHardMachineFault() {
        return switch (this) {
            case MACHINE_NO_POWER, MACHINE_DISABLED, MACHINE_NOT_FORMED, MACHINE_MAINTENANCE, MACHINE_OUTPUT_FULL -> true;
            default -> false;
        };
    }

    public String translationKey() {
        return "ae2declogger.reason." + name().toLowerCase(Locale.ROOT);
    }

    public static BlockReason stronger(BlockReason a, BlockReason b) {
        if (a == null || a == NONE) {
            return b == null ? NONE : b;
        }
        if (b == null || b == NONE) {
            return a;
        }
        if (a.problem != b.problem) {
            return a.problem ? a : b;
        }
        return b.priority > a.priority ? b : a;
    }

    public static BlockReason byOrdinal(int ordinal) {
        BlockReason[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : UNKNOWN;
    }
}
