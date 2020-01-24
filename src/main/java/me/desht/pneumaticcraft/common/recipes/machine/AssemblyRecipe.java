package me.desht.pneumaticcraft.common.recipes.machine;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import me.desht.pneumaticcraft.api.crafting.PneumaticCraftRecipes;
import me.desht.pneumaticcraft.api.crafting.recipe.IAssemblyRecipe;
import me.desht.pneumaticcraft.common.recipes.AbstractRecipeSerializer;
import me.desht.pneumaticcraft.common.recipes.MachineRecipeHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.Ingredient;
import net.minecraft.item.crafting.ShapedRecipe;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.lang3.Validate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static me.desht.pneumaticcraft.common.util.PneumaticCraftUtils.RL;

public class AssemblyRecipe implements IAssemblyRecipe {
    private final ResourceLocation id;
    private final Ingredient input;
    private final ItemStack output;
    private final AssemblyProgramType program;

    public AssemblyRecipe(ResourceLocation id, @Nonnull Ingredient input, @Nonnull ItemStack output, AssemblyProgramType program) {
        this.id = id;
        this.input = input;
        this.output = output;
        this.program = program;
    }

    @Override
    public Ingredient getInput() {
        return input;
    }

    @Override
    public int getInputAmount() {
        return input.getMatchingStacks().length > 0 ? input.getMatchingStacks()[0].getCount() : 0;
    }

    @Override
    public ItemStack getOutput() {
        return output;
    }

    @Override
    public AssemblyProgramType getProgramType() {
        return program;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public ResourceLocation getRecipeType() {
        return MachineRecipeHandler.Category.ASSEMBLY.getId();
    }

    @Override
    public boolean matches(ItemStack stack) {
        return input.test(stack) && stack.getCount() >= getInputAmount();
    }

    /**
     * Categorise assembly recipes by subtype; whether they require a laser or drill.  Also set up chained recipes;
     * see {@link #calculateAssemblyChain()}.
     *
     * @param recipes all known assembly recipes
     */
    public static void setupRecipeSubtypes(Collection<IAssemblyRecipe> recipes) {
        Map<ResourceLocation, IAssemblyRecipe> laser = new HashMap<>();
        Map<ResourceLocation, IAssemblyRecipe> drill = new HashMap<>();
        for (IAssemblyRecipe recipe : recipes) {
            switch (recipe.getProgramType()) {
                case LASER:
                    laser.put(recipe.getId(), recipe);
                    break;
                case DRILL:
                    drill.put(recipe.getId(), recipe);
                    break;
            }
        }
        PneumaticCraftRecipes.assemblyLaserRecipes = ImmutableMap.copyOf(laser);
        PneumaticCraftRecipes.assemblyDrillRecipes = ImmutableMap.copyOf(drill);
        PneumaticCraftRecipes.assemblyLaserDrillRecipes = ImmutableMap.copyOf(calculateAssemblyChain());
    }

    /**
     * Work out which recipes can be chained.  E.g. if laser recipe makes B from A, and drill recipe makes C from B,
     * then add a laser/drill recipe to make C from A. Takes into account the number of inputs & outputs from each step.
     */
    private static Map<ResourceLocation,IAssemblyRecipe> calculateAssemblyChain() {
        Map<ResourceLocation, IAssemblyRecipe> r = new HashMap<>();
        for (IAssemblyRecipe r1 : PneumaticCraftRecipes.assemblyDrillRecipes.values()) {
            for (IAssemblyRecipe r2 : PneumaticCraftRecipes.assemblyLaserRecipes.values()) {
                if (r2.getInput().test(r1.getOutput())
                        && r1.getOutput().getCount() % r2.getInputAmount() == 0
                        && r2.getOutput().getMaxStackSize() >= r2.getOutput().getCount() * (r1.getOutput().getCount() / r2.getInputAmount())) {
                    ItemStack output = r2.getOutput().copy();
                    output.setCount(output.getCount() * (r1.getOutput().getCount() / r2.getInputAmount()));
                    String name = r1.getId().getPath() + "/" + r2.getId().getPath();
                    ResourceLocation id = RL(name);
                    r.put(id, new AssemblyRecipe(id, r1.getInput(), output, AssemblyProgramType.DRILL_LASER));
                }
            }
        }
        return r;
    }

    public static class Serializer extends AbstractRecipeSerializer<AssemblyRecipe> {
        @Override
        public AssemblyRecipe read(ResourceLocation recipeId, JsonObject json) {
            Ingredient input = Ingredient.deserialize(json.get("input"));
            ItemStack result = ShapedRecipe.deserializeItem(JSONUtils.getJsonObject(json, "result"));
            String program = JSONUtils.getString(json, "program").toUpperCase();
            try {
                AssemblyProgramType programType = AssemblyProgramType.valueOf(program);
                Validate.isTrue(programType != AssemblyProgramType.DRILL_LASER, "'drill_laser' may not be used in recipe JSON!");
                return new AssemblyRecipe(recipeId, input, result, programType);
            } catch (IllegalArgumentException e) {
                throw new JsonParseException(e.getMessage());
            }
        }

        @Nullable
        @Override
        public AssemblyRecipe read(ResourceLocation recipeId, PacketBuffer buffer) {
            Ingredient input = Ingredient.read(buffer);
            ItemStack out = buffer.readItemStack();
            AssemblyProgramType program = AssemblyProgramType.values()[buffer.readVarInt()];
            return new AssemblyRecipe(recipeId, input, out, program);
        }

        @Override
        public void write(PacketBuffer buffer, AssemblyRecipe recipe) {
            super.write(buffer, recipe);

            recipe.input.write(buffer);
            buffer.writeItemStack(recipe.output);
            buffer.writeVarInt(recipe.program.ordinal());
        }
    }
}
