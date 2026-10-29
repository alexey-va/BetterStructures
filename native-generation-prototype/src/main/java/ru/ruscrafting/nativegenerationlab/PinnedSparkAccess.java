package ru.ruscrafting.nativegenerationlab;

import org.bukkit.command.CommandSender;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.locks.Lock;

/** Lab-only binding to Paper's exact bundled spark-paper 1.10.152; unknown layouts fail closed. */
final class PinnedSparkAccess implements SparkCapture.SamplerAccess {
    private static final String BASE = "me.lucko.spark.paper.common.";
    private final Object platform, container, sender;
    private final Lock lock;
    private final Method active, background, started, execute, parse, metadata, comment, receiptStart;

    static PinnedSparkAccess open(ClassLoader loader, CommandSender console) throws Exception {
        // Actual class payloads from Paper bd74bf6's spark-paper 1.10.152 dependency.
        Map<String, String> hashes = Map.of(
                "SparkPlatform", "9fa11a63c97c441ada47c8bbc862c0c6c1430ca31cddd2722e0933b7e051677c",
                "command.modules.SamplerModule", "7040bb8ac4ef2990e3c19f10c5b951baa0b482165adf1aa35885eb2a86563e46",
                "sampler.SamplerContainer", "00c9a0d3513f21c37c2380d60bae91a041cbf64c2795751352f51dcccfca3a3d");
        for (var entry : hashes.entrySet()) try (var input = loader.getResourceAsStream((BASE + entry.getKey()).replace('.', '/') + ".class")) {
            if (input == null || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readNBytes(2_000_000))).equals(entry.getValue()))
                throw new IllegalStateException("Unsupported bundled Spark implementation: " + entry.getKey());
        }
        Object api = Class.forName("me.lucko.spark.api.SparkProvider", true, loader).getMethod("get").invoke(null);
        if (!api.getClass().getName().equals(BASE + "api.SparkApi")) throw new IllegalStateException("Only the pinned bundled Paper Spark provider is supported");
        Object platform = field(api, "platform");
        Object plugin = platform.getClass().getMethod("getPlugin").invoke(platform);
        if (!plugin.getClass().getName().equals("me.lucko.spark.paper.PaperSparkPlugin")
                || !"1.10.152".equals(plugin.getClass().getMethod("getVersion").invoke(plugin)))
            throw new IllegalStateException("Unexpected Spark provider/version");
        return new PinnedSparkAccess(loader, platform, console);
    }
    private PinnedSparkAccess(ClassLoader loader, Object platform, CommandSender console) throws Exception {
        this.platform = platform;
        container = platform.getClass().getMethod("getSamplerContainer").invoke(platform);
        lock = (Lock) field(platform, "commandExecuteLock");
        sender = Class.forName("me.lucko.spark.paper.PaperCommandSender", true, loader).getConstructor(CommandSender.class).newInstance(console);
        Class<?> senderType = Class.forName(BASE + "command.sender.CommandSender", true, loader);
        execute = platform.getClass().getDeclaredMethod("executeCommand0", senderType, String[].class); execute.setAccessible(true);
        active = container.getClass().getMethod("getActiveSampler");
        Class<?> sampler = Class.forName(BASE + "sampler.Sampler", true, loader);
        background = sampler.getMethod("isRunningInBackground"); started = sampler.getMethod("getStartTime");
        Class<?> data = Class.forName("me.lucko.spark.paper.proto.SparkSamplerProtos$SamplerData", true, loader);
        parse = data.getMethod("parseFrom", byte[].class); metadata = data.getMethod("getMetadata");
        comment = metadata.getReturnType().getMethod("getComment"); receiptStart = metadata.getReturnType().getMethod("getStartTime");
    }
    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
    }
    @Override public Lock lock() { return lock; }
    @Override public Object active() throws Exception { return active.invoke(container); }
    @Override public boolean background(Object sampler) throws Exception { return (boolean) background.invoke(sampler); }
    @Override public long started(Object sampler) throws Exception { return (long) started.invoke(sampler); }
    @Override public void command(String... args) throws Exception { execute.invoke(platform, sender, args); }
    @Override public SparkCapture.Receipt receipt(byte[] bytes) throws Exception {
        Object info = metadata.invoke(parse.invoke(null, (Object) bytes));
        return new SparkCapture.Receipt((String) comment.invoke(info), (long) receiptStart.invoke(info));
    }
}
