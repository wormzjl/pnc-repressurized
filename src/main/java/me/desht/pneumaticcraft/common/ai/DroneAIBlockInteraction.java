package me.desht.pneumaticcraft.common.ai;

import me.desht.pneumaticcraft.api.item.EnumUpgrade;
import me.desht.pneumaticcraft.common.network.NetworkHandler;
import me.desht.pneumaticcraft.common.network.PacketSpawnIndicatorParticles;
import me.desht.pneumaticcraft.common.pneumatic_armor.CommonArmorHandler;
import me.desht.pneumaticcraft.common.progwidgets.IBlockOrdered;
import me.desht.pneumaticcraft.common.progwidgets.IBlockOrdered.Ordering;
import me.desht.pneumaticcraft.common.progwidgets.ISidedWidget;
import me.desht.pneumaticcraft.common.progwidgets.ProgWidgetAreaItemBase;
import me.desht.pneumaticcraft.common.util.DirectionUtil;
import me.desht.pneumaticcraft.common.util.ThreadedSorter;
import net.minecraft.block.BlockState;
import net.minecraft.block.FlowingFluidBlock;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.pathfinding.PathType;
import net.minecraft.util.Direction;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.ICollisionReader;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public abstract class DroneAIBlockInteraction<W extends ProgWidgetAreaItemBase> extends Goal {
    private static final int MAX_LOOKUPS_PER_SEARCH = 30;
    private static final int DRONE_DEBUG_PARTICLE_RANGE_SQ = 32 * 32;

    protected final IDroneBase drone;
    protected final W progWidget;
    private final Ordering order;
    private BlockPos curPos;
    private final List<BlockPos> area;
    final ICollisionReader worldCache;
    private final List<BlockPos> blacklist = new ArrayList<>(); //a list of position which weren't allowed to be dug in the past.
    private int curY;
    private int lastSuccessfulY;
    private int minY, maxY;
    private ThreadedSorter<BlockPos> sorter;
    private boolean aborted;
    private boolean searching; // True while the drone is searching for a coordinate, false if traveling to or processing a coordinate.
    private int searchIndex;   // The current index in the area list the drone is searching at.
    private int totalActions;
    private int maxActions = -1;

    /**
     * @param drone the drone
     * @param progWidget needs to implement IBlockOrdered
     */
    public DroneAIBlockInteraction(IDroneBase drone, W progWidget) {
        this.drone = drone;
        setMutexFlags(EnumSet.allOf(Flag.class)); // exclusive to all other AI tasks
        this.progWidget = progWidget;
        order = progWidget instanceof IBlockOrdered ? ((IBlockOrdered) progWidget).getOrder() : Ordering.CLOSEST;
        area = progWidget.getCachedAreaList();
        worldCache = progWidget.getChunkCache(drone.world());

        AxisAlignedBB extents = progWidget.getAreaExtents();
        if (area.size() > 0) {
            minY = (int) extents.minY;
            maxY = (int) extents.maxY;
            if (order == Ordering.HIGH_TO_LOW) {
                curY = maxY;
            } else if (order == Ordering.LOW_TO_HIGH) {
                curY = minY;
            }
        }
    }

    /**
     * Returns whether the EntityAIBase should begin execution.
     */
    @Override
    public boolean shouldExecute() {
        if (aborted || maxActions >= 0 && totalActions >= maxActions) {
            return false;
        } else {
            if (!searching) {
                searching = true;
                lastSuccessfulY = curY;
                curPos = null;
                searchIndex = 0;
                if (sorter == null || sorter.isDone()) {
                    sorter = new ThreadedSorter<>(area, new ChunkPositionSorter(drone, order));
                }
                return true;
            } else {
                return false;
            }
        }
    }

    private void updateY() {
        searchIndex = 0;
        if (order == Ordering.LOW_TO_HIGH) {
            if (++curY > maxY) curY = minY;
        } else if (order == Ordering.HIGH_TO_LOW) {
            if (--curY < minY) curY = maxY;
        }
    }

    private boolean isYValid(int y) {
        return order == Ordering.CLOSEST || y == curY;
    }

    public DroneAIBlockInteraction<?> setMaxActions(int maxActions) {
        this.maxActions = maxActions;
        return this;
    }

    /**
     * Check if the given blockpos is valid for the purposes of this operation; generally the operation that is
     * carried out by {@link #doBlockInteraction(BlockPos, double)} should be attempted as a simulation.  If this
     * method returns true, then the drone will attempt to move there (if appropriate) and call
     * {@link #doBlockInteraction(BlockPos, double)} to actually carry out the operation.
     *
     * @param pos the block pos to examine
     * @return true if this pos is valid, false otherwise
     */
    protected abstract boolean isValidPosition(BlockPos pos);

    /**
     * Carry out the actual interaction operation.  Beware the return value: returning false is usually the right
     * thing to do when the operation succeeded, to indicate to the drone that it shouldn't keep trying this, but
     * instead move on to the next progwidget.
     *
     * @param pos the block pos to work on
     * @param squareDistToBlock squared distance from the drone to the block
     * @return false if we're done and should stop trying, true to try again next time
     */
    protected abstract boolean doBlockInteraction(BlockPos pos, double squareDistToBlock);

    /**
     * Returns whether an in-progress EntityAIBase should continue executing
     */
    @Override
    public boolean shouldContinueExecuting() {
        if (aborted) return false;
        if (searching) {
            if (!sorter.isDone()) return true; // wait until the area is sorted according to the given ordering

            boolean firstRun = true;
            int searchedBlocks = 0; // tracks the number of inspected blocks; stop searching when MAX_LOOKUPS_PER_SEARCH is reached
            while (curPos == null && curY != lastSuccessfulY && order != Ordering.CLOSEST || firstRun) {
                firstRun = false;
                List<BlockPos> inspectedPositions = new ArrayList<>();
                while (!shouldAbort() && searchIndex < area.size()) {
                    BlockPos pos = area.get(searchIndex);
                    searchIndex++;
                    if (isYValid(pos.getY()) && !blacklist.contains(pos) && (!respectClaims() || !DroneClaimManager.getInstance(drone.world()).isClaimed(pos))) {
                        if (!drone.getDebugger().getDebuggingPlayers().isEmpty()) inspectedPositions.add(pos);
                        if (isValidPosition(pos)) {
                            curPos = pos;
                            if (moveToPositions()) {
                                if (tryMoveToBlock(pos)) {
                                    return true;
                                }
                                if (drone.getPathNavigator().isGoingToTeleport()) {
                                    return movedToBlockOK(pos);
                                } else {
                                    drone.getDebugger().addEntry("pneumaticcraft.gui.progWidget.general.debug.cantNavigate", pos);
                                }
                            } else {
                                searching = false;
                                totalActions++;
                                return true;
                            }
                        }
                        searchedBlocks++;
                    }
                    if (searchedBlocks >= MAX_LOOKUPS_PER_SEARCH) {
                        indicateToListeningPlayers(inspectedPositions);
                        return true;
                    }
                }
                indicateToListeningPlayers(inspectedPositions);
                if (curPos == null) updateY();
            }
            if (!shouldAbort()) addEndingDebugEntry();
            return false;
        } else {
            // found a block to interact with; we're now either moving to it, or we've arrived there
            // curPos *should* always be non-null here, but just to be defensive...
            if (curPos != null) {
                if (respectClaims()) {
                    DroneClaimManager.getInstance(drone.world()).claim(curPos);
                }
                double distSq = drone.getDronePos().squareDistanceTo(Vector3d.copyCentered(curPos));
                if (!moveToPositions() || distSq < (moveIntoBlock() ? 1 : 4)) {  // 1 or 2 blocks
                    return doBlockInteraction(curPos, distSq);
                }
            }
            // if we end up here, we're either still travelling (return true) or have nowhere to go (return false)
            return !drone.getPathNavigator().hasNoPath();
        }
    }

    private boolean tryMoveToBlock(BlockPos pos) {
        if (moveIntoBlock()) {
            if (blockAllowsMovement(worldCache, curPos, worldCache.getBlockState(pos))
                    && drone.getPathNavigator().moveToXYZ(curPos.getX(), curPos.getY() + 0.5, curPos.getZ())) {
                return movedToBlockOK(pos);
            }
        } else {
            ISidedWidget w = progWidget instanceof ISidedWidget ? (ISidedWidget) progWidget : null;
            for (Direction dir : DirectionUtil.VALUES) {
                BlockPos pos2 = curPos.offset(dir);
                if (drone.getDronePos().squareDistanceTo(pos2.getX() + 0.5, pos2.getY() + 0.5, pos2.getZ() + 0.5) < 0.5) {
                    // consider that close enough already
                    return movedToBlockOK(pos);
                }
                if ((w == null || w.isSideSelected(dir))
                        && blockAllowsMovement(worldCache, pos2, worldCache.getBlockState(pos2))
                        && drone.getPathNavigator().moveToXYZ(pos2.getX(), pos2.getY() + 0.5, pos2.getZ())) {
                    return movedToBlockOK(pos);
                }
            }
        }
        return false;
    }

    private boolean blockAllowsMovement(ICollisionReader world, BlockPos pos, BlockState state) {
        return state.getBlock() instanceof FlowingFluidBlock ?
                drone.canMoveIntoFluid(((FlowingFluidBlock) state.getBlock()).getFluid()) :
                world.getBlockState(pos).allowsMovement(world, pos, PathType.AIR);
    }

    private boolean movedToBlockOK(BlockPos pos) {
        searching = false;
        totalActions++;
        if (respectClaims()) DroneClaimManager.getInstance(drone.world()).claim(pos);
        blacklist.clear(); //clear the list for next time (maybe the blocks/rights have changed by the time there will be dug again).
        return true;
    }

    protected void addEndingDebugEntry() {
        drone.getDebugger().addEntry("pneumaticcraft.gui.progWidget.blockInteraction.debug.noBlocksValid");
    }

    protected boolean respectClaims() {
        return false;
    }

    protected boolean moveIntoBlock() {
        return false;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean shouldAbort() {
        return aborted;
    }

    public void abort() {
        aborted = true;
    }

    protected boolean moveToPositions() {
        return true;
    }

    /**
     * Sends particle spawn packets to any close player that has a charged pneumatic helmet with entity tracker enabled
     * and dispenser upgrade installed.
     *
     * @param pos the blockpos to indicate
     */
    private void indicateToListeningPlayers(List<BlockPos> pos) {
        if (!pos.isEmpty()) {
            for (ServerPlayerEntity player : drone.getDebugger().getDebuggingPlayers()) {
                if (player.getDistanceSq(pos.get(0).getX(), pos.get(0).getY(), pos.get(0).getZ()) < DRONE_DEBUG_PARTICLE_RANGE_SQ) {
                    CommonArmorHandler handler = CommonArmorHandler.getHandlerForPlayer(player);
                    if (handler.isArmorReady(EquipmentSlotType.HEAD) && handler.isEntityTrackerEnabled()
                            && handler.getUpgradeCount(EquipmentSlotType.HEAD, EnumUpgrade.ENTITY_TRACKER) > 0
                            && handler.getUpgradeCount(EquipmentSlotType.HEAD, EnumUpgrade.DISPENSER) > 0) {
                        NetworkHandler.sendToPlayer(new PacketSpawnIndicatorParticles(pos, progWidget.getColor()), player);
                    }
                }
            }
        }
    }

    void addToBlacklist(BlockPos coord) {
        blacklist.add(coord);
        drone.sendWireframeToClient(coord);
    }

}
