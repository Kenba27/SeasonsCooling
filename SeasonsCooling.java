package vn.seasonscooling;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionType;
import tr.alperendemir.seasons.Seasons;
import tr.alperendemir.seasons.effects.TemperatureEffects;
import tr.alperendemir.seasons.player.PlayerManager;
import tr.alperendemir.seasons.temperature.TemperatureCalculator;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public final class SeasonsCooling extends JavaPlugin implements Listener {

    private final Map<UUID, Long> coolUntil = new ConcurrentHashMap<>();
    private final Map<UUID, Double> lastTemp = new ConcurrentHashMap<>();

    private double minTemp;
    private double maxTemp;
    private long waterMs;
    private long melonMs;
    private long teaMs;
    private String teaName;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        Seasons seasons = (Seasons) getServer().getPluginManager().getPlugin("Seasons");
        if (seasons == null) {
            getLogger().severe("Khong tim thay plugin Seasons!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        try {
            hook(seasons.getPlayerManager());
        } catch (Throwable t) {
            getLogger().severe("Khong the hook vao Seasons: " + t);
            t.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Da hook TemperatureCalculator cua Seasons.");
    }

    private void loadSettings() {
        waterMs = getConfig().getLong("durations.water", 60) * 1000L;
        melonMs = getConfig().getLong("durations.watermelon", 30) * 1000L;
        teaMs = getConfig().getLong("durations.iced-tea", 120) * 1000L;
        minTemp = getConfig().getDouble("temperature.min", 38.0);
        maxTemp = getConfig().getDouble("temperature.max", 40.0);
        if (maxTemp < minTemp) {
            double t = minTemp;
            minTemp = maxTemp;
            maxTemp = t;
        }
        teaName = getConfig().getString("iced-tea-name", "Trà đá").toLowerCase(Locale.ROOT);
    }

    /** Thay bo tinh nhiet do cua Seasons bang ban co them logic "dang mat". */
    private void hook(PlayerManager pm) throws Exception {
        CoolingCalculator calc = new CoolingCalculator();

        Field f = PlayerManager.class.getDeclaredField("temperatureCalculator");
        f.setAccessible(true);
        f.set(pm, calc);

        Field fe = PlayerManager.class.getDeclaredField("temperatureEffects");
        fe.setAccessible(true);
        Object effects = fe.get(pm);
        if (effects instanceof TemperatureEffects) {
            Field fc = TemperatureEffects.class.getDeclaredField("temperatureCalculator");
            fc.setAccessible(true);
            fc.set(effects, calc);
        }
    }

    private final class CoolingCalculator extends TemperatureCalculator {
        @Override
        public double calculateTemperature(Player player) {
            double real = super.calculateTemperature(player);
            UUID id = player.getUniqueId();
            Long end = coolUntil.get(id);
            if (end == null) {
                return real;
            }
            if (System.currentTimeMillis() >= end) {
                coolUntil.remove(id);
                lastTemp.remove(id);
                return real;
            }
            if (real <= maxTemp) {
                return real; // chi giam, khong tang
            }
            double cur = lastTemp.getOrDefault(id, (minTemp + maxTemp) / 2.0);
            cur += (ThreadLocalRandom.current().nextDouble() * 2 - 1) * 0.6;
            cur = Math.max(minTemp, Math.min(maxTemp, cur));
            lastTemp.put(id, cur);
            return Math.min(real, cur);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        ItemStack item = e.getItem();
        long ms = 0;
        Material type = item.getType();

        if (type == Material.MELON_SLICE) {
            ms = melonMs;
        } else if (type == Material.POTION) {
            ItemMeta meta = item.getItemMeta();
            if (meta instanceof PotionMeta pmeta
                    && pmeta.getBasePotionType() == PotionType.WATER
                    && !pmeta.hasCustomEffects()) {
                ms = waterMs;
            }
        } else if (type == Material.HONEY_BOTTLE) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.hasDisplayName()) {
                String name = ChatColor.stripColor(meta.getDisplayName());
                if (name != null && name.toLowerCase(Locale.ROOT).contains(teaName)) {
                    ms = teaMs;
                }
            }
        }
        if (ms <= 0) {
            return;
        }
        UUID id = e.getPlayer().getUniqueId();
        long end = System.currentTimeMillis() + ms;
        coolUntil.merge(id, end, Math::max);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        coolUntil.remove(e.getPlayer().getUniqueId());
        lastTemp.remove(e.getPlayer().getUniqueId());
    }
}
