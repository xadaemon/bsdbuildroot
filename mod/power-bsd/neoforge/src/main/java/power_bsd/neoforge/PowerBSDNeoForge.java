package power_bsd.neoforge;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * Registers the bundled data pack (resourcepacks/freebsd in the jar) as a
 * built-in pack. Built-in packs are added to existing worlds automatically
 * too, and can be turned off with /datapack disable.
 */
@Mod(PowerBSDNeoForge.MOD_ID)
public final class PowerBSDNeoForge {
    public static final String MOD_ID = "power_bsd";

    public PowerBSDNeoForge(final IEventBus modBus) {
        modBus.addListener(AddPackFindersEvent.class, event -> event.addPackFinders(
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "resourcepacks/freebsd"),
            PackType.SERVER_DATA,
            Component.literal("Power BSD"),
            PackSource.BUILT_IN,
            false,
            Pack.Position.TOP));
    }
}
