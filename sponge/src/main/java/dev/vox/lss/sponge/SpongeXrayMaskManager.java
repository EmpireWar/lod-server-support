package dev.vox.lss.sponge;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.XrayMaskPolicy;
import dev.vox.lss.common.XrayMaskPolicy.FallbackKind;
import dev.vox.lss.common.config.ServerConfigBase;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-world x-ray mask decisions — twin of the Fabric {@code XrayMaskManager}
 * (docs/planning/antixray-compat-design.md §3 Detection), ported from the Paper twin.
 * Sponge ships no anti-xray engine, so the engine view is always absent and only the LSS
 * config keys can activate masking.
 *
 * <p>Logging bounds mirror the Fabric twin: one info line per ACTIVE world, silence on the
 * common inactive path, fallback list resolved (and its warnings emitted) at most once.
 * Folia-safe: evaluation is a read of the immutable world config, cached in a concurrent
 * map, callable from any serializing thread.
 */
final class SpongeXrayMaskManager {

    /** An active world's resolved mask inputs; absence (null) = serve unmasked. */
    record MaskEntry(SpongeXrayMaskFilter.MaskSet mask, FallbackKind kind, String sourceLabel) {}

    private static volatile SpongeXrayMaskManager active;

    private final XrayMaskPolicy.Mode mode;
    private final ServerConfigBase config;
    private final ConcurrentHashMap<String, Optional<MaskEntry>> byDimension = new ConcurrentHashMap<>();
    private final AtomicLong maskedSections = new AtomicLong();
    private volatile SpongeXrayMaskFilter.MaskSet fallbackMask;

    SpongeXrayMaskManager(ServerConfigBase config) {
        this.mode = XrayMaskPolicy.Mode.parse(config.xrayObfuscation());
        this.config = config;
    }

    /** Publishes a fresh manager for the starting service (config is read once here). */
    static SpongeXrayMaskManager activate(ServerConfigBase config) {
        var manager = new SpongeXrayMaskManager(config);
        active = manager;
        return manager;
    }

    /** Guarded retract: clears the holder only when {@code owner} is still the published
     *  manager, so a stale shutdown (or a test-wired service that never published) cannot
     *  null out a successor service's masking. */
    static void deactivate(SpongeXrayMaskManager owner) {
        if (owner != null && active == owner) {
            active = null;
        }
    }

    /** The serializer hook: the active world entry, or null when nothing masks. */
    static MaskEntry entryForActive(ServerLevel level) {
        var manager = active;
        return manager == null ? null : manager.entryFor(level);
    }

    static SpongeXrayMaskManager current() {
        return active;
    }

    MaskEntry entryFor(ServerLevel level) {
        return entryFor(level.dimension().identifier().toString());
    }

    /** The cache + decision core, level-free for Tier 1. Every outcome is terminal for the
     *  service lifetime: the config is the only input. */
    MaskEntry entryFor(String dimension) {
        return this.byDimension
                .computeIfAbsent(dimension, d -> Optional.ofNullable(evaluate(d)))
                .orElse(null);
    }

    /** Counted by the serializer hooks; surfaced by the {@code /lsslod diag} xray line. */
    void countMaskedSection() {
        this.maskedSections.incrementAndGet();
    }

    /** Twin of the Fabric diagLine. */
    String diagLine() {
        String label = "off";
        for (var entry : this.byDimension.values()) {
            if (entry.isEmpty()) continue;
            String source = entry.get().sourceLabel();
            if (!source.equals("config")) {
                label = source;
                break;
            }
            label = "config";
        }
        return "Xray: active=" + label + ", masked_sections=" + this.maskedSections.get();
    }

    private MaskEntry evaluate(String dimension) {
        if (this.mode == XrayMaskPolicy.Mode.OFF) return null;
        // No anti-xray engine on Sponge: only the LSS config keys can activate masking.
        var decision = XrayMaskPolicy.decide(this.mode, false, false);
        if (!decision.active()) return null;

        var mask = fallbackMask();
        LSSLogger.info("LOD x-ray masking active for " + dimension + " (source=config"
                + ", maxY=" + mask.maxBlockHeight() + ")");
        return new MaskEntry(mask, FallbackKind.fromDimension(dimension), "config");
    }

    private SpongeXrayMaskFilter.MaskSet fallbackMask() {
        var resolved = this.fallbackMask;
        if (resolved == null) {
            synchronized (this) {
                resolved = this.fallbackMask;
                if (resolved == null) {
                    resolved = SpongeXrayMaskFilter.MaskSet.resolve(
                            this.config.xrayHiddenBlocks(), this.config.xrayMaxBlockHeight());
                    this.fallbackMask = resolved;
                }
            }
        }
        return resolved;
    }
}
