package dev.lodekeeper.fabric;

import dev.lodekeeper.core.ItemId;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.display.SlotDisplay;
import net.minecraft.registry.Registries;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** Native display regressions run without a window, renderer, client, server or world. */
final class RecipeDisplayTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }
    @Test void acceptsNativeExplicitIngredientDisplays() {
        SlotDisplay nativeDisplay = Ingredient.ofItems(Items.OAK_PLANKS, Items.BIRCH_PLANKS).toDisplay();
        var facts = GameApi.slotFacts(nativeDisplay);
        assertEquals(Set.of(ItemId.parse("minecraft:oak_planks"), ItemId.parse("minecraft:birch_planks")), facts.items());
        assertTrue(facts.remainder().isEmpty());
    }
    @Test void retainsExplicitEmptyContractThroughNestedAlternatives() {
        SlotDisplay explicitEmpty = new SlotDisplay.WithRemainderSlotDisplay(item(Items.STICK), SlotDisplay.EmptySlotDisplay.INSTANCE);
        SlotDisplay nested = composite(composite(explicitEmpty, item(Items.OAK_PLANKS)));
        assertTrue(GameApi.slotFacts(nested).containsExplicitDeclaration());
        assertThrows(IllegalArgumentException.class, () -> GameApi.slotFacts(
            new SlotDisplay.WithRemainderSlotDisplay(nested, item(Items.BUCKET))));
    }
    @Test void allowsOuterContractForUndeclaredAlternatives() {
        var facts = GameApi.slotFacts(new SlotDisplay.WithRemainderSlotDisplay(
            composite(item(Items.STICK), item(Items.OAK_PLANKS)), item(Items.BUCKET)));
        assertTrue(facts.remainderDeclared());
        assertTrue(facts.remainder().isOf(Items.BUCKET));
    }
    @Test void rejectsDifferentRemainderQuantities() {
        var one = Items.BUCKET.getDefaultStack();
        var two = one.copy(); two.setCount(2);
        assertThrows(IllegalArgumentException.class, () -> GameApi.slotFacts(composite(
            new SlotDisplay.WithRemainderSlotDisplay(item(Items.STICK), new SlotDisplay.StackSlotDisplay(one)),
            new SlotDisplay.WithRemainderSlotDisplay(item(Items.OAK_PLANKS), new SlotDisplay.StackSlotDisplay(two)))));
    }
    @Test void rejectsEmptyAndExcessiveAlternatives() {
        assertThrows(IllegalArgumentException.class, () -> GameApi.slotFacts(composite()));
        assertThrows(IllegalArgumentException.class, () -> GameApi.slotFacts(composite(item(Items.STICK), SlotDisplay.EmptySlotDisplay.INSTANCE)));
        SlotDisplay nested = item(Items.STICK);
        for (int depth = 0; depth < 10; depth++) nested = composite(nested);
        SlotDisplay tooDeep = nested;
        assertThrows(IllegalArgumentException.class, () -> GameApi.slotFacts(tooDeep));
        assertThrows(IllegalArgumentException.class, () -> GameApi.slotFacts(new SlotDisplay.CompositeSlotDisplay(
            java.util.Collections.nCopies(256, item(Items.STICK)))));
    }
    private static SlotDisplay item(net.minecraft.item.Item item) {
        return new SlotDisplay.ItemSlotDisplay(Registries.ITEM.getEntry(item));
    }
    private static SlotDisplay composite(SlotDisplay... values) {
        return new SlotDisplay.CompositeSlotDisplay(List.of(values));
    }
}
