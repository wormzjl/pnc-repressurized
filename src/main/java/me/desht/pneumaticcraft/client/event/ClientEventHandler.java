package me.desht.pneumaticcraft.client.event;

import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.IVertexBuilder;
import me.desht.pneumaticcraft.api.client.IFOVModifierItem;
import me.desht.pneumaticcraft.api.item.EnumUpgrade;
import me.desht.pneumaticcraft.api.item.ICustomDurabilityBar;
import me.desht.pneumaticcraft.client.gui.GuiPneumaticContainerBase;
import me.desht.pneumaticcraft.client.gui.GuiPneumaticScreenBase;
import me.desht.pneumaticcraft.client.gui.IExtraGuiHandling;
import me.desht.pneumaticcraft.client.gui.pneumatic_armor.GuiArmorMainScreen;
import me.desht.pneumaticcraft.client.gui.widget.IDrawAfterRender;
import me.desht.pneumaticcraft.client.util.ClientUtils;
import me.desht.pneumaticcraft.client.util.GuiUtils;
import me.desht.pneumaticcraft.client.util.RenderUtils;
import me.desht.pneumaticcraft.common.config.PNCConfig;
import me.desht.pneumaticcraft.common.core.ModItems;
import me.desht.pneumaticcraft.common.event.DateEventHandler;
import me.desht.pneumaticcraft.common.item.IShiftScrollable;
import me.desht.pneumaticcraft.common.item.ItemJackHammer;
import me.desht.pneumaticcraft.common.item.ItemMinigun;
import me.desht.pneumaticcraft.common.item.ItemPneumaticArmor;
import me.desht.pneumaticcraft.common.minigun.Minigun;
import me.desht.pneumaticcraft.common.network.NetworkHandler;
import me.desht.pneumaticcraft.common.network.PacketJetBootsActivate;
import me.desht.pneumaticcraft.common.network.PacketShiftScrollWheel;
import me.desht.pneumaticcraft.common.pneumatic_armor.ArmorUpgradeRegistry;
import me.desht.pneumaticcraft.common.pneumatic_armor.CommonArmorHandler;
import me.desht.pneumaticcraft.common.pneumatic_armor.JetBootsStateTracker;
import me.desht.pneumaticcraft.lib.Names;
import me.desht.pneumaticcraft.lib.Textures;
import net.minecraft.client.GameSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.IGuiEventListener;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.entity.BipedRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.model.BipedModel;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.HandSide;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.*;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.opengl.GL11;

import java.util.Random;

@Mod.EventBusSubscriber(modid = Names.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ClientEventHandler {
//    private static final double MINIGUN_RADIUS = 1.1D;
    private static final float MINIGUN_TEXT_SIZE = 0.55f;
    private static final float MAX_SCREEN_ROLL = 25F;  // max roll in degrees when flying with jetboots

    private static float currentScreenRoll = 0F;

    @SubscribeEvent
    public static void onLivingRender(RenderLivingEvent.Pre<?,?> event) {
        setRenderHead(event.getEntity(), false);
    }

    @SubscribeEvent
    public static void onLivingRender(RenderLivingEvent.Post<?,?> event) {
        setRenderHead(event.getEntity(), true);
    }

    private static void setRenderHead(LivingEntity entity, boolean setRender) {
        if (entity.getItemStackFromSlot(EquipmentSlotType.HEAD).getItem() == ModItems.PNEUMATIC_HELMET.get()
                && (PNCConfig.Client.Armor.fancyArmorModels || DateEventHandler.isIronManEvent())) {
            EntityRenderer<?> renderer = Minecraft.getInstance().getRenderManager().getRenderer(entity);
            if (renderer instanceof BipedRenderer) {
                BipedModel<?> modelBiped = ((BipedRenderer<?,?>) renderer).getEntityModel();
                modelBiped.bipedHead.showModel = setRender;
            }
        }
    }

    /* TODO 1.8 @SubscribeEvent
      public void onPlayerRender(RenderPlayerEvent.Pre event){
          playerRenderPartialTick = event.partialRenderTick;
          if(!Config.useHelmetModel && !DateEventHandler.isIronManEvent() || event.entityPlayer.getItemStackFromSlot(EntityEquipmentSlot.HEAD) == null || event.entityPlayer.getItemStackFromSlot(EntityEquipmentSlot.HEAD).getItem() != Itemss.pneumaticHelmet) return;
          event.renderer.modelBipedMain.bipedHead.showModel = false;
      }

      @SubscribeEvent
      public void onPlayerRender(RenderPlayerEvent.Post event){
          event.renderer.modelBipedMain.bipedHead.showModel = true;
      }*/

//    @SubscribeEvent
//    public static void tickEnd(TickEvent.RenderTickEvent event) {
//        if (event.phase == TickEvent.Phase.END && Minecraft.getInstance().isGameFocused()
//                && ClientUtils.getClientPlayer() != null
//                && (ModuleRegulatorTube.inverted || !ModuleRegulatorTube.inLine)) {
//            Minecraft mc = Minecraft.getInstance();
//            MainWindow mw = mc.getMainWindow();
//            FontRenderer fontRenderer = Minecraft.getInstance().fontRenderer;
//            String warning = TextFormatting.RED + I18n.format("pneumaticcraft.gui.regulatorTube.hudMessage." + (ModuleRegulatorTube.inverted ? "inverted" : "notInLine"));
//            fontRenderer.drawStringWithShadow(warning, mw.getScaledWidth() / 2f - fontRenderer.getStringWidth(warning) / 2f, mw.getScaledHeight() / 2f + 30, 0xFFFFFFFF);
//        }
//    }

    @SubscribeEvent
    public static void onGuiOverlay(RenderGameOverlayEvent.Pre event) {
        // gameSettings.getPointOfView().isFirstPerson(), or some such
        if (event.getType() == RenderGameOverlayEvent.ElementType.CROSSHAIRS && Minecraft.getInstance().gameSettings.getPointOfView().func_243192_a()) {
            PlayerEntity player = Minecraft.getInstance().player;
            if (player == null) return;
            ItemStack stack = player.getHeldItemMainhand();
            if (stack.getItem() instanceof ItemMinigun) {
                renderFirstPersonMinigunTraces(event, player, stack);
            } else if (stack.getItem() instanceof ItemJackHammer) {
                renderJackHamerOverlay(event, player, stack);
            }
        }
    }

    private static void renderJackHamerOverlay(RenderGameOverlayEvent.Pre event, PlayerEntity player, ItemStack heldStack) {
        Minecraft mc = Minecraft.getInstance();
        MatrixStack matrixStack = event.getMatrixStack();
        int w = event.getWindow().getScaledWidth();
        int h = event.getWindow().getScaledHeight();

        long timedelta = player.world.getGameTime() - ItemJackHammer.getLastModeSwitchTime();
        ItemJackHammer.DigMode digMode = ItemJackHammer.getDigMode(heldStack);
        if (digMode != null && (digMode.atLeast(ItemJackHammer.DigMode.MODE_1X2) || timedelta < 30 || player.isCrouching())) {
            mc.getTextureManager().bindTexture(digMode.getGuiIcon());
            RenderSystem.enableBlend();
            RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            RenderSystem.color4f(1f, 1f, 1f, 0.25f);
            AbstractGui.blit(matrixStack, w / 2 + 7, h / 2 - 7, 0, 0, 16, 16, 16, 16);
        }
    }

    private static void renderFirstPersonMinigunTraces(RenderGameOverlayEvent.Pre event, PlayerEntity player, ItemStack heldStack) {
        Minecraft mc = Minecraft.getInstance();
        Minigun minigun = ((ItemMinigun) heldStack.getItem()).getMinigun(heldStack, player);
        int w = event.getWindow().getScaledWidth();
        int h = event.getWindow().getScaledHeight();

        if (minigun.isMinigunActivated() && minigun.getMinigunSpeed() == Minigun.MAX_GUN_SPEED) {
            drawBulletTraces2D(minigun.getAmmoColor() | 0x40000000, w, h);
        }

        MatrixStack matrixStack = event.getMatrixStack();
        ItemStack ammo = minigun.getAmmoStack();
        if (!ammo.isEmpty()) {
            GuiUtils.renderItemStack(matrixStack, ammo,w / 2 + 16, h / 2 - 7);
            int remaining = ammo.getMaxDamage() - ammo.getDamage();
            matrixStack.push();
            matrixStack.translate(w / 2f + 32, h / 2f - 1, 0);
            matrixStack.scale(MINIGUN_TEXT_SIZE, MINIGUN_TEXT_SIZE, 1f);
            String text = remaining + "/" + ammo.getMaxDamage();
            mc.fontRenderer.drawString(matrixStack, text, 1, 0, 0);
            mc.fontRenderer.drawString(matrixStack, text, -1, 0, 0);
            mc.fontRenderer.drawString(matrixStack, text, 0, 1, 0);
            mc.fontRenderer.drawString(matrixStack, text, 0, -1, 0);
            mc.fontRenderer.drawString(matrixStack, text, 0, 0, minigun.getAmmoColor());
            matrixStack.pop();
        }
        mc.getTextureManager().bindTexture(Textures.MINIGUN_CROSSHAIR);
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderSystem.color4f(0.2f, 1.0f, 0.2f, 0.6f);
        AbstractGui.blit(matrixStack, w / 2 - 7, h / 2 - 7, 0, 0, 16, 16, 16, 16);
        event.setCanceled(true);
    }

    private static void drawBulletTraces2D(int color, int w, int h) {
        RenderSystem.pushMatrix();
        RenderSystem.disableTexture();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_LINE_STIPPLE);

        int x = w / 2;
        int y = h / 2;

        int[] cols = RenderUtils.decomposeColor(color);
        Random rand = Minecraft.getInstance().world.rand;
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        float f = Minecraft.getInstance().gameSettings.mainHand == HandSide.RIGHT ? 0.665F : 0.335F;
        float endX = w * f;
        float endY = h * 0.685F;
        for (int i = 0; i < 5; i++) {
            int stipple = 0xFFFF & ~(3 << rand.nextInt(16));
            GL11.glLineStipple(4, (short) stipple);
            bb.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            bb.pos(x + rand.nextInt(12) - 6, y + rand.nextInt(12) - 6, 0).color(cols[1], cols[2], cols[3], cols[0]).endVertex();
            bb.pos(endX, endY, 0).color(cols[1], cols[2], cols[3], cols[0]).endVertex();
            Tessellator.getInstance().draw();
        }
        GL11.glDisable(GL11.GL_LINE_STIPPLE);
        RenderSystem.disableBlend();
        RenderSystem.enableTexture();
        RenderSystem.popMatrix();
    }

    @SubscribeEvent
    public static void renderThirdPersonMinigunTraces(RenderPlayerEvent.Post event) {
        // render everyone else's (and ours, in 3rd person camera) minigun bullet traces
        PlayerEntity player = event.getPlayer();
        if (player == Minecraft.getInstance().player && Minecraft.getInstance().gameSettings.getPointOfView().func_243192_a()) return;

        ItemStack curItem = player.getHeldItemMainhand();
        if (curItem.getItem() == ModItems.MINIGUN.get()) {
            Minigun minigun = ModItems.MINIGUN.get().getMinigun(curItem, player);
            if (minigun.isMinigunActivated() && minigun.getMinigunSpeed() == Minigun.MAX_GUN_SPEED) {
                IVertexBuilder builder = event.getBuffers().getBuffer(RenderType.LINES);
                // FIXME: this just doesn't place the start of the line where it should... why?
                Vector3d startVec = new Vector3d(0, player.getEyeHeight() / 2, 0).add(player.getLookVec());
                Vector3d endVec = startVec.add(player.getLookVec().scale(20));
                int[] cols = RenderUtils.decomposeColor(minigun.getAmmoColor());
                Matrix4f posMat = event.getMatrixStack().getLast().getMatrix();
                for (int i = 0; i < 5; i++) {
                    RenderUtils.posF(builder, posMat, startVec.x, startVec.y, startVec.z)
                            .color(cols[1], cols[2], cols[3], 64)
                            .endVertex();
                    RenderUtils.posF(builder, posMat, endVec.x + player.getRNG().nextDouble() - 0.5, endVec.y + player.getRNG().nextDouble() - 0.5, endVec.z + player.getRNG().nextDouble() - 0.5)
                            .color(cols[1], cols[2], cols[3], 64)
                            .endVertex();
                }
            }
        }
    }

    @SubscribeEvent
    public static void screenTilt(EntityViewRenderEvent.CameraSetup event) {
        if (event.getInfo().getRenderViewEntity() instanceof PlayerEntity) {
            PlayerEntity player = (PlayerEntity) event.getInfo().getRenderViewEntity();
            if (ItemPneumaticArmor.isPneumaticArmorPiece(player, EquipmentSlotType.FEET) && !player.isOnGround()) {
                CommonArmorHandler handler = CommonArmorHandler.getHandlerForPlayer(player);
                float targetRoll;
                float div = 50F;
                if (handler.isJetBootsActive() && !handler.isJetBootsBuilderMode()) {
                    float roll = player.rotationYawHead - player.prevRotationYawHead;
                    if (Math.abs(roll) < 0.0001) {
                        targetRoll = 0F;
                    } else {
                        targetRoll = Math.signum(roll) * MAX_SCREEN_ROLL;
                        div = Math.abs(400F / roll);
                    }
                } else {
                    targetRoll = 0F;
                }
                currentScreenRoll += (targetRoll - currentScreenRoll) / div;
                event.setRoll(currentScreenRoll);
            } else {
                currentScreenRoll = 0F;
            }
        }
    }

    @SubscribeEvent
    public static void jetBootsEvent(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.START) {
            PlayerEntity player = event.player;
            if (player == null || player.world == null || !player.world.isRemote) return;
            CommonArmorHandler handler = CommonArmorHandler.getHandlerForPlayer(player);
            if (handler.upgradeUsable(ArmorUpgradeRegistry.getInstance().jetBootsHandler, false)) {
                GameSettings settings = Minecraft.getInstance().gameSettings;
                if (handler.isJetBootsActive() && (!handler.isJetBootsEnabled() || !settings.keyBindJump.isKeyDown())) {
                    NetworkHandler.sendToServer(new PacketJetBootsActivate(false));
                    handler.setJetBootsActive(false);
                } else if (!handler.isJetBootsActive() && handler.isJetBootsEnabled() && settings.keyBindJump.isKeyDown()) {
                    NetworkHandler.sendToServer(new PacketJetBootsActivate(true));
                    handler.setJetBootsActive(true);
                }
            }
        }
    }

    @SubscribeEvent
    public static void playerPreRotateEvent(RenderPlayerEvent.Pre event) {
        PlayerEntity player = event.getPlayer();
        if (!player.isElytraFlying()) {
            JetBootsStateTracker.JetBootsState state = JetBootsStateTracker.getClientTracker().getJetBootsState(player);
            if (state != null && state.shouldRotatePlayer()) {
                player.limbSwing = player.limbSwingAmount = 0F;
            }
        }
    }

    @SubscribeEvent
    public static void adjustFOVEvent(FOVUpdateEvent event) {
        float modifier = 1.0f;
        for (EquipmentSlotType slot : EquipmentSlotType.values()) {
            ItemStack stack = event.getEntity().getItemStackFromSlot(slot);
            if (stack.getItem() instanceof IFOVModifierItem) {
                modifier *= ((IFOVModifierItem) stack.getItem()).getFOVModifier(stack, event.getEntity(), slot);
            }
        }

        event.setNewfov(event.getNewfov() * modifier);
    }

    @SubscribeEvent
    public static void fogDensityEvent(EntityViewRenderEvent.FogDensity event) {
        if (event.getInfo().getFluidState().isTagged(FluidTags.WATER) && event.getInfo().getRenderViewEntity() instanceof PlayerEntity) {
            CommonArmorHandler handler = CommonArmorHandler.getHandlerForPlayer();
            if (handler.isArmorReady(EquipmentSlotType.HEAD) && handler.isScubaEnabled()
                    && handler.getUpgradeCount(EquipmentSlotType.HEAD, EnumUpgrade.SCUBA) > 0) {
                event.setDensity(0.02f);
                event.setCanceled(true);
            }
        }
    }

    private static final int Z_LEVEL = 233;  // should be just above the drawn itemstack

    @SubscribeEvent
    public static void guiContainerForeground(GuiContainerEvent.DrawForeground event) {
        // general extra rendering
        if (Minecraft.getInstance().currentScreen instanceof IExtraGuiHandling) {
            ((IExtraGuiHandling) Minecraft.getInstance().currentScreen).drawExtras(event);
        }

        // custom durability bars
        RenderSystem.disableTexture();
        BufferBuilder bb = Tessellator.getInstance().getBuffer();
        ContainerScreen<?> container = event.getGuiContainer();
        MatrixStack matrixStack = event.getMatrixStack();
        for (Slot s : container.getContainer().inventorySlots) {
            if (s.getStack().getItem() instanceof ICustomDurabilityBar) {
                ICustomDurabilityBar custom = (ICustomDurabilityBar) s.getStack().getItem();
                if (custom.shouldShowCustomDurabilityBar(s.getStack())) {
                    int x = s.xPos;
                    int y = s.yPos;
                    float width = custom.getCustomDurability(s.getStack()) * 13;
                    int[] cols = RenderUtils.decomposeColor(custom.getCustomDurabilityColour(s.getStack()));
                    int yOff = custom.isShowingOtherBar(s.getStack()) ? 0 : 1;
                    if (yOff == 1) {
                        GuiUtils.drawUntexturedQuad(matrixStack, bb, x + 2, y + 14, Z_LEVEL, width, 1, 40, 40, 40, 255);
                    }
                    GuiUtils.drawUntexturedQuad(matrixStack, bb, x + 2, y + 12 + yOff, Z_LEVEL, 13, 1, 0, 0, 0, 255);
                    GuiUtils.drawUntexturedQuad(matrixStack, bb, x + 2, y + 12 + yOff, Z_LEVEL, width, 1, cols[1], cols[2], cols[3], 255);
                }
            }
        }
        RenderSystem.enableTexture();
    }

    @SubscribeEvent
    public static void onGuiDrawPost(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (event.getGui() instanceof GuiPneumaticContainerBase || event.getGui() instanceof GuiPneumaticScreenBase) {
            for (IGuiEventListener l : event.getGui().getEventListeners()) {
                if (l instanceof IDrawAfterRender) {
                    ((IDrawAfterRender) l).renderAfterEverythingElse(event.getMatrixStack(), event.getMouseX(), event.getMouseY(), event.getRenderPartialTicks());
                }
            }
        }
    }

    @SubscribeEvent
    public static void onShiftScroll(InputEvent.MouseScrollEvent event) {
        if (ClientUtils.getClientPlayer().isCrouching()) {
            if (!tryHand(event, Hand.MAIN_HAND)) tryHand(event, Hand.OFF_HAND);
        }
    }

    private static boolean tryHand(InputEvent.MouseScrollEvent event, Hand hand) {
        ItemStack stack = ClientUtils.getClientPlayer().getHeldItem(hand);
        if (stack.getItem() instanceof IShiftScrollable) {
            NetworkHandler.sendToServer(new PacketShiftScrollWheel(event.getScrollDelta() > 0, Hand.MAIN_HAND));
            ((IShiftScrollable) stack.getItem()).onShiftScrolled(ClientUtils.getClientPlayer(), event.getScrollDelta() > 0, Hand.MAIN_HAND);
            event.setCanceled(true);
            return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onClientConnect(ClientPlayerNetworkEvent.LoggedInEvent event) {
        GuiArmorMainScreen.initHelmetCoreComponents();
    }
}

