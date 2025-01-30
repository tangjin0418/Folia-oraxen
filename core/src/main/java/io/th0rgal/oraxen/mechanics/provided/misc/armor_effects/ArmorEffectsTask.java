package io.th0rgal.oraxen.mechanics.provided.misc.armor_effects;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.tjdev.util.tjpluginutil.spigot.scheduler.universalscheduler.UniversalRunnable;

public class ArmorEffectsTask extends UniversalRunnable {

    @Override
    public void run() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            ArmorEffectsMechanic.addEffects(player);
        }
    }
}
