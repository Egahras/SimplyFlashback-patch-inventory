package patch.simplyflashback;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntUnaryOperator;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerInventoryPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Convert wire inventory packets to Flashback's synthetic container-0 hotbar packets.
 * Returning null means the packet is unrelated and the upstream encoder handles it.
 * Returning an empty list means intentionally omit an unrelated inventory change.
 */
public final class HotbarPackets {
    private HotbarPackets() {}

    public static List<Packet<?>> normalize(ServerPlayer player, Packet<?> packet) {
        if (packet instanceof ClientboundSetPlayerInventoryPacket) {
            return normalize(packet, ignored -> -1);
        }
        final int containerId;
        if (packet instanceof ClientboundContainerSetSlotPacket slot) {
            containerId = slot.getContainerId();
        } else if (packet instanceof ClientboundContainerSetContentPacket content) {
            containerId = content.containerId();
        } else {
            return null;
        }
        AbstractContainerMenu menu = containerId == 0 ? player.inventoryMenu : player.containerMenu;
        if (menu == null || menu.containerId != containerId) {
            // A stale/closed menu cannot be mapped safely. Never guess its slot layout.
            return List.of();
        }
        return normalize(packet, slotLookup(menu, player.getInventory()));
    }

    static IntUnaryOperator slotLookup(AbstractContainerMenu menu, Container inventory) {
        return index -> {
            if (index < 0 || index >= menu.slots.size()) return -1;
            Slot slot = menu.slots.get(index);
            // Use the actual slot owner/index, including custom menus. Armor, crafting,
            // offhand and storage slots must never be mistaken for hotbar slots.
            return slot.container == inventory ? slot.getContainerSlot() : -1;
        };
    }

    static List<Packet<?>> normalize(Packet<?> packet, IntUnaryOperator inventorySlot) {
        if (packet instanceof ClientboundContainerSetSlotPacket slot) {
            int hotbar = inventorySlot.applyAsInt(slot.getSlot());
            return isHotbar(hotbar) ? List.of(update(hotbar, slot.getItem())) : List.of();
        }
        if (packet instanceof ClientboundSetPlayerInventoryPacket slot) {
            return isHotbar(slot.slot()) ? List.of(update(slot.slot(), slot.contents())) : List.of();
        }
        if (packet instanceof ClientboundContainerSetContentPacket content) {
            List<Packet<?>> updates = new ArrayList<>(9);
            for (int i = 0; i < content.items().size(); i++) {
                int hotbar = inventorySlot.applyAsInt(i);
                if (isHotbar(hotbar)) updates.add(update(hotbar, content.items().get(i)));
            }
            return updates;
        }
        return null;
    }

    private static boolean isHotbar(int slot) { return slot >= 0 && slot < 9; }

    private static ClientboundContainerSetSlotPacket update(int slot, ItemStack item) {
        // Copy now: later inventory mutations must not change an already captured action.
        return new ClientboundContainerSetSlotPacket(0, 0, slot, item.copy());
    }
}
