package io.th0rgal.oraxen.utils.breaker;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import io.th0rgal.oraxen.OraxenPlugin;
import io.th0rgal.oraxen.api.OraxenBlocks;
import io.th0rgal.oraxen.api.OraxenFurniture;
import io.th0rgal.oraxen.api.events.furniture.OraxenFurnitureDamageEvent;
import io.th0rgal.oraxen.api.events.noteblock.OraxenNoteBlockDamageEvent;
import io.th0rgal.oraxen.api.events.stringblock.OraxenStringBlockDamageEvent;
import io.th0rgal.oraxen.mechanics.provided.gameplay.block.BlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.furniture.FurnitureMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.noteblock.NoteBlockMechanic;
import io.th0rgal.oraxen.mechanics.provided.gameplay.stringblock.StringBlockMechanic;
import io.th0rgal.oraxen.utils.BlockHelpers;
import io.th0rgal.oraxen.utils.EventUtils;
import io.th0rgal.oraxen.utils.ItemUtils;
import io.th0rgal.oraxen.utils.PotionUtils;
import io.th0rgal.oraxen.utils.blocksounds.BlockSounds;
import io.th0rgal.oraxen.utils.drops.Drop;
import io.th0rgal.oraxen.utils.wrappers.EnchantmentWrapper;
import io.th0rgal.protectionlib.ProtectionLib;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.tjdev.util.tjpluginutil.spigot.FoliaUtil;
import org.tjdev.util.tjpluginutil.spigot.scheduler.universalscheduler.scheduling.tasks.MyScheduledTask;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static io.th0rgal.oraxen.mechanics.provided.gameplay.block.BlockMechanicFactory.getBlockMechanic;
import static org.tjdev.util.tjpluginutil.spigot.object.task.Scope.Sync;

public class BreakerSystem {

    public static final List<HardnessModifier> MODIFIERS = new ArrayList<>();
    private final Set<Location> breakerPerLocation = new HashSet<>();
    private final Map<Location, MyScheduledTask> breakerPlaySound = new HashMap<>();
    private final PacketAdapter listener = new PacketAdapter(OraxenPlugin.get(),
            ListenerPriority.LOW, PacketType.Play.Client.BLOCK_DIG
    ) {
        @Override
        public void onPacketReceiving(final PacketEvent event) {
            final PacketContainer packet = event.getPacket();
            final Player player = event.getPlayer();
            final ItemStack item = player.getInventory().getItemInMainHand();
            if (player.getGameMode() == GameMode.CREATIVE) return;

            event.setCancelled(true);
            Runnable r = () -> ProtocolLibrary.getProtocolManager().receiveClientPacket(player, packet, false);
            Sync(player, () -> {
                final StructureModifier<BlockPosition> dataTemp = packet.getBlockPositionModifier();
                final StructureModifier<EnumWrappers.Direction> dataDirection = packet.getDirections();
                final StructureModifier<EnumWrappers.PlayerDigType> data = packet
                        .getEnumModifier(EnumWrappers.PlayerDigType.class, 2);
                EnumWrappers.PlayerDigType type;
                try {
                    type = data.getValues().getFirst();
                } catch (IllegalArgumentException exception) {
                    type = EnumWrappers.PlayerDigType.SWAP_HELD_ITEMS;
                }

                final BlockPosition pos = dataTemp.getValues().getFirst();
                final World world = player.getWorld();
                final Block block = world.getBlockAt(pos.getX(), pos.getY(), pos.getZ());
                final Location location = block.getLocation();
                final BlockFace blockFace = dataDirection.size() > 0 ?
                                            BlockFace.valueOf(dataDirection.read(0).name()) :
                                            BlockFace.UP;

                HardnessModifier triggeredModifier = null;
                for (final HardnessModifier modifier : MODIFIERS) {
                    if (modifier.isTriggered(player, block, item)) {
                        triggeredModifier = modifier;
                        break;
                    }
                }
                if (triggeredModifier == null) {
                    r.run();
                    return;
                }
                final long period = triggeredModifier.getPeriod(player, block, item);
                if (period == 0) {
                    r.run();
                    return;
                }

                NoteBlockMechanic noteMechanic = OraxenBlocks.getNoteBlockMechanic(block);
                StringBlockMechanic stringMechanic = OraxenBlocks.getStringMechanic(block);
                FurnitureMechanic furnitureMechanic = OraxenFurniture.getFurnitureMechanic(block);
                if (block.getType() == Material.NOTE_BLOCK && noteMechanic == null ||
                    block.getType() == Material.TRIPWIRE && stringMechanic == null ||
                    block.getType() == Material.BARRIER && furnitureMechanic == null) {
                    r.run();
                    return;
                }

                if (type == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                    // Get these when block is started being broken to minimize checks & allow for proper damage checks later
                    final Drop drop;
                    if (furnitureMechanic != null)
                        drop = furnitureMechanic.getDrop() != null ? furnitureMechanic.getDrop() : Drop.emptyDrop();
                    else if (noteMechanic != null)
                        drop = noteMechanic.getDrop() != null ? noteMechanic.getDrop() : Drop.emptyDrop();
                    else if (stringMechanic != null)
                        drop = stringMechanic.getDrop() != null ? stringMechanic.getDrop() : Drop.emptyDrop();
                    else drop = null;

                    FoliaUtil.scheduler.runTask(player, () ->
                            player.addPotionEffect(new PotionEffect(PotionUtils.getEffectType("mining_fatigue"),
                                    (int) (period * 11),
                                    Integer.MAX_VALUE,
                                    false, false, false
                            )));

                    if (breakerPerLocation.contains(location))
                        FoliaUtil.scheduler.cancelTasks(OraxenPlugin.get());

                    // Cancellation state is being ignored.
                    // However still needs to be called for plugin support.
                    final PlayerInteractEvent playerInteractEvent =
                            new PlayerInteractEvent(
                                    player,
                                    Action.LEFT_CLICK_BLOCK,
                                    player.getInventory().getItemInMainHand(),
                                    block,
                                    blockFace,
                                    EquipmentSlot.HAND
                            );
                    FoliaUtil.scheduler.runTask(player, () -> Bukkit.getPluginManager().callEvent(playerInteractEvent));

                    breakerPerLocation.add(location);

                    // If the relevant damage event is cancelled, return
                    if (blockDamageEventCancelled(block, player)) {
                        stopBlockBreaker(location);
                        return;
                    }

                    // Methods for sending multi-barrier block-breaks
                    final List<Location> furnitureBarrierLocations = furnitureBarrierLocations(
                            furnitureMechanic,
                            block
                    );
                    final HardnessModifier modifier = triggeredModifier;
                    startBlockHitSound(location);

                    final int[] value = {0};
                    MyScheduledTask[] task = new MyScheduledTask[1];
                    task[0] = FoliaUtil.scheduler.runTaskTimer(location, () -> {
                        if (!breakerPerLocation.contains(location)) {
                            task[0].cancel();
                            stopBlockHitSound(location);
                            return;
                        }

                        if (item.getEnchantmentLevel(EnchantmentWrapper.EFFICIENCY) >= 5)
                            value[0] = 10;

                        for (Player onlinePlayer : Bukkit.getOnlinePlayers())
                            if (onlinePlayer.getLocation().getWorld() == world &&
                                onlinePlayer.getLocation().distance(location) <= 16)
                                if (furnitureMechanic != null)
                                    for (Location barrierLoc : furnitureBarrierLocations)
                                        sendBlockBreak(onlinePlayer, barrierLoc, value[0]);
                                else sendBlockBreak(onlinePlayer, location, value[0]);

                        if (value[0]++ < 10) return;
                        if (EventUtils.callEvent(new BlockBreakEvent(block, player)) && ProtectionLib.canBreak(
                                player,
                                location
                        )) {
                            // Damage item with properties identified earlier
                            ItemUtils.damageItem(player, drop, item);
                            modifier.breakBlock(player, block, item);
                        } else stopBlockHitSound(location);

                        FoliaUtil.scheduler.runTask(player, () ->
                                player.removePotionEffect(PotionUtils.getEffectType("mining_fatigue")));

                        stopBlockBreaker(location);
                        stopBlockHitSound(location);
                        for (Player onlinePlayer : Bukkit.getOnlinePlayers())
                            if (onlinePlayer.getLocation().getWorld() == world &&
                                onlinePlayer.getLocation().distance(location) <= 16) {
                                if (furnitureMechanic != null) for (Location barrierLoc : furnitureBarrierLocations)
                                    sendBlockBreak(onlinePlayer, barrierLoc, value[0]);
                                else sendBlockBreak(onlinePlayer, location, value[0]);
                            }
                        task[0].cancel();
                    }, period, period);
                } else {
                    FoliaUtil.scheduler.runTask(player, () -> {
                        player.removePotionEffect(PotionUtils.getEffectType("mining_fatigue"));
                        if (!ProtectionLib.canBreak(player, location))
                            player.sendBlockChange(location, block.getBlockData());

                        for (Player onlinePlayer : Bukkit.getOnlinePlayers())
                            if (onlinePlayer.getLocation().getWorld() == world &&
                                onlinePlayer.getLocation().distance(location) <= 16)
                                sendBlockBreak(onlinePlayer, location, 10);
                        stopBlockBreaker(location);
                        stopBlockHitSound(location);
                    });
                }
            });
        }
    };

    private List<Location> furnitureBarrierLocations(FurnitureMechanic furnitureMechanic, Block block) {
        if (!breakerPerLocation.contains(block.getLocation())) return List.of(block.getLocation());

        AtomicReference<Entity> furnitureBaseEntity = new AtomicReference<>();
        FoliaUtil.scheduler.runTask(
                block.getLocation(),
                () -> furnitureBaseEntity.set(furnitureMechanic != null ? furnitureMechanic.getBaseEntity(block) : null)
        );
        return furnitureMechanic != null && furnitureBaseEntity.get() != null
               ? furnitureMechanic.getLocations(FurnitureMechanic.getFurnitureYaw(furnitureBaseEntity.get()),
                furnitureBaseEntity.get().getLocation(), furnitureMechanic.getBarriers()
        )
               : Collections.singletonList(block.getLocation());
    }

    private boolean blockDamageEventCancelled(Block block, Player player) {
        if (!breakerPerLocation.contains(block.getLocation())) return false;

        switch (block.getType()) {
            case NOTE_BLOCK -> {
                NoteBlockMechanic mechanic = OraxenBlocks.getNoteBlockMechanic(block);
                if (mechanic == null) return true;
                OraxenNoteBlockDamageEvent event = new OraxenNoteBlockDamageEvent(mechanic, block, player);
                FoliaUtil.scheduler.runTask(() -> Bukkit.getPluginManager().callEvent(event));
                return event.isCancelled();
            }
            case TRIPWIRE -> {
                StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(block);
                if (mechanic == null) return true;
                OraxenStringBlockDamageEvent event = new OraxenStringBlockDamageEvent(mechanic, block, player);
                FoliaUtil.scheduler.runTask(() -> Bukkit.getPluginManager().callEvent(event));
                return event.isCancelled();
            }
            case BARRIER -> {
                try {
                    return FoliaUtil.scheduler.callSyncMethod(() -> {
                        FurnitureMechanic mechanic = OraxenFurniture.getFurnitureMechanic(block);
                        if (mechanic == null) return true;
                        Entity baseEntity = mechanic.getBaseEntity(block);
                        if (baseEntity == null) return true;
                        OraxenFurnitureDamageEvent event = new OraxenFurnitureDamageEvent(
                                mechanic,
                                baseEntity,
                                player,
                                block
                        );
                        FoliaUtil.scheduler.runTask(() -> Bukkit.getPluginManager().callEvent(event));
                        return event.isCancelled();
                    }).get();
                } catch (Exception e) {
                    return false;
                }
            }
            case BEDROCK -> { // For BedrockBreakMechanic
                return false;
            }
            default -> {
                return true;
            }
        }
    }

    private void sendBlockBreak(final Player player, final Location location, final int stage) {
        final PacketContainer packet = ProtocolLibrary.getProtocolManager()
                                                      .createPacket(PacketType.Play.Server.BLOCK_BREAK_ANIMATION);
        packet.getIntegers().write(0, location.hashCode()).write(1, stage);
        packet.getBlockPositionModifier().write(0, new BlockPosition(location.toVector()));

        ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
    }

    private void stopBlockBreaker(Location location) {
        if (breakerPerLocation.contains(location)) {
            FoliaUtil.scheduler.cancelTasks(OraxenPlugin.get());
            breakerPerLocation.remove(location);
        }
    }

    private void startBlockHitSound(Location location) {
        BlockSounds blockSounds = getBlockSounds(location.getBlock());

        if (!breakerPerLocation.contains(location) || blockSounds == null || !blockSounds.hasHitSound()) {
            stopBlockHitSound(location);
            return;
        }

        breakerPlaySound.put(location, FoliaUtil.scheduler.runTaskTimer(location,
                () -> BlockHelpers.playCustomBlockSound(
                        location,
                        getHitSound(location.getBlock()),
                        blockSounds.getHitVolume(),
                        blockSounds.getHitPitch()
                )
                , 0L, 4L
        ));
    }

    private void stopBlockHitSound(Location location) {
        Optional.ofNullable(breakerPlaySound.get(location)).ifPresent(MyScheduledTask::cancel);
        breakerPlaySound.remove(location);
    }

    public void registerListener() {
        ProtocolLibrary.getProtocolManager().addPacketListener(listener);
    }

    private BlockSounds getBlockSounds(Block block) {
        ConfigurationSection soundSection = OraxenPlugin.get()
                                                        .getConfigsManager()
                                                        .getMechanics()
                                                        .getConfigurationSection("custom_block_sounds");
        if (soundSection == null) return null;
        switch (block.getType()) {
            case NOTE_BLOCK -> {
                NoteBlockMechanic mechanic = OraxenBlocks.getNoteBlockMechanic(block);
                if (mechanic == null || !mechanic.hasBlockSounds()) return null;
                if (!soundSection.getBoolean("noteblock_and_block")) return null;
                else return mechanic.getBlockSounds();
            }
            case MUSHROOM_STEM -> {
                BlockMechanic mechanic = getBlockMechanic(block);
                if (mechanic == null || !mechanic.hasBlockSounds()) return null;
                if (!soundSection.getBoolean("noteblock_and_block")) return null;
                else return mechanic.getBlockSounds();
            }
            case TRIPWIRE -> {
                StringBlockMechanic mechanic = OraxenBlocks.getStringMechanic(block);
                if (mechanic == null || !mechanic.hasBlockSounds()) return null;
                if (!soundSection.getBoolean("stringblock_and_furniture")) return null;
                else return mechanic.getBlockSounds();
            }
            case BARRIER -> {
                FurnitureMechanic mechanic = OraxenFurniture.getFurnitureMechanic(block);
                if (mechanic == null || !mechanic.hasBlockSounds()) return null;
                if (!soundSection.getBoolean("stringblock_and_furniture")) return null;
                else return mechanic.getBlockSounds();
            }
            default -> {
                return null;
            }
        }
    }

    private String getHitSound(Block block) {
        ConfigurationSection soundSection = OraxenPlugin.get()
                                                        .getConfigsManager()
                                                        .getMechanics()
                                                        .getConfigurationSection("custom_block_sounds");
        if (soundSection == null) return null;
        BlockSounds sounds = getBlockSounds(block);
        if (sounds == null) return null;
        return switch (block.getType()) {
            case NOTE_BLOCK, MUSHROOM_STEM -> sounds.hasHitSound() ? sounds.getHitSound() : "required.wood.hit";
            case TRIPWIRE -> sounds.hasHitSound() ? sounds.getHitSound() : "block.tripwire.detach";
            case BARRIER -> sounds.hasHitSound() ? sounds.getHitSound() : "required.stone.hit";
            default -> block.getBlockData().getSoundGroup().getHitSound().getKey().toString();
        };
    }
}
