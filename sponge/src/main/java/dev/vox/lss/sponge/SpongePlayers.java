package dev.vox.lss.sponge;

import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.api.data.Keys;
import org.spongepowered.api.effect.VanishState;
import org.spongepowered.api.util.Tristate;

/** Sponge reads of an NMS player: the NMS class IS the Sponge player at runtime. */
final class SpongePlayers {

    private SpongePlayers() {}

    static org.spongepowered.api.entity.living.player.server.ServerPlayer api(ServerPlayer player) {
        return (org.spongepowered.api.entity.living.player.server.ServerPlayer) (Object) player;
    }

    /**
     * Deny-model nodes (lss.use / vss.use): held unless explicitly set false. Bukkit gets
     * this from plugin.yml's {@code default: true}; Sponge resolves an unset node to
     * UNDEFINED, which {@code hasPermission} would read as denied for everyone.
     */
    static boolean holds(ServerPlayer player, String node) {
        return api(player).permissionValue(node) != Tristate.FALSE;
    }

    /**
     * Grant-model nodes (the far-player hidden pair): only an explicit true counts, so an
     * op is not hidden merely for being an op.
     */
    static boolean granted(ServerPlayer player, String node) {
        return api(player).permissionValue(node) == Tristate.TRUE;
    }

    static boolean vanished(ServerPlayer player) {
        return api(player).get(Keys.VANISH_STATE).map(VanishState::invisible).orElse(false);
    }
}
