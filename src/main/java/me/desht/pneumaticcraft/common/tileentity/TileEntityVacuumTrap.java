package me.desht.pneumaticcraft.common.tileentity;

import com.google.common.collect.ImmutableMap;
import me.desht.pneumaticcraft.api.item.EnumUpgrade;
import me.desht.pneumaticcraft.client.util.ClientUtils;
import me.desht.pneumaticcraft.common.PneumaticCraftTags;
import me.desht.pneumaticcraft.common.config.PNCConfig;
import me.desht.pneumaticcraft.common.core.ModBlocks;
import me.desht.pneumaticcraft.common.core.ModTileEntities;
import me.desht.pneumaticcraft.common.entity.living.EntityDrone;
import me.desht.pneumaticcraft.common.inventory.ContainerVacuumTrap;
import me.desht.pneumaticcraft.common.item.ItemSpawnerCore.SpawnerCoreItemHandler;
import me.desht.pneumaticcraft.common.network.DescSynced;
import me.desht.pneumaticcraft.common.network.GuiSynced;
import me.desht.pneumaticcraft.common.util.ITranslatableEnum;
import me.desht.pneumaticcraft.lib.Names;
import me.desht.pneumaticcraft.lib.PneumaticValues;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MobEntity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.Fluids;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.INamedContainerProvider;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.state.properties.BlockStateProperties;
import net.minecraft.util.Direction;
import net.minecraft.util.NonNullList;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvents;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.entity.living.LivingSpawnEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TileEntityVacuumTrap extends TileEntityPneumaticBase implements
        IMinWorkingPressure, INamedContainerProvider, ISerializableTanks, IRangedTE {
    static final String DEFENDER_TAG = Names.MOD_ID + ":defender";
    public static final int MEMORY_ESSENCE_AMOUNT = 100;

    public enum Problems implements ITranslatableEnum {
        OK,
        NO_CORE,
        CORE_FULL,
        TRAP_CLOSED;

        @Override
        public String getTranslationKey() {
            return "pneumaticcraft.gui.tab.problems.vacuum_trap." + this.toString().toLowerCase();
        }
    }

    private final SpawnerCoreItemHandler inv = new SpawnerCoreItemHandler();
    private final LazyOptional<IItemHandler> invCap = LazyOptional.of(() -> inv);

    private final List<MobEntity> targetEntities = new ArrayList<>();

    private final RangeManager rangeManager = new RangeManager(this, 0x60600060);

    @GuiSynced
    private final SmartSyncTank xpTank = new XPTank();
    private final LazyOptional<IFluidHandler> fluidCap = LazyOptional.of(() -> xpTank);

    @DescSynced
    private boolean isCoreLoaded;
    @DescSynced
    public Problems problem = Problems.OK;

    public TileEntityVacuumTrap() {
        super(ModTileEntities.VACUUM_TRAP.get(), PneumaticValues.DANGER_PRESSURE_TIER_ONE, PneumaticValues.MAX_PRESSURE_TIER_ONE, PneumaticValues.VOLUME_VACUUM_TRAP, 4);
    }

    @Override
    public void tick() {
        super.tick();

        xpTank.tick();

        rangeManager.setRange(3 + getUpgrades(EnumUpgrade.RANGE));

        if (!world.isRemote) {
            isCoreLoaded = inv.getStats() != null;

            if (isOpen() && isCoreLoaded && inv.getStats().getUnused() > 0 && getPressure() <= getMinWorkingPressure()) {
                if ((world.getGameTime() & 0xf) == 0) {
                    scanForEntities();
                }
                Vector3d trapVec = Vector3d.copyCentered(pos);
                double min = world.getFluidState(pos).getFluid() == Fluids.WATER ? 2.5 : 1.75;
                for (MobEntity e : targetEntities) {
                    if (!e.isAlive() || e.getTags().contains(DEFENDER_TAG)) continue;
                    // kludge: mobs in water seem a bit flaky about getting close enough so increase the absorb dist a bit
                    if (e.getDistanceSq(trapVec) <= min) {
                        absorbEntity(e);
                        addAir((int) (PneumaticValues.USAGE_VACUUM_TRAP * e.getHealth()));
                    } else {
                        e.getNavigator().tryMoveToXYZ(trapVec.getX(), trapVec.getY(), trapVec.getZ(), 1.2);
                    }
                }
            }
            if (!isCoreLoaded)
                problem = Problems.NO_CORE;
            else if (inv.getStats().getUnused() == 0)
                problem = Problems.CORE_FULL;
            else if (!isOpen())
                problem = Problems.TRAP_CLOSED;
            else
                problem = Problems.OK;
        } else {
            if (isOpen() && isCoreLoaded && world.rand.nextBoolean()) {
                ClientUtils.emitParticles(world, pos, ParticleTypes.PORTAL);
            }
        }
    }

    private void absorbEntity(MobEntity e) {
        int toAdd = 1;
        if (xpTank.getFluid().getAmount() >= MEMORY_ESSENCE_AMOUNT) {
            toAdd += e.world.rand.nextInt(3) + 1;
        }
        if (inv.getStats().addAmount(e.getType(), toAdd)) {
            e.remove();
            if (toAdd > 1) xpTank.drain(MEMORY_ESSENCE_AMOUNT, IFluidHandler.FluidAction.EXECUTE);
            inv.getStats().serialize(inv.getStackInSlot(0));
            e.world.playSound(null, pos, SoundEvents.BLOCK_PORTAL_TRIGGER, SoundCategory.BLOCKS, 1f, 2f);
            if (world instanceof ServerWorld) {
                ((ServerWorld) world).spawnParticle(ParticleTypes.CLOUD, e.getPosX(), e.getPosY() + 0.5, e.getPosZ(), 5, 0, 1, 0, 0);
            }
        }
    }

    private void scanForEntities() {
        targetEntities.clear();
        targetEntities.addAll(world.getEntitiesWithinAABB(MobEntity.class, rangeManager.getExtents(), this::isApplicable));
    }

    private boolean isApplicable(LivingEntity e) {
        return e.isNonBoss()
                && !(e instanceof EntityDrone)
                && !(e instanceof TameableEntity && ((TameableEntity) e).isTamed())
                && !PNCConfig.Common.General.vacuumTrapBlacklist.contains(e.getType().getRegistryName());
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        if (cap == CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY) {
            return fluidCap.cast();
        } else {
            return super.getCapability(cap, side);
        }
    }

    public FluidTank getFluidTank() {
        return xpTank;
    }

    @Nonnull
    @Override
    public Map<String, FluidTank> getSerializableTanks() {
        return ImmutableMap.of("Tank", xpTank);
    }

    @Override
    public IItemHandler getPrimaryInventory() {
        return inv;
    }

    @Nonnull
    @Override
    protected LazyOptional<IItemHandler> getInventoryCap() {
        return invCap;
    }

    @Override
    public boolean canConnectPneumatic(Direction side) {
        return side == Direction.DOWN || side.getAxis() == getRotation().getAxis();
    }

    @Override
    public float getMinWorkingPressure() {
        return -0.5f;
    }

    @Nullable
    @Override
    public Container createMenu(int windowId, PlayerInventory inv, PlayerEntity player) {
        return new ContainerVacuumTrap(windowId, inv, getPos());
    }

    @Override
    public void read(BlockState state, CompoundNBT tag) {
        super.read(state, tag);

        inv.deserializeNBT(tag.getCompound("Items"));
    }

    @Override
    public CompoundNBT write(CompoundNBT tag) {
        super.write(tag);

        tag.put("Items", inv.serializeNBT());
        return tag;
    }

    @Override
    public void getContentsToDrop(NonNullList<ItemStack> drops) {
        // if we're wrenching, any spawner core should stay in the trap
        if (!shouldPreserveStateOnBreak()) {
            super.getContentsToDrop(drops);
        }
    }

    @Override
    public void serializeExtraItemData(CompoundNBT blockEntityTag, boolean preserveState) {
        super.serializeExtraItemData(blockEntityTag, preserveState);

        if (preserveState) {
            // if wrenching, spawner core stays inside the trap when broken
            blockEntityTag.put("Items", inv.serializeNBT());
        }
    }

    public boolean isOpen() {
        return getBlockState().getBlock() == ModBlocks.VACUUM_TRAP.get() && getBlockState().get(BlockStateProperties.OPEN);
    }

    @Override
    public RangeManager getRangeManager() {
        return rangeManager;
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        return rangeManager.shouldShowRange() ? rangeManager.getExtents() : super.getRenderBoundingBox();
    }

    private class XPTank extends SmartSyncTank {
        public XPTank() {
            super(TileEntityVacuumTrap.this, 16000);
        }

        @Override
        public boolean isFluidValid(FluidStack stack) {
            return stack.getFluid().isIn(PneumaticCraftTags.Fluids.MEMORY_ESSENCE);
        }
    }

    @Mod.EventBusSubscriber(modid = Names.MOD_ID)
    public static class Listener {
        @SubscribeEvent
        public static void onMobSpawn(LivingSpawnEvent.SpecialSpawn event) {
            // tag any mob spawned by a vanilla Spawner (rather than naturally) as a "defender"
            // such defenders are immune to being absorbed by a Vacuum Trap
            // note: mobs spawned by a Pressurized Spawner are not considered to be defenders
            if (!event.isCanceled() && event.getSpawner() != null) {
                event.getEntity().addTag(DEFENDER_TAG);
            }
        }
    }
}
