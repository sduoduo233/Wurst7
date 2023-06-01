package net.wurstclient.hacks;

import net.minecraft.block.BlockState;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.wurstclient.Category;
import net.wurstclient.events.*;
import net.wurstclient.hack.Hack;
import net.wurstclient.mixinterface.ISimpleOption;
import net.wurstclient.settings.BlockListSetting;
import net.wurstclient.settings.SliderSetting;
import net.wurstclient.util.BlockUtils;

import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;

public class AntiAntiXrayHack extends Hack implements UpdateListener, PacketInputListener, PacketOutputListener, GetAmbientOcclusionLightLevelListener,
        ShouldDrawSideListener, TesselateBlockListener, RenderBlockEntityListener, GUIRenderListener {

    private final BlockListSetting ores = new BlockListSetting("Ores", "",
            "minecraft:ancient_debris", "minecraft:bone_block", "minecraft:coal_block",
            "minecraft:coal_ore", "minecraft:copper_ore", "minecraft:deepslate_coal_ore",
            "minecraft:deepslate_copper_ore", "minecraft:deepslate_diamond_ore",
            "minecraft:deepslate_emerald_ore", "minecraft:deepslate_gold_ore",
            "minecraft:deepslate_iron_ore", "minecraft:deepslate_lapis_ore",
            "minecraft:deepslate_redstone_ore", "minecraft:diamond_block",
            "minecraft:diamond_ore", "minecraft:dispenser", "minecraft:dropper",
            "minecraft:emerald_block", "minecraft:emerald_ore", "minecraft:gold_block",
            "minecraft:gold_ore", "minecraft:hopper", "minecraft:iron_block",
            "minecraft:iron_ore", "minecraft:nether_gold_ore", "minecraft:nether_quartz_ore",
            "minecraft:raw_copper_block", "minecraft:raw_gold_block",
            "minecraft:raw_iron_block", "minecraft:redstone_block",
            "minecraft:redstone_ore"
    );
    private final SliderSetting radius = new SliderSetting("Radius", 3, 1, 20, 1, SliderSetting.ValueDisplay.INTEGER);
    private final SliderSetting packetsPerTick = new SliderSetting("Packets Per Tick", 1, 1, 20, 1, SliderSetting.ValueDisplay.INTEGER);

    private ArrayList<String> oreNames;
    private int current = 0;
    private int[][] offsets = new int[0][0];
    private int oldRadius = -1;

    private final ArrayList<BlockPos> renderList = new ArrayList<>();

    public AntiAntiXrayHack() {
        super("AntiAntiXray");

        setCategory(Category.RENDER);
        addSetting(ores);
        addSetting(radius);
        addSetting(packetsPerTick);

    }


    @Override
    protected void onEnable() {
        super.onEnable();

        current = 0;
        oreNames = new ArrayList<>(ores.getBlockNames());

        EVENTS.add(UpdateListener.class, this);
        EVENTS.add(PacketInputListener.class, this);
        EVENTS.add(PacketOutputListener.class, this);
        EVENTS.add(GetAmbientOcclusionLightLevelListener.class, this);
        EVENTS.add(ShouldDrawSideListener.class, this);
        EVENTS.add(TesselateBlockListener.class, this);
        EVENTS.add(RenderBlockEntityListener.class, this);
        EVENTS.add(GUIRenderListener.class, this);

        MC.worldRenderer.reload();
    }

    @Override
    protected void onDisable() {
        super.onDisable();

        if (renderList != null) renderList.clear();

        EVENTS.remove(UpdateListener.class, this);
        EVENTS.remove(PacketInputListener.class, this);
        EVENTS.remove(PacketOutputListener.class, this);
        EVENTS.remove(GetAmbientOcclusionLightLevelListener.class, this);
        EVENTS.remove(ShouldDrawSideListener.class, this);
        EVENTS.remove(TesselateBlockListener.class, this);
        EVENTS.remove(RenderBlockEntityListener.class, this);
        EVENTS.remove(GUIRenderListener.class, this);

        ISimpleOption<Double> gammaOption =
                (ISimpleOption<Double>) (Object) MC.options.getGamma();
        if (!WURST.getHax().fullbrightHack.isEnabled())
            gammaOption.forceSetValue(0.5);

        MC.worldRenderer.reload();

    }

    @Override
    public void onReceivedPacket(PacketInputEvent event) {
        if (event.getPacket() instanceof BlockUpdateS2CPacket) {
            BlockPos pos = ((BlockUpdateS2CPacket) event.getPacket()).getPos();
            BlockState state = ((BlockUpdateS2CPacket) event.getPacket()).getState();

            String name = BlockUtils.getName(state.getBlock());
            int index = Collections.binarySearch(oreNames, name);

            if (index >= 0) {
                for (BlockPos blockPos : renderList) {
                    if (pos.equals(blockPos)) {
                        return;
                    }
                }
                renderList.add(pos);
            }
        }
    }

    @Override
    public void onSentPacket(PacketOutputEvent event) {

    }

    @Override
    public void onUpdate() {
        ISimpleOption<Double> gammaOption =
                (ISimpleOption<Double>) (Object) MC.options.getGamma();

        gammaOption.forceSetValue(16.0);

        int r = radius.getValueCeil();

        // update offsets
        if (oldRadius != r) {
            offsets = new int[(r * 2 + 1) * (r * 2 + 1) * (r * 2 + 1)][3];
            int n = 0;
            for (int i = -r; i <= r; i++) {
                for (int j = -r; j <= r; j++) {
                    for (int k = -r; k <= r; k++) {
                        offsets[n][0] = i;
                        offsets[n][1] = j;
                        offsets[n][2] = k;
                        n++;
                    }
                }
            }
            oldRadius = r;
            current = 0;
        }

        // send block destroy packets to surrounding blocks
        for (int i = 0; i < packetsPerTick.getValueCeil(); i++) {
            MC.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                    PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
                    MC.player.getBlockPos().add(offsets[current][0], offsets[current][1], offsets[current][2]),
                    Direction.UP,
                    0
            ));
            current++;
            if (current >= offsets.length) current = 0;
        }

    }

    @Override
    public void onShouldDrawSide(ShouldDrawSideEvent event) {

        String name = BlockUtils.getName(event.getState().getBlock());
        int index = Collections.binarySearch(oreNames, name);

        event.setRendered(index >= 0);
    }

    @Override
    public void onGetAmbientOcclusionLightLevel(GetAmbientOcclusionLightLevelEvent event) {
        event.setLightLevel(1);
    }

    @Override
    public void onRenderBlockEntity(RenderBlockEntityEvent event) {

    }

    @Override
    public void onTesselateBlock(TesselateBlockEvent event) {
        for (BlockPos blockPos : renderList) {
            if (event.getPos().equals(blockPos)) {
                return;
            }
        }
        event.cancel();
    }

    @Override
    public void onRenderGUI(MatrixStack matrixStack, float partialTicks) {
        TextRenderer textRenderer = MC.textRenderer;

        int width = MC.getWindow().getScaledWidth();
        int height = MC.getWindow().getScaledHeight();
        String text = String.format("AntiAntiXray: %d / %d", current, offsets.length);
        textRenderer.drawWithShadow(matrixStack, text, width / 2f - (textRenderer.getWidth(text) / 2f), height / 4f * 3f, Color.WHITE.getRGB());
    }
}
