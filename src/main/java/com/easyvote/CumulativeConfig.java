package com.easyvote;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Logger;

/** The standalone file is authoritative once present; migrate before removing any legacy data. */
final class CumulativeConfig {
    private static final String LEGACY_PATH = "votifier.cumulative";

    static YamlConfiguration load(File folder, FileConfiguration main, Logger logger) {
        File file = new File(folder, "cumulative.yml");
        try {
            if (file.exists()) {
                YamlConfiguration config = new YamlConfiguration();
                config.load(file); // Do not silently fall back to defaults on malformed YAML.
                if (main.contains(LEGACY_PATH, true)) {
                    logger.warning("cumulative.yml 已存在，忽略 config.yml 中旧的 votifier.cumulative 配置");
                }
                return config;
            }

            YamlConfiguration config = new YamlConfiguration();
            try (var resource = CumulativeConfig.class.getResourceAsStream("/cumulative.yml")) {
                if (resource == null) throw new IOException("缺少默认 cumulative.yml");
                config.load(new InputStreamReader(resource, StandardCharsets.UTF_8));
            }
            ConfigurationSection legacy = main.getConfigurationSection(LEGACY_PATH);
            if (main.contains(LEGACY_PATH, true) && legacy == null) {
                throw new InvalidConfigurationException("votifier.cumulative 必须为配置段");
            }
            if (legacy != null) {
                for (var entry : legacy.getValues(true).entrySet()) {
                    if (!(entry.getValue() instanceof ConfigurationSection)) config.set(entry.getKey(), entry.getValue());
                }
            }
            saveAtomically(config, file.toPath());
            if (legacy != null) {
                // Only remove the original after the complete migrated file has been saved.
                main.set(LEGACY_PATH, null);
                try {
                    saveAtomically(main, new File(folder, "config.yml").toPath());
                } catch (IOException e) {
                    main.set(LEGACY_PATH, legacy);
                    throw e;
                }
                logger.info("已将 config.yml 的 votifier.cumulative 迁移至 cumulative.yml");
            }
            return config;
        } catch (IOException | InvalidConfigurationException e) {
            throw new IllegalStateException("加载 cumulative.yml 失败，请检查配置；未使用默认奖励替代", e);
        }
    }

    private static void saveAtomically(FileConfiguration config, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, config.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
