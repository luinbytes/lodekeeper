package dev.lodekeeper.fabric;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/** Native client access; storage views are used only on the client thread. */
final class ClientAccess {
    private ClientAccess() {}
    static List<ItemStack> main(PlayerInventory inventory) { return inventory.getMainStacks(); }
    static int selectedSlot(PlayerInventory inventory) { return inventory.getSelectedSlot(); }
    static void selectedSlot(PlayerInventory inventory, int slot) { inventory.setSelectedSlot(slot); }
    static Vec3d position(Entity entity) { return entity.getPos(); }
    static KeyBinding stopKey() {
        return new KeyBinding("key.lodekeeper.stop", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "category.lodekeeper");
    }
}
