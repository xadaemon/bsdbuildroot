package sedna_freebsd.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Registers the bundled data pack (resourcepacks/freebsd in the jar) as a
 * built-in pack, turned on in new and existing worlds unless disabled.
 */
public final class SednaFreeBSDFabric implements ModInitializer {
    public static final String MOD_ID = "sedna_freebsd";

    @Override
    public void onInitialize() {
        ResourceManagerHelper.registerBuiltinResourcePack(
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "freebsd"),
            FabricLoader.getInstance().getModContainer(MOD_ID).orElseThrow(),
            Component.literal("Sedna FreeBSD"),
            ResourcePackActivationType.DEFAULT_ENABLED);
    }
}
