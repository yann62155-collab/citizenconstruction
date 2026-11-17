package fr.citizen.worldbuilder;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.levelgen.Heightmap;

public final class CitizenCommands {
    private CitizenCommands() {}
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("citizenbuild").requires(s -> s.hasPermission(2))
            .then(Commands.literal("generate").executes(ctx -> {
                var source=ctx.getSource();
                if (BuildQueue.isRunning()) { source.sendFailure(Component.literal("Une génération est déjà en cours.")); return 0; }
                var level=source.getLevel();
                var player=source.getPlayerOrException().blockPosition();
                int y=level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,player.getX(),player.getZ());
                BuildQueue.start(level,new net.minecraft.core.BlockPos(player.getX(),y,player.getZ()));
                source.sendSuccess(() -> Component.literal("Génération prototype lancée dans la dimension actuelle. Utilise /citizenbuild status."),true);
                return 1;
            }))
            .then(Commands.literal("status").executes(ctx -> { ctx.getSource().sendSuccess(() -> Component.literal(BuildQueue.status()),false); return 1; }))
            .then(Commands.literal("cancel").executes(ctx -> { BuildQueue.cancel(); ctx.getSource().sendSuccess(() -> Component.literal("Génération annulée."),true); return 1; })));
    }
}
