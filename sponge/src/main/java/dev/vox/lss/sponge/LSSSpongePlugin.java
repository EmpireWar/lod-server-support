package dev.vox.lss.sponge;

import com.google.inject.Inject;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.vox.lss.common.Brand;
import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.networking.server.LSSServerCommands;
import dev.vox.lss.networking.server.LSSServerNetworking;
import dev.vox.lss.platform.LoaderServices;
import dev.vox.lss.platform.SpongeLoaderServices;
import net.kyori.adventure.text.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import org.spongepowered.api.Server;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.command.Command;
import org.spongepowered.api.command.CommandCause;
import org.spongepowered.api.command.CommandCompletion;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.exception.CommandException;
import org.spongepowered.api.command.parameter.ArgumentReader;
import org.spongepowered.api.config.ConfigDir;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.lifecycle.RegisterChannelEvent;
import org.spongepowered.api.event.lifecycle.RegisterCommandEvent;
import org.spongepowered.api.event.lifecycle.StartedEngineEvent;
import org.spongepowered.api.event.lifecycle.StoppingEngineEvent;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.scheduler.Task;
import org.spongepowered.api.util.Ticks;
import org.spongepowered.plugin.PluginContainer;
import org.spongepowered.plugin.builtin.jvm.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Sponge entrypoint — the fabric {@code LSSMod} / NeoForge {@code LSSNeoMod} twin. Brand
 * first, the LoaderServices seam second (before any config touch), then channel, command
 * and lifecycle wiring onto the shared xplat server half.
 */
@Plugin("lodserversupport")
public class LSSSpongePlugin {
    private final PluginContainer container;

    @Inject
    public LSSSpongePlugin(PluginContainer container, @ConfigDir(sharedRoot = true) Path configRoot) {
        this.container = container;
        Brand.load(LSSSpongePlugin.class.getClassLoader());
        // The shared config root, where Fabric/NeoForge keep the same files.
        LoaderServices.install(new SpongeLoaderServices(configRoot));
    }

    @Listener
    public void onRegisterChannels(RegisterChannelEvent event) {
        SpongeChannels.register(event);
    }

    /**
     * The shared /lsslod brigadier tree, run on a private dispatcher behind a Sponge raw
     * command: Sponge owns the vanilla dispatcher. The Sponge permission node gates access,
     * so the tree's own op-level gate is satisfied by elevating the source.
     */
    @Listener
    public void onRegisterCommands(RegisterCommandEvent<Command.Raw> event) {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        LSSServerCommands.register(dispatcher);
        String root = Brand.serverCommand();
        event.register(this.container, new Command.Raw() {
            @Override
            public CommandResult process(CommandCause cause, ArgumentReader.Mutable arguments)
                    throws CommandException {
                try {
                    dispatcher.execute(input(root, arguments), source(cause));
                    return CommandResult.success();
                } catch (CommandSyntaxException e) {
                    throw new CommandException(Component.text(e.getMessage()));
                }
            }

            @Override
            public List<CommandCompletion> complete(CommandCause cause, ArgumentReader.Mutable arguments) {
                var parse = dispatcher.parse(input(root, arguments), source(cause));
                return dispatcher.getCompletionSuggestions(parse).join().getList().stream()
                        .map(s -> CommandCompletion.of(s.getText())).toList();
            }

            @Override
            public boolean canExecute(CommandCause cause) {
                return cause.hasPermission(Brand.lowerShortName() + ".admin");
            }

            @Override
            public Optional<Component> shortDescription(CommandCause cause) {
                return Optional.of(Component.text(Brand.displayName() + " admin commands"));
            }

            @Override
            public Optional<Component> extendedDescription(CommandCause cause) {
                return shortDescription(cause);
            }

            @Override
            public Component usage(CommandCause cause) {
                return Component.text("<help|stats|diag|diagnostics|reload|store>");
            }
        }, root);
    }

    /** The full brigadier input; a bare root must not carry a trailing space (a parse error). */
    private static String input(String root, ArgumentReader arguments) {
        String rest = arguments.remaining();
        return rest.isEmpty() ? root : root + " " + rest;
    }

    private static CommandSourceStack source(CommandCause cause) {
        return ((CommandSourceStack) (Object) cause).withPermission(LevelBasedPermissionSet.GAMEMASTER);
    }

    @Listener
    public void onServerStarted(StartedEngineEvent<Server> event) {
        LSSServerNetworking.onServerStarted((MinecraftServer) event.engine());
        SpongeChannels.setReceiving(true);
        // The main thread is the service's single pump.
        Sponge.server().scheduler().submit(Task.builder()
                .plugin(this.container)
                .interval(Ticks.single())
                .execute(LSSServerNetworking::onServerTick)
                .build());
        LSSLogger.info(Brand.displayName() + " (Sponge) enabled");
    }

    @Listener
    public void onServerStopping(StoppingEngineEvent<Server> event) {
        // Stop dispatching before the teardown so no new frame races the shutdown.
        SpongeChannels.setReceiving(false);
        Sponge.server().scheduler().tasks(this.container).forEach(task -> task.cancel());
        LSSServerNetworking.onServerStopping();
    }

    @Listener
    public void onPlayerDisconnect(ServerSideConnectionEvent.Disconnect event) {
        event.profile().ifPresent(profile -> LSSServerNetworking.onPlayerDisconnect(profile.uuid()));
    }
}
