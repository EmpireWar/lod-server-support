package dev.vox.lss.neoforge;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * N-2 contract suite (neoforge-support-plan.md §5.1) — pure file/source pins,
 * no MC classloading: the module's descriptor, mixin config, AT, and payload
 * registrar carry loader-bound invariants that compile green when violated and
 * fail only at runtime on a live NeoForge install.
 */
class NeoForgeModuleContractTest {

    // ---- descriptor ----

    @Test
    void modsTomlPinsIdentityAndMixins() throws IOException {
        String toml = read("neoforge/src/main/resources/META-INF/neoforge.mods.toml");
        assertTrue(toml.contains("modId=\"lss\""),
                "the wire-compat contract: mod id stays lss on every loader");
        assertTrue(toml.contains("version=\"${mod_version}\""),
                "version must be the template token — processResources expands it"
                        + " (a literal here goes stale silently)");
        assertTrue(toml.contains("config=\"lss.neoforge.mixins.json\""),
                "the mixin config must be declared or the accessors/save hook never apply"
                        + " (disk reads + dirty detection silently dead)");
        assertTrue(toml.contains("modId=\"neoforge\"") && toml.contains("modId=\"minecraft\""),
                "dependency ranges drive the Modrinth/listing environment metadata");
    }

    @Test
    void modsTomlDeclaresTheSqliteLibraryModAsAnOptionalDependency() throws IOException {
        // Issue #304 (2026-09-27): the driver is EXTERNAL on this line's NeoForge jar —
        // the "Minecraft SQLite JDBC" library mod provides org.sqlite as a plain mod
        // module; any copy of ours (flat or jarJar-nested) is a second exporter of the
        // same packages and a ResolutionException. release_check pins the jar side
        // (no org/sqlite anywhere); this pins the declaration.
        String toml = read("neoforge/src/main/resources/META-INF/neoforge.mods.toml");
        int row = toml.indexOf("modId=\"sqlite_jdbc\"");
        assertTrue(row >= 0, "the sqlite_jdbc library mod must be declared as a dependency");
        String block = toml.substring(row, Math.min(toml.length(), toml.indexOf("[[", row) < 0
                ? toml.length() : toml.indexOf("[[", row)));
        assertTrue(block.contains("type=\"optional\""),
                "OPTIONAL — LSS boots store-less without the driver (LodStores' install hint);"
                        + " a required row would refuse to load a server that never wanted"
                        + " the store");
        assertTrue(block.contains("side=\"BOTH\""),
                "BOTH — the integrated server opens the store when publishing to LAN");
        assertTrue(block.contains("versionRange=\"[3.45,)\""),
                "the floor must sit BELOW 3.49.1.0: the library's '+YYYY-MM-DD' build"
                        + " suffix sorts below the bare number in Maven's ComparableVersion,"
                        + " and an optional dependency present-but-out-of-range is a hard"
                        + " FML error");
        assertTrue(block.contains("[dependencies.lss.mc-publish]") && block.contains("ignore=true"),
                "mc-publish must ignore the TOML-derived row — release.yml declares the"
                        + " Modrinth dependency explicitly (REQUIRED, for auto-install)");
        // The jar must never carry a copy again: no jarJarStore row, no runtime row.
        String gradle = read("neoforge/build.gradle");
        assertFalse(gradle.contains("jarJarStore \"org.xerial:sqlite-jdbc"),
                "sqlite-jdbc must not be nested via jarJar (issue #304)");
        assertFalse(gradle.contains("implementation \"org.xerial:sqlite-jdbc")
                        || gradle.contains("additionalRuntimeClasspath \"org.xerial:sqlite-jdbc"),
                "no stock sqlite-jdbc on the NeoForge module's classpath — dev runs load"
                        + " the library MOD so the smoke exercises the real module shape");
        assertTrue(gradle.contains("additionalRuntimeClasspath \"maven.modrinth:minecraft-sqlite-jdbc:"),
                "dev runs must load the Minecraft SQLite JDBC library mod (the gametest"
                        + " store pin runs through it)");
    }

    @Test
    void theEntrypointWarnsWithTheInstallHintWhenTheDriverIsAbsent() throws IOException {
        // The shared store factory (common, identical on every line) can only say
        // "SQLite engine unavailable"; the per-line entrypoint is where the NeoForge
        // remedy lives. Source-level pin (the listener needs a live server to fire):
        // presence-only probe of the driver class, dedicated servers only, registered
        // on ServerAboutToStartEvent so it precedes the store open at STARTED, and the
        // message names the library mod, its Modrinth page and mod id.
        String src = read("neoforge/src/main/java/dev/vox/lss/neoforge/LSSNeoMod.java");
        assertTrue(src.contains("SQLITE_DRIVER_CLASS = \"org.sqlite.JDBC\""),
                "the probe must target the DriverManager entry point sqlite-jdbc registers");
        assertTrue(src.contains("Class.forName(SQLITE_DRIVER_CLASS, false, loader)"),
                "presence only — initialize=false, a native-load failure is the factory's degrade");
        assertTrue(src.contains("addListener(ServerAboutToStartEvent.class,")
                        && src.contains("LSSNeoMod::warnIfSqliteDriverAbsent"),
                "the hint must be wired BEFORE ServerStartedEvent opens the store");
        assertTrue(src.contains("isDedicatedServer()) return;"),
                "integrated servers skip the hint (singleplayer noise for a LAN-only store)");
        for (String needle : new String[]{"Minecraft SQLite JDBC",
                "https://modrinth.com/mod/minecraft-sqlite-jdbc", "sqlite_jdbc"}) {
            assertTrue(src.contains(needle), "the hint must name " + needle);
        }
        assertTrue(LSSNeoMod.sqliteDriverPresent(new ClassLoader(null) { }) == false,
                "a bootstrap-only loader sees no driver — the absent shape");
    }

    // ---- registrar census (the §1.2 loader-bound invariants) ----

    @Test
    void registrarIsOptionalRunsOnMainAndNeverUsesTheLssProtocolAsChannelVersion() throws IOException {
        String src = read("neoforge/src/main/java/dev/vox/lss/networking/LSSNetworking.java");
        String normalized = src.replaceAll("\\s+", " ");
        assertTrue(normalized.contains("event.registrar(\"1\") .optional() .executesOn(HandlerThread.MAIN)"),
                "the registrar chain must be exactly optional()+executesOn(MAIN) on the"
                        + " constant version \"1\": a MANDATORY payload refuses vanilla/Fabric"
                        + " clients at login (the community fork's mistake), a non-MAIN thread"
                        + " breaks the receiver thread contract, and the LSS protocol constant"
                        + " as channel version would make NeoForge negotiation fight our own"
                        + " handshake-driven compat rungs");
        assertFalse(src.contains("PROTOCOL_VERSION"),
                "never derive the registrar version from the LSS protocol constant");
    }

    @Test
    void registrarCensusCoversAllChannels() throws IOException {
        String src = read("neoforge/src/main/java/dev/vox/lss/networking/LSSNetworking.java");
        List<String> c2s = List.of("HandshakeC2SPayload", "BatchChunkRequestC2SPayload",
                "ClientInfoC2SPayload", "FarPlayerPrefsC2SPayload",
                "RegionSummaryRequestC2SPayload");
        List<String> s2c = List.of("SessionConfigS2CPayload", "BatchResponseS2CPayload",
                "DirtyColumnsS2CPayload", "VoxelColumnS2CPayload",
                "FarPlayerRosterS2CPayload", "FarPlayerUpdatesS2CPayload",
                "RegionSummaryS2CPayload", "ColumnStampsS2CPayload");
        for (String p : c2s) {
            assertTrue(src.contains("playToServer(" + p + ".TYPE, " + p + ".CODEC"),
                    p + " must register playToServer with its shared TYPE+CODEC");
        }
        for (String p : s2c) {
            assertTrue(src.contains("playToClient(" + p + ".TYPE, " + p + ".CODEC"),
                    p + " must register playToClient with its shared TYPE+CODEC");
        }
        assertEquals(5, count(src, Pattern.compile("playToServer\\(")),
                "exactly the 5 C2S channels — a new channel must be added to BOTH loaders"
                        + " and both WireParityTest censuses");
        assertEquals(8, count(src, Pattern.compile("playToClient\\(")),
                "exactly the 8 S2C channels");
        // CROSS-LOADER census (N-2 review): the registrar's total must track the shared
        // CHANNEL_* constant count — a fabric-side channel add reds THIS module's build
        // instead of shipping a NeoForge jar that silently lacks the new channel.
        long sharedChannels = Arrays.stream(dev.vox.lss.common.LSSConstants.class.getFields())
                .filter(f -> f.getName().startsWith("CHANNEL_"))
                .count();
        assertEquals(sharedChannels, 5 + 8,
                "LSSConstants declares " + sharedChannels + " CHANNEL_* constants but this"
                        + " registrar registers 13 — add the new channel to LSSNetworking"
                        + " (both loaders) and both WireParityTest censuses");
    }

    // ---- mixin config + AT ----

    @Test
    void mixinConfigListsTheSharedAccessorsAndTheSaveHookTwin() throws IOException {
        String config = read("neoforge/src/main/resources/lss.neoforge.mixins.json");
        for (String required : new String[]{
                "AccessorServerChunkCache", "AccessorSimpleRegionStorage", "AccessorIOWorker",
                "AccessorRegionFileStorage", "AccessorRegionFile", "AccessorConnection",
                "AccessorServerCommonPacketListener", "ChunkSaveDataHook",
                "AccessorClientPacketListener", "IntegratedServerLanHook"}) {
            assertTrue(config.contains("\"" + required + "\""),
                    required + " missing from lss.neoforge.mixins.json — an unlisted accessor"
                            + " compiles but never applies, so every use ClassCastExceptions"
                            + " at runtime (disk reads / channel pressure / dirty detection)");
        }
        // The LAN hook (issue #257) is a byte-identical twin of the fabric mixin
        // (loaderSurfaceFreeTwinsAreByteIdentical pins it), so its @Inject descriptor is
        // exactly the one LanHookContractTest resolves against the real publishServer
        // overload. Deliberately ABSENT: the trace mixins (tracer deferred).
        assertFalse(config.contains("MovementRejectHook"),
                "the trace mixins are fabric-only here; listing them would hard-fail mixin apply");
    }

    @Test
    void everyListedMixinClassExistsInXplatOrThisModule() throws IOException {
        String config = read("neoforge/src/main/resources/lss.neoforge.mixins.json");
        Matcher m = Pattern.compile("\"([A-Za-z$]+)\"").matcher(config);
        while (m.find()) {
            String name = m.group(1);
            if (!name.matches("[A-Z].*")) {
                continue; // JSON keys (required, package, ...), not class names
            }
            String rel = "dev/vox/lss/mixin/" + name + ".java";
            boolean exists = exists("xplat/src/main/java/" + rel)
                    || exists("neoforge/src/main/java/" + rel);
            assertTrue(exists, "mixin config lists " + name + " but no source exists in"
                    + " xplat or the neoforge module");
        }
    }

    @Test
    void accessTransformerMirrorsTheFabricAccessWidener() throws IOException {
        String at = read("neoforge/src/main/resources/META-INF/accesstransformer.cfg");
        assertTrue(at.contains("public net.minecraft.world.level.chunk.PalettedContainer$Data"),
                "the PalettedContainer$Data class widening (headless serialization)");
        assertTrue(at.contains("public net.minecraft.world.level.chunk.PalettedContainer data"),
                "the PalettedContainer.data field widening");
        String aw = read("fabric/src/main/resources/lss.accesswidener");
        // Count EVERY effective widener line (round-3 review: an 'accessible'-only
        // filter let a future mutable/extendable line grow the widener without
        // tripping the mirror — fabric works, the NeoForge twin IllegalAccessErrors).
        var effective = aw.lines().map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")
                        && !l.startsWith("accessWidener"))
                .toList();
        assertEquals(2, effective.size(), "the fabric accesswidener grew — mirror the"
                + " new lines into accesstransformer.cfg (this pin is the drift tripwire)");
        assertTrue(effective.stream().allMatch(l -> l.startsWith("accessible")),
                "a non-'accessible' widener line needs a new AT mapping form "
                        + "(mutable → public-f) — extend the mirror and this pin together");
    }

    // ---- the save-hook twin (the fabric SaveHookContractTest's sibling) ----

    @Test
    void saveHookTwinTargetsChunkSerializerWriteWithSoftFail() throws IOException {
        String src = read("neoforge/src/main/java/dev/vox/lss/mixin/ChunkSaveDataHook.java");
        assertTrue(src.contains("@Mixin(ChunkSerializer.class)"),
                "the dirty hook must target ChunkSerializer (1.21.1 line: no"
                        + " SerializableChunkData here — write() is this line's issue-#69"
                        + " choke point, the copyOf ancestor)");
        assertTrue(src.contains("method = \"write\"") && src.contains("require = 0"),
                "write @ RETURN with require = 0 — a missing target degrades dirty"
                        + " detection, never crashes the server");
        assertTrue(src.contains("LSSServerNetworking.onChunkSaveData(level, chunk)"),
                "the body must delegate to this loader's holder -> the shared glue");
    }

    // ---- services registration ----

    @Test
    void loaderServicesServiceFilePointsAtTheNeoForgeImpl() throws IOException {
        String services = read(
                "neoforge/src/main/resources/META-INF/services/dev.vox.lss.platform.LoaderServices");
        assertEquals("dev.vox.lss.platform.NeoForgeLoaderServices", services.trim(),
                "ServiceLoader fallback registration (the fabric module's twin file)");
        assertTrue(exists("neoforge/src/main/java/dev/vox/lss/platform/NeoForgeLoaderServices.java"),
                "the registered impl must exist");
    }

    // ---- helpers ----

    private static int count(String src, Pattern p) {
        Matcher m = p.matcher(src);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    static String read(String repoRelative) throws IOException {
        return Files.readString(resolve(repoRelative));
    }

    static boolean exists(String repoRelative) {
        try {
            resolve(repoRelative);
            return true;
        } catch (AssertionError e) {
            return false;
        }
    }

    /**
     * Same-FQN twins with NO loader surface — byte-identical by contract (a fix landing
     * in one tree only is silent drift the compile cannot see; both compile fine alone):
     * ScopedCarrier (V-2/S5 — a version-volatile whole-file swap at port time, but on ONE
     * line the same file), the LAN hook (issue #257 — publishServer @Inject, per-line
     * descriptor but identical across the two loaders of one line), and the legacy-Sodium
     * options stack
     * (sodium-options-page-generations-plan.md D4/D5 — the probe, the reflective
     * builder, the constructor hook, and the mixin config, whose compatibilityLevel is
     * per-LINE data but identical across the two loaders of one line).
     */
    private static final String[] BYTE_IDENTICAL_TWINS = {
            "src/main/java/dev/vox/lss/mixin/IntegratedServerLanHook.java",
            "src/main/java/dev/vox/lss/compat/ScopedCarrier.java",
            "src/main/java/dev/vox/lss/config/menu/SodiumGeneration.java",
            "src/main/java/dev/vox/lss/config/menu/LegacySodiumPage.java",
            "src/main/java/dev/vox/lss/mixin/sodium/SodiumLegacyOptionsHook.java",
            "src/main/resources/lss-sodium-legacy.mixins.json",
    };

    @Test
    void loaderSurfaceFreeTwinsAreByteIdentical() throws IOException {
        for (String rel : BYTE_IDENTICAL_TWINS) {
            byte[] fab = Files.readAllBytes(resolve("fabric/" + rel));
            byte[] neo = Files.readAllBytes(resolve("neoforge/" + rel));
            assertTrue(java.util.Arrays.equals(fab, neo),
                    rel + " twins drifted — apply the change to BOTH trees");
        }
    }

    @Test
    void theLegacySodiumOptionsConfigIsDeclaredAndItsHookExists() throws IOException {
        String toml = read("neoforge/src/main/resources/META-INF/neoforge.mods.toml");
        assertTrue(toml.contains("config=\"lss-sodium-legacy.mixins.json\""),
                "the toml must carry a second [[mixins]] row for the legacy Sodium options hook");
        String config = read("neoforge/src/main/resources/lss-sodium-legacy.mixins.json");
        assertTrue(config.contains("\"SodiumLegacyOptionsHook\""));
        assertTrue(exists("neoforge/src/main/java/dev/vox/lss/mixin/sodium/SodiumLegacyOptionsHook.java"),
                "the config lists a hook whose neoforge twin source must exist");
        // (no gson on this module's test classpath — whitespace-tolerant regexes)
        assertTrue(Pattern.compile("\"required\"\\s*:\\s*false").matcher(config).find(),
                "non-required — an apply failure degrades to no options page");
        assertTrue(Pattern.compile("\"defaultRequire\"\\s*:\\s*0\\b").matcher(config).find(),
                "defaultRequire 0 — a require miss is an Error that escapes required:false");
    }

    /** Repo-relative resolution surviving both the Gradle CWD (module dir) and repo root. */
    static Path resolve(String repoRelative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 5 && dir != null; depth++, dir = dir.getParent()) {
            Path candidate = dir.resolve(repoRelative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new AssertionError("cannot locate " + repoRelative + " from "
                + Path.of("").toAbsolutePath());
    }
}
