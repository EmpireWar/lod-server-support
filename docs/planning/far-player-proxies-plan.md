# Far-player proxies in LSS — plan

**Status: IMPLEMENTED — shipped in v0.11.0** (E1-E3, 2026-08-13; kept as the design record — the mega plan's R-3/R-5/R-7/R-9/R-10 amendments apply on top). Design for rendering distant players
beyond vanilla entity-tracking range as a native LSS feature — the player-entity
complement to LOD terrain. The initial review required a dedicated send lane,
server-declared cadence, roster epochs and explicit privacy/visibility decisions.
This document is a historical design record; current settings and reload semantics
are documented in the generated settings reference.

---

## 1. Architecture considerations

Far-player rendering uses vanilla proxy entities and a separate networking channel.
LSS builds each online snapshot once per tick, keeps identity in an epoch-scoped
roster, and sends compact updates. Renderer state is independent of the terrain
request manager. Implementation is derived from vanilla APIs and observed behavior.

## 2. Scope decision

This is in-product: LSS's pitch (just re-worded in the description change plan) is
"see the world beyond vanilla range" — terrain today, players are the natural
complement, and the live test showed exactly that composition (a distant player
standing on Voxy LOD terrain). It becomes an LSS feature behind a capability bit +
config toggles, in the existing jars — no new mod, no new channels beyond the `lss:*`
namespace, NeoForge stays out of scope (LSS ships Fabric + Paper only).

The genuinely NEW surface for LSS is client-side **rendering** — everything else
(handshake, per-player state, batching, bandwidth, config discipline, Folia patterns,
diag, release) reuses infrastructure LSS already has and the historical renderer lacks.

## 3. Design

### 3.1 Wire (additive, no protocol bump)

- New capability bit `CAPABILITY_FAR_PLAYERS` in the existing handshake bitmask
  (`CAPABILITY_VOXEL_COLUMNS` precedent). The server sends far-player payloads only to
  sessions that declared it; legacy clients never see them — **no compat rung
  needed**, no version bump. Review-verified as direct in-repo precedent: the
  handshake gate MASKS capability bits (`HandshakeGate.java:151`), so an OLDER v20
  server ignores the unknown bit and registers normally, and the
  `CHANNEL_CLIENT_INFO` sidecar carries the explicit doctrine ("legacy servers
  silently discard unregistered channels", `LSSConstants.java:45-51`); Paper S2C
  bypasses the Bukkit messenger via NMS `DiscardedPayload` (no outgoing registration
  needed), a new C2S channel needs only `registerIncomingPluginChannel` + a dispatch
  case. Caveat (review): the 4-field SessionConfig carries no server-capability echo,
  so the client cannot distinguish "older server ignored my bit" from "nobody in
  range" — if UX wants an ack, the v20-only append arm of `encodeSessionConfig` is
  the available slot.
- C2S `FarPlayerPrefsC2SPayload` (sent after session config, re-sendable at runtime):
  enabled, max distance, min distance, shareSelf, share distance — the the historical renderer hello
  fields minus the version (LSS's handshake owns versioning). Server-side prefs and
  roster state follow the **v18-rung lifecycle checklist** (review MAJOR — this
  codebase's own race history: the v0.8.0 deferred-reply fix, the dialect tracker's
  quit-race drain): Paper marks pump-only, dropped at disconnect AND the
  quit-originated mailbox Remove, survives the dimension-change remove+register.
- S2C **two-payload split** (the main wire improvement over the historical renderer):
  - `FarPlayerRosterS2CPayload` — carries a **roster epoch** (review MAJOR) and, per
    player, a compact index ↔ UUID + name binding (+ leave events). A FULL roster is
    sent on subscribe, on (re)handshake, and on the viewer's dimension change; index
    reuse is only valid within an epoch. Names/UUIDs cross the wire once per
    join/leave, not 2× per second.
  - `FarPlayerUpdatesS2CPayload` — the periodic batch, stamped with the roster epoch
    (the client DROPS updates whose epoch it hasn't seen — the misbinding armor) and
    the viewer's dimension (the historical renderer ships dimensionKey per packet; ours rides the
    epoch'd roster + a dimension check, and the client clears on mismatch). Per
    visible player: roster index (varint), **quantized position** (fixed-point 1/16
    block, int32 — covers ±134M blocks vs the ±30M border; sub-block precision is
    invisible at proxy distances), yaw/head-yaw/pitch as bytes (1.4° steps), a pose
    flags byte, **equipment only when its hash changed** (dictionary indices via the
    v20 identity-dictionary pattern, not registry-id strings per update), optional
    vehicle (type via the same dictionary), and a **velocity hint** (3 shorts,
    blocks/s × 256, clamped ±64 m/s — elytra ≈ 40 m/s) for client extrapolation.
  - The updates payload also carries the **server-declared nominal cadence for the
    player's tier** (one varint) — the client's interpolation window derives from
    the DECLARED interval, not a measured one (review MAJOR — see §3.3).
- Both platforms encode via the shared `common/` codec twins with the established
  Fabric/Paper wire-parity test discipline (`WireParityTest` pattern), and the
  protocol-constant envelope pin gains the new channel ids.

### 3.2 Server service (`common/` core + platform adapters)

- One `FarPlayerBroadcastService` in `common/`, ticked from the existing service tick
  (Fabric server thread / Paper GlobalRegionScheduler pump — Folia-safe by
  construction, and `FoliaWiringContractTest` covers the new classes for free).
- **Invert loop**: per broadcast tick, build each ONLINE PLAYER's snapshot
  ONCE (position/rotation/pose/equipment-hash — O(P)), then run the per-viewer
  filter over the shared snapshots (O(V×P) *filter*, but comparisons only — no
  per-pair snapshot construction or string encoding).
- **Distance-tiered cadence**: full-rate updates (default 2 Hz) inside a near band,
  half/quarter rate beyond (e.g. >2048 blocks). A stationary far player costs a
  position-unchanged skip (delta suppression: unchanged players are omitted from the
  update payload entirely — the roster keeps them alive client-side).
- Filters, in proven order plus LSS additions: same dimension → alive/removed →
  spectator (config) → invisible → vanish bridge (reuse the `AntiXrayCompat`-style
  reflective ladder; melius-vanish on Fabric, a Paper vanish-meta check on Paper) →
  ring (client ∩ server) → target privacy (below).
- **Privacy, server-authoritative** (the ESP-oracle fix — biggest gap): the historical renderer
  only honors `shareSelf` for targets *running the mod*; vanilla players are broadcast
  with no say and no knowledge. LSS adds: `farPlayersMaxDistanceBlocks` server cap
  (default **2048**, not 8192 — admins raise it consciously), a server-side
  `farPlayersExclude` list + Paper permission node (`lss.farplayers.hidden`), and a
  `farPlayers` mode config: `off` / `opt-in` / `on` (default `on`, documented in the
  README's privacy note). Target-client `shareSelf` still honored on top.
- **Bandwidth — a DEDICATED far-player send lane, NOT the column send queue**
  (review MAJOR — the first draft's queue reuse was structurally impossible: the
  queue is column-position-keyed with load-bearing `packedPos` semantics — the
  relevance prune, send-failure done-bit clears — and Paper's flush sender is
  hard-bound to the `ID_VOXEL_COLUMN` channel; FIFO behind megabytes of backfill
  would also delay 2 Hz pose data by seconds). The lane: send immediately at
  broadcast time, but CONSULT the same channel-writability/yield gate before
  sending, and charge `SharedBandwidthLimiter.recordSend` so the governor sees the
  bytes. Far-player bytes get their OWN diag counters — never folded into
  `service.bytes_sent`/`wire_bytes`, which feed soak_report's cross-identity audits
  against client received counters.
- Honesty note on cost (review): the per-viewer delta/equipment-hash bookkeeping is
  still O(V×P) MEMORY with per-pair rows — the win over the historical renderer is eliminating per-pair
  snapshot/string construction and unchanged-player bytes, not the asymptotic shape.
- **Folia cross-region reads** (review): positions are plain-field stale-tolerant
  (matches how the pump already reads `player.chunkPosition()` — precedent in
  `PaperRequestProcessingService`), but equipment/pose/vehicle reads are a NEW
  cross-region read class (potentially torn ItemStack reads). Decision:
  accept-and-document for display-only data rather than EntityScheduler hops;
  contained per player (a torn read renders one wrong frame of gear); the Folia
  experimental label covers it in release notes.
- Diag: a `FarPlayers:` line in `/lsslod diag` (subscribers, snapshots/s, bytes/s,
  suppressed-unchanged count) + exporter fields on both platform exporters, added to
  `check_soak.py`'s `KNOWN_SERVER_KEYS` with `--selftest` cases and the
  `DiagnosticsFormatter` golden updates (review: additive fields WARN as unknown
  until registered — register them, don't ship warnings).

### 3.3 Client (tracker + renderer)

- Tracker mirrors generational latest-wins map, plus the roster layer
  (index→identity, epoch-guarded) and per-player equipment cache.
- **Interpolation from the DECLARED cadence** (review MAJOR — the first draft's
  measured EWMA was mutually hostile with delta suppression: a suppressed-stationary
  player's measured gap grows unbounded, so their first movement would slow-motion
  glide over an inflated window, and tier migration shifts the gap under the
  filter). The lerp window = the server-declared tier interval + 20% margin;
  measurement survives only as a correction clamped to ≤2× declared. **Velocity
  extrapolation** for moving players (dead-reckon from the velocity hint, clamped to
  ~1.5 windows) — the elytra lag case.
- **Handoff with hysteresis**: use the real `RemotePlayer`'s client-side
  `ClientEntityEvents.ENTITY_LOAD/UNLOAD` (fabric-lifecycle-events-v1 — review
  confirmed these fire for player adds/removes) as EDGE TRIGGERS, but keep the historical renderer's
  conjuncts in the steady-state formula — the review's caveat: entity-add can
  precede the client having a renderable chunk, which is exactly why the historical renderer's
  `chunkLoaded` term exists. ±16-block hysteresis band + a 1-frame crossfade guard.
  **SUPERSEDED AS BUILT (E2 review M3, decisions log 2026-08-13 entry 16 — §6.1
  pair): the shipped handoff is vanilla's own cull predicate (`real present ∧
  chunk loaded ∧ real.shouldRenderAtSqrDistance(camDistSq)`) keyed the same both
  directions, NOT a Euclidean distance band. Review proved the band shape
  double-renders at the render square's diagonal (Euclidean vs Chebyshev chunk
  geometry) and leaves an invisibility annulus at high render distance (entity
  cull ~256 blocks sits far inside a 32-chunk circle); the same-predicate swap
  frame-synchronizes with vanilla's entity pop, so no band is needed. The
  ENTITY_LOAD edge trigger survives as the same-frame kill.**
- Renderer: adopt `RemotePlayer`-proxy + `LevelRenderContext` submission
  approach (proven on 26.2), with: entity-ID allocation guarded against collision
  with real entity IDs, poses (sneak/glide/swim) mapped as the historical renderer does,
  TAB-`PlayerInfo` skins, name-tag toggle, animation-distance cap. Vehicles:
  phase C — render the vehicle model at the snapshot pose directly rather than
  spawning rideable client entities and re-seating every frame (eject/re-seat
  per frame is the hackiest part of their renderer).
- **Visibility decision (review MAJOR — do NOT copy the historical renderer blind here):**
  - **No glow, ever, by default.** the historical renderer sets `setGlowingTag(true)` on every proxy —
    a through-wall outline that contradicts this plan's own privacy stance. LSS
    proxies render as normal entities; if an outline option is ever wanted it is a
    separate opt-in with its own privacy note.
  - **Fog stance:** LSS ships NO fog mixin in phase B. Voxy users overwhelmingly run
    with extended/disabled fog already (the LOD mod owns the horizon); a proxy
    beyond vanilla fog-end on a fog-default client simply fades like terrain would —
    documented, with a client-config `farPlayersMaxRenderDistanceBlocks` the user
    can align with their fog. If live testing shows it matters, a fog *option*
    follows the tracer's non-required-mixin discipline in a later phase.
- Client config (`lss-client-config.json`): `farPlayersEnabled` (default true),
  distance overrides, name tags, shareSelf + share distance — plus Sodium option
  screen rows next to the existing LSS entries.
- **Concrete capability gate** (review — "renderer viable" was not a real
  predicate): the bit is sent when `farPlayersEnabled` AND `-Dlss.soak` /
  `-Dlss.benchmark` are absent. The soak/benchmark clients are full Loom clients
  distinguished only by those properties, and they DO register LSSApi consumers +
  send `CAPABILITY_VOXEL_COLUMNS|zstd` — without the explicit property check they
  would subscribe and shift soak baselines. In Phase A (no renderer yet) this same
  gate applies — the bit does not wait for the renderer (review: the draft's
  renderer-viability wording made Phase A unverifiable by its own gate).

### 3.4 Config (server, shared `ServerConfigBase` — both platforms)

`farPlayers` (`on`/`opt-in`/`off`, default `on`), `farPlayersUpdateIntervalTicks`
(default 10, clamp 2..100), `farPlayersMaxDistanceBlocks` (default 2048, clamp
128..16384), `farPlayersMinDistanceBlocks` (default 0), `farPlayersSendSpectators`
(default false), `farPlayersExclude` (name/UUID list, default empty —
`xrayHiddenBlocks` is the shared-List precedent). All clamped in `validate()` with
the standard test-table entries (Fabric switch + Paper `SHARED_BOUNDS`), plus an
erratum in the clamp-audit doc.

**VSS branding interaction (review)**: the new Paper permission node
(`lss.farplayers.hidden`) lands in plugin.yml, whose LSS↔VSS pair diff is pinned
line-by-line by `release_check.py` and whose rebrand set today is exactly the
command key + `lss.admin`→`vss.admin`. The vssJar rewrite (paper/build.gradle) AND
the release_check token lists must be extended together for the new node
(`vss.farplayers.hidden`), or the release gate reds / the VSS jar ships an `lss.*`
node. Wire channels stay `lss:*` verbatim (the wire-compat contract); config keys
need nothing.

## 4. Operational requirements

1. Build each target snapshot once per tick, with distance-tiered cadence.
2. Use an epoch-scoped roster, quantized position/rotation and equipment dictionaries.
3. Keep Folia work on the existing owning pump and player/region schedulers.
4. Enforce server privacy policy, excluded players, hidden permissions and vanish.
5. Interpolate using the advertised cadence and bounded velocity extrapolation.
6. Hand off between vanilla and proxy rendering at the tracking boundary.
7. Keep wire parity, diagnostics, bandwidth limits and validation independent of loader.
8. Negotiate additive payloads through capability bits.

## 5. Implementation provenance

The implementation uses vanilla rendering APIs and independent LSS wire/state code.
The inspiration acknowledgement is kept in the project README.

## 6. Local rendering choice

The former automatic mod-presence suppression was removed by the YAML settings
work on 2026-09-28. Players manually disable `far_players.enabled` if they use
another distant-player renderer. Rendering and position-sharing privacy remain
independent; changing local rendering must never silently enable sharing.

## 7. Phasing

- **Phase A — wire + server service + client tracker** (no rendering): payloads,
  capability bit (the §3.3 property-gated form — active from Phase A), broadcast
  service with filters/tiers/privacy, client-side tracked state exposed via a debug
  HUD line + `/lss diag` counters. Tier 1 twins (codec parity, filter ladder via
  seams, prefs/roster lifecycle incl. the epoch resync paths). Tier 2 asserts the
  SERVER EGRESS surface (review correction — client tracker state is not reachable
  from a server gametest): a crafted-handshake mock player with the capability bit
  receives roster + updates for a far player and nothing for a near one — the
  `ServiceLifecycleGameTests` crafted-frame pattern. Tracker state itself is Tier 3
  / live territory.
- **Phase B — renderer**: proxy entities, interpolation/extrapolation, handoff,
  name tags, poses, skins. Live-verified on the test rig (the the historical renderer session's exact
  setup, minus the historical renderer).
- **Phase C — polish**: vehicles, Sodium config rows, opt-in mode UX, the historical renderer-coexist
  default, exporter/soak-report fields, README + release notes.

## 8. Risks / open questions

- **Rendering is new territory for LSS** — the one area with no existing test
  discipline; Phase B leans on live verification and keeps the renderer strictly
  client-side/optional (a renderer crash must degrade to "no proxies", contained
  like every LSS compat surface).
- 26.2's `LevelRenderContext`/submit API is what the historical renderer targets today; MC rendering
  APIs churn per version — the renderer needs the same per-MC-line porting budget as
  the rest of the client. **SUPERSEDED (mega plan R-7 v1.4, §6.1 pair — this pointer
  edit rides E1's PR): far players ship on ALL THREE lines**, backed by the measured
  the historical renderer per-line diffs (26.2→26.1.2 = 6 lines; 26.2→1.21.11 = 60/43 lines of symbol
  renames, same render architecture); "backports likely skip Phase B" no longer holds.
- Folia cross-region position reads from the pump are stale-tolerant by design
  (positions are plain fields; a 1-tick-stale snapshot is invisible at 500 ms
  cadence) — document rather than synchronize, matching the Folia experimental
  labeling rules.
- ESP concern is real and worth the README privacy note regardless of design — even
  the 2048 default reveals positions far beyond vanilla; `opt-in` mode exists for
  servers that care.
- Open: should Phase A data also be exposed through `LSSApi` (a
  `FarPlayerConsumer`) so other mods can consume the feed? Cheap to add, matches the
  LSSApi philosophy — leaning yes, decide at implementation.

## 9. Verification

1. Tier 1: codec/parity/filter/prefs/clamp tests both platforms, PLUS (review):
   `DiagnosticsFormatter` golden updates for the `FarPlayers:` line, exporter twins
   with `KNOWN_SERVER_KEYS` + `check_soak.py --selftest` case registration, and the
   protocol-constant envelope pin for the new channel ids.
2. Tier 2: server-egress gametests per §7 Phase A (crafted-handshake mock player;
   NOT client-tracker assertions); handoff behavior is Tier 3/live.
3. `SOAK_PLATFORM=paper` + Fabric `soak.sh all` — baselines must be untouched (soak
   clients never set the capability bit; assert `FarPlayers:` counters stay 0).
4. Live: the the historical renderer test-rig session repeated with LSS-native proxies — two real
   clients + the SoakPlayer dummy, elytra flight for the extrapolation case, walk
   across the tracking boundary for handoff, vanish/spectator checks via RCON.
5. Folia: one manual `run-folia` session with two clients (experimental label rules
   apply to release notes).
