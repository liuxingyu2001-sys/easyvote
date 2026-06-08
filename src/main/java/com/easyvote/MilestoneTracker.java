package com.easyvote;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MilestoneTracker {

    private final EasyVotePlugin plugin;
    private final ConcurrentHashMap<String, Set<Integer>> receivedMilestones;
    private final File dataFile;

    public MilestoneTracker(EasyVotePlugin plugin) {
        this.plugin = plugin;
        this.receivedMilestones = new ConcurrentHashMap<>();
        this.dataFile = new File(plugin.getDataFolder(), "milestones.csv");
        loadFromFile();
    }

    /**
     * Atomically check and mark a milestone. Returns true if this call
     * actually marked it (first time), false if it was already received.
     */
    public boolean markIfNotReceived(String playerName, int count, long timestamp) {
        playerName = playerName.toLowerCase();
        Set<Integer> milestones = receivedMilestones.computeIfAbsent(
            playerName, k -> ConcurrentHashMap.newKeySet()
        );
        if (!milestones.add(count)) {
            return false;
        }
        saveToFile(playerName, count, timestamp);
        return true;
    }

    public int getPlayerHighestMilestone(String playerName) {
        Set<Integer> milestones = receivedMilestones.get(playerName.toLowerCase());
        if (milestones == null || milestones.isEmpty()) {
            return 0;
        }
        return milestones.stream().max(Integer::compareTo).orElse(0);
    }

    private void saveToFile(String playerName, int count, long timestamp) {
        try {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(dataFile, true), StandardCharsets.UTF_8))) {
                writer.write(String.format("%s,%d,%d", playerName, count, timestamp));
                writer.newLine();
            }
        } catch (IOException e) {
            plugin.getLogger().warning("保存里程碑记录失败: " + e.getMessage());
        }
    }

    private void loadFromFile() {
        if (!dataFile.exists()) {
            return;
        }

        int loadedCount = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(dataFile), StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split(",");
                if (parts.length >= 2) {
                    String playerName = parts[0].toLowerCase();
                    int count;
                    try {
                        count = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    receivedMilestones.computeIfAbsent(playerName, k -> ConcurrentHashMap.newKeySet()).add(count);
                    loadedCount++;
                }
            }

            plugin.getLogger().info("已加载 " + loadedCount + " 条里程碑记录");
        } catch (IOException e) {
            plugin.getLogger().warning("加载里程碑记录失败: " + e.getMessage());
        }
    }
}
