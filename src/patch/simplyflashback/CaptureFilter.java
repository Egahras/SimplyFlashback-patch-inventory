package patch.simplyflashback;

import com.simplyran.simplyflashback.capture.IgnoredPacketFilter;
import com.simplyran.simplyflashback.version.VersionAdapters;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;

/** Let full inventory sync reach the patched 26.3 encoder; retain all other filters. */
public final class CaptureFilter {
    private CaptureFilter() {}

    public static boolean shouldSkip(Object packet) {
        return shouldSkip(packet, packet instanceof ClientboundContainerSetContentPacket
                && VersionAdapters.current().getClass().getName().equals(
                    "com.simplyran.simplyflashback.version.v26_3.V26_3Adapter"));
    }

    static boolean shouldSkip(Object packet, boolean patchedAdapter) {
        if (packet instanceof ClientboundContainerSetContentPacket && patchedAdapter) {
            return false;
        }
        return IgnoredPacketFilter.shouldSkip(packet);
    }
}
