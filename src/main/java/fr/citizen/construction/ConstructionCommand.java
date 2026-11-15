package fr.citizen.construction;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * /construction liste
 * /construction info <id>
 * /construction construire <id>
 * /construction annuler <id>      remet le terrain comme avant la construction
 * /construction stop
 * /construction github            relève tout de suite les plans sur GitHub
 * /construction recharger         relit config/citizenconstruction.properties
 */
public final class ConstructionCommand {
    private ConstructionCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
        d.register(Commands.literal("construction").requires(s -> s.hasPermission(2))
                .then(Commands.literal("liste").executes(c -> {
                    DemandeStore st = CitizenConstruction.store();
                    if (st == null || st.all().isEmpty()) { say(c, "Aucune demande. Sélectionne une zone sur la carte : /carte"); return 1; }
                    for (Demande dm : st.all()) say(c, dm.id + " · " + dm.titre + " · " + dm.statut
                            + " · X " + dm.x0 + "→" + dm.x1 + " Z " + dm.z0 + "→" + dm.z1 + (dm.planBlocs > 0 ? " · " + dm.planBlocs + " blocs" : ""));
                    return 1;
                }))
                .then(Commands.literal("info").then(Commands.argument("id", StringArgumentType.word()).suggests(ConstructionCommand::ids).executes(c -> {
                    Demande dm = get(c);
                    if (dm == null) return 0;
                    say(c, dm.id + " — " + dm.titre + " (" + dm.statut + ")");
                    say(c, dm.description);
                    if (!dm.message.isEmpty()) say(c, dm.message);
                    if (dm.total > 0) say(c, "Progression : " + dm.progression + " / " + dm.total);
                    return 1;
                })))
                .then(Commands.literal("construire").then(Commands.argument("id", StringArgumentType.word()).suggests(ConstructionCommand::ids).executes(c -> {
                    Demande dm = get(c);
                    if (dm == null) return 0;
                    String e = CitizenConstruction.build(dm);
                    if (e != null) { c.getSource().sendFailure(Component.literal(e)); return 0; }
                    say(c, "Construction de " + dm.id + " lancée (" + dm.total + " blocs).");
                    return 1;
                })))
                .then(Commands.literal("annuler").then(Commands.argument("id", StringArgumentType.word()).suggests(ConstructionCommand::ids).executes(c -> {
                    Demande dm = get(c);
                    if (dm == null) return 0;
                    String e = CitizenConstruction.undo(dm);
                    if (e != null) { c.getSource().sendFailure(Component.literal(e)); return 0; }
                    say(c, "Remise du terrain d'origine pour " + dm.id + " lancée.");
                    return 1;
                })))
                .then(Commands.literal("stop").executes(c -> {
                    if (CitizenConstruction.builder() != null) CitizenConstruction.builder().stopAll("Arrêtée par commande.");
                    say(c, "Opérations arrêtées.");
                    return 1;
                }))
                .then(Commands.literal("github").executes(c -> {
                    GitHubSync.pollNow();
                    say(c, "GitHub : " + GitHubSync.status());
                    return 1;
                }))
                .then(Commands.literal("recharger").executes(c -> {
                    ConstructionConfig.load();
                    GitHubSync.start();
                    say(c, "Configuration rechargée. GitHub : " + GitHubSync.status());
                    return 1;
                })));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> ids(
            CommandContext<CommandSourceStack> c, com.mojang.brigadier.suggestion.SuggestionsBuilder b) {
        DemandeStore st = CitizenConstruction.store();
        return SharedSuggestionProvider.suggest(st == null ? java.util.List.of() : st.all().stream().map(x -> x.id).toList(), b);
    }

    private static Demande get(CommandContext<CommandSourceStack> c) {
        String id = StringArgumentType.getString(c, "id").toUpperCase(java.util.Locale.ROOT);
        Demande dm = CitizenConstruction.store() == null ? null : CitizenConstruction.store().get(id);
        if (dm == null) c.getSource().sendFailure(Component.literal("Demande " + id + " introuvable."));
        return dm;
    }

    private static void say(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg).withStyle(ChatFormatting.GOLD), false);
    }
}
