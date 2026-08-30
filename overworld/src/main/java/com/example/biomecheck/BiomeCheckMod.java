package com.example.biomecheck;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.RegistryKey;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BiomeCheckMod implements ClientModInitializer {
    public static final String MOD_ID = "biomecheck";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    // Сканируем коробку [-50..50] x [-50..50] по X/Z, Y = 100
    private static final int MIN_XZ = -50;
    private static final int MAX_XZ = 50;
    private static final int SCAN_Y = 100;

    // Регэксп для строки скорборда "Анархия-<number>" (поддержим и "Anarchy-N" на всякий случай).
    private static final Pattern ANARCHY_PATTERN =
            Pattern.compile("(?:Анархия|Anarchy)\\s*[-#:]?\\s*(\\d+)", Pattern.UNICODE_CASE);

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private boolean scanning = false;
    private Map<String, BiomeRegion> currentScan = null; // biomeId -> aggregated bounds
    private Integer currentAnarchyNumber = null;

    @Override
    public void onInitializeClient() {
        Path cfgDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        try { Files.createDirectories(cfgDir); } catch (IOException e) { LOG.error("mkdir", e); }

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
                    .literal("biome-check")
                    .executes(ctx -> {
                        toggle(ctx.getSource());
                        return Command.SINGLE_SUCCESS;
                    })
            );
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void toggle(FabricClientCommandSource src) {
        if (scanning) {
            scanning = false;
            currentScan = null;
            currentAnarchyNumber = null;
            src.sendFeedback(Text.literal("[BiomeCheck] сканирование остановлено").formatted(Formatting.YELLOW));
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            src.sendFeedback(Text.literal("[BiomeCheck] нет мира").formatted(Formatting.RED));
            return;
        }
        // только overworld
        RegistryKey<World> dim = mc.world.getRegistryKey();
        if (!dim.equals(World.OVERWORLD)) {
            src.sendFeedback(Text.literal("[BiomeCheck] работает только в overworld").formatted(Formatting.RED));
            return;
        }

        Integer num = readAnarchyNumber(mc.world);
        if (num == null) {
            src.sendFeedback(Text.literal("[BiomeCheck] не нашёл 'Анархия-<номер>' в скорборде").formatted(Formatting.RED));
            return;
        }

        // Уже есть в конфиге?
        Config cfg = Config.load();
        Integer gen = cfg.findGenerationOf(num);
        if (gen != null) {
            src.sendFeedback(Text.literal("[BiomeCheck] анархия " + num + " уже в базе (поколение " + gen + "), пропуск").formatted(Formatting.GRAY));
            return;
        }

        scanning = true;
        currentAnarchyNumber = num;
        currentScan = new LinkedHashMap<>();
        src.sendFeedback(Text.literal("[BiomeCheck] стартую скан анархии " + num + "...").formatted(Formatting.AQUA));
    }

    private void onClientTick(MinecraftClient mc) {
        if (!scanning) return;
        if (mc.world == null || mc.player == null) return;

        ClientWorld world = mc.world;

        // Проверим что нужные чанки прогружены — все 9 чанков, покрывающих [-50..50] (это чанки -4..3 по X и Z).
        for (int cx = -4; cx <= 3; cx++) {
            for (int cz = -4; cz <= 3; cz++) {
                if (world.getChunkManager().getChunk(cx, cz) == null) {
                    // ждём прогрузки
                    return;
                }
            }
        }

        // Сканируем весь блок за один тик — 101*101 = 10201 поиск биома, это дёшево.
        for (int x = MIN_XZ; x <= MAX_XZ; x++) {
            for (int z = MIN_XZ; z <= MAX_XZ; z++) {
                BlockPos pos = new BlockPos(x, SCAN_Y, z);
                var entry = world.getBiome(pos);
                var key = entry.getKey().orElse(null);
                String id = (key != null) ? key.getValue().toString() : "unknown";
                BiomeRegion r = currentScan.computeIfAbsent(id, k -> new BiomeRegion());
                r.add(x, z);
            }
        }

        finishScan(mc);
    }

    private void finishScan(MinecraftClient mc) {
        int num = currentAnarchyNumber;
        Map<String, BiomeRegion> scan = currentScan;
        scanning = false;
        currentScan = null;
        currentAnarchyNumber = null;

        // Сохраняем "сырой" скан конкретной анархии (отдельный json для дебага/прозрачности).
        Path cfgDir = FabricLoader.getInstance().getConfigDir().resolve(MOD_ID);
        try {
            Path scansDir = cfgDir.resolve("scans");
            Files.createDirectories(scansDir);
            JsonObject root = new JsonObject();
            root.addProperty("anarchy", num);
            JsonObject biomes = new JsonObject();
            for (var e : scan.entrySet()) biomes.add(e.getKey(), e.getValue().toJson());
            root.add("biomes", biomes);
            Files.writeString(scansDir.resolve("anarchy-" + num + ".json"), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("save scan", e);
        }

        // Сравниваем с существующими поколениями.
        Config cfg = Config.load();
        Integer matched = cfg.findGenerationMatching(scan);
        int gen;
        if (matched != null) {
            cfg.addServerToGeneration(matched, num);
            gen = matched;
        } else {
            gen = cfg.createGeneration(scan, num);
        }
        cfg.save();

        if (mc.player != null) {
            mc.player.sendMessage(
                Text.literal("[BiomeCheck] биом на анархии " + num + " был вычислен и добавлен в базу (поколение " + gen + ")")
                    .formatted(Formatting.GREEN),
                false
            );
        }
    }

    private static Integer readAnarchyNumber(ClientWorld world) {
        Scoreboard sb = world.getScoreboard();
        // 1) сайдбар
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        Integer n = checkObjective(obj);
        if (n != null) return n;
        // 2) на всякий — все objectives
        for (ScoreboardObjective o : sb.getObjectives()) {
            n = checkObjective(o);
            if (n != null) return n;
        }
        return null;
    }

    private static Integer checkObjective(ScoreboardObjective obj) {
        if (obj == null) return null;
        // имя/заголовок objective
        String[] candidates;
        try {
            candidates = new String[] {
                obj.getDisplayName().getString(),
                obj.getName()
            };
        } catch (Throwable t) {
            candidates = new String[] { obj.getName() };
        }
        for (String s : candidates) {
            if (s == null) continue;
            Matcher m = ANARCHY_PATTERN.matcher(s);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        // и строки на счёт — игроки/строки имеют формат "Анархия-NN" иногда там
        Scoreboard sb = MinecraftClient.getInstance().world.getScoreboard();
        for (var entry : sb.getKnownScoreHolders()) {
            String s = entry.getNameForScoreboard();
            if (s == null) continue;
            Matcher m = ANARCHY_PATTERN.matcher(s);
            if (m.find()) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    // ===================== BiomeRegion =====================
    /** Хранит набор всех (x,z) клеток биома и его bounding box. */
    public static class BiomeRegion {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        // Для надёжного сравнения двух сканов используем полный набор клеток.
        Set<Long> cells = new HashSet<>();

        void add(int x, int z) {
            if (x < minX) minX = x;
            if (z < minZ) minZ = z;
            if (x > maxX) maxX = x;
            if (z > maxZ) maxZ = z;
            cells.add(((long)(x & 0xffffffffL) << 32) | (z & 0xffffffffL));
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            JsonObject start = new JsonObject();
            start.addProperty("x", minX); start.addProperty("z", minZ);
            JsonObject end = new JsonObject();
            end.addProperty("x", maxX); end.addProperty("z", maxZ);
            o.add("start", start);
            o.add("end", end);
            o.addProperty("cells_count", cells.size());
            // fingerprint = хеш отсортированного списка клеток (детерминированный)
            long h = 1469598103934665603L;
            long[] arr = cells.stream().mapToLong(Long::longValue).sorted().toArray();
            for (long v : arr) {
                h ^= v;
                h *= 1099511628211L;
            }
            o.addProperty("fingerprint", Long.toHexString(h));
            return o;
        }

        String fingerprint() {
            long h = 1469598103934665603L;
            long[] arr = cells.stream().mapToLong(Long::longValue).sorted().toArray();
            for (long v : arr) { h ^= v; h *= 1099511628211L; }
            return Long.toHexString(h);
        }
    }

    /** "Подпись" всего скана: отображение biomeId -> fingerprint региона. */
    static Map<String, String> signature(Map<String, BiomeRegion> scan) {
        TreeMap<String, String> sig = new TreeMap<>();
        for (var e : scan.entrySet()) sig.put(e.getKey(), e.getValue().fingerprint());
        return sig;
    }

    // ===================== Config =====================
    public static class Config {
        // generation number -> {servers: [..], signature: {biomeId: fingerprint}, biomes: {...как в скане...}}
        public final Map<Integer, Generation> generations = new TreeMap<>();

        public static class Generation {
            public List<Integer> servers = new ArrayList<>();
            public Map<String, String> signature = new TreeMap<>();
            public JsonObject biomes = new JsonObject(); // оригинальный layout (для справки)
        }

        static Path file() {
            return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("config.json");
        }

        public static Config load() {
            Config c = new Config();
            Path p = file();
            if (!Files.exists(p)) return c;
            try {
                String s = Files.readString(p, StandardCharsets.UTF_8);
                JsonObject root = JsonParser.parseString(s).getAsJsonObject();
                JsonObject gens = root.getAsJsonObject("generations");
                if (gens != null) {
                    for (var e : gens.entrySet()) {
                        int gen = Integer.parseInt(e.getKey());
                        JsonObject g = e.getValue().getAsJsonObject();
                        Generation G = new Generation();
                        JsonArray arr = g.getAsJsonArray("servers");
                        if (arr != null) for (JsonElement el : arr) G.servers.add(el.getAsInt());
                        JsonObject sig = g.getAsJsonObject("signature");
                        if (sig != null) for (var se : sig.entrySet()) G.signature.put(se.getKey(), se.getValue().getAsString());
                        JsonObject biomes = g.getAsJsonObject("biomes");
                        if (biomes != null) G.biomes = biomes;
                        c.generations.put(gen, G);
                    }
                }
            } catch (Exception ex) {
                LOG.error("config load", ex);
            }
            return c;
        }

        static Path listFile() {
            return FabricLoader.getInstance().getConfigDir().resolve(MOD_ID).resolve("list.json");
        }

        public void save() {
            try {
                Files.createDirectories(file().getParent());
                JsonObject root = new JsonObject();
                JsonObject gens = new JsonObject();
                JsonObject listRoot = new JsonObject();
                JsonObject listGens = new JsonObject();
                for (var e : generations.entrySet()) {
                    JsonObject g = new JsonObject();
                    JsonArray sv = new JsonArray();
                    for (Integer s : e.getValue().servers) sv.add(s);
                    g.add("servers", sv);
                    JsonObject sig = new JsonObject();
                    for (var se : e.getValue().signature.entrySet()) sig.addProperty(se.getKey(), se.getValue());
                    g.add("signature", sig);
                    g.add("biomes", e.getValue().biomes);
                    gens.add(String.valueOf(e.getKey()), g);

                    // clean list.json — только servers
                    JsonObject lg = new JsonObject();
                    JsonArray lsv = new JsonArray();
                    for (Integer s : e.getValue().servers) lsv.add(s);
                    lg.add("servers", lsv);
                    listGens.add(String.valueOf(e.getKey()), lg);
                }
                root.add("generations", gens);
                listRoot.add("generations", listGens);
                Files.writeString(file(), GSON.toJson(root), StandardCharsets.UTF_8);
                Files.writeString(listFile(), GSON.toJson(listRoot), StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.error("config save", e);
            }
        }

        public Integer findGenerationOf(int server) {
            for (var e : generations.entrySet())
                if (e.getValue().servers.contains(server)) return e.getKey();
            return null;
        }

        public Integer findGenerationMatching(Map<String, BiomeRegion> scan) {
            Map<String, String> sig = signature(scan);
            for (var e : generations.entrySet()) {
                if (e.getValue().signature.equals(sig)) return e.getKey();
            }
            return null;
        }

        public void addServerToGeneration(int gen, int server) {
            Generation g = generations.get(gen);
            if (g == null) return;
            if (!g.servers.contains(server)) {
                g.servers.add(server);
                Collections.sort(g.servers);
            }
        }

        public int createGeneration(Map<String, BiomeRegion> scan, int server) {
            int next = 1;
            while (generations.containsKey(next)) next++;
            Generation g = new Generation();
            g.servers.add(server);
            g.signature.putAll(signature(scan));
            JsonObject biomes = new JsonObject();
            for (var e : scan.entrySet()) biomes.add(e.getKey(), e.getValue().toJson());
            g.biomes = biomes;
            generations.put(next, g);
            return next;
        }
    }
}
