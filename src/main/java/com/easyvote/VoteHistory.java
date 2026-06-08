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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class VoteHistory {

    private final EasyVotePlugin plugin;
    private final Map<String, List<VoteRecord>> playerVotes;
    private final Map<String, Integer> serviceVoteCounts;
    private final Map<String, Integer> playerVoteCounts;
    private int totalVotes;
    private final File dataFile;

    public VoteHistory(EasyVotePlugin plugin) {
        this.plugin = plugin;
        this.playerVotes = new ConcurrentHashMap<>();
        this.serviceVoteCounts = new ConcurrentHashMap<>();
        this.playerVoteCounts = new ConcurrentHashMap<>();
        this.totalVotes = 0;
        this.dataFile = new File(plugin.getDataFolder(), "votes.csv");
        loadFromFile();
    }

    public void addVote(String playerName, String serviceName, String address, long timestamp) {
        playerName = playerName.toLowerCase();
        
        VoteRecord record = new VoteRecord(playerName, serviceName, address, timestamp);
        playerVotes.computeIfAbsent(playerName, k -> new ArrayList<>()).add(record);
        
        serviceVoteCounts.merge(serviceName.toLowerCase(), 1, Integer::sum);
        playerVoteCounts.merge(playerName, 1, Integer::sum);
        totalVotes++;
        
        saveToFile();
    }

    public List<VoteRecord> getPlayerVotes(String playerName) {
        return new ArrayList<>(playerVotes.getOrDefault(playerName.toLowerCase(), new ArrayList<>()));
    }

    public int getPlayerVoteCount(String playerName) {
        return playerVoteCounts.getOrDefault(playerName.toLowerCase(), 0);
    }

    public int getServiceVoteCount(String serviceName) {
        return serviceVoteCounts.getOrDefault(serviceName.toLowerCase(), 0);
    }

    public int getTotalVotes() {
        return totalVotes;
    }

    public Map<String, Integer> getPlayerVoteCounts() {
        return new HashMap<>(playerVoteCounts);
    }

    public Map<String, Integer> getServiceVoteCounts() {
        return new HashMap<>(serviceVoteCounts);
    }

    public List<VoteRecord> getAllVotes() {
        return playerVotes.values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    public List<VoteRecord> getRecentVotes(int limit) {
        return playerVotes.values().stream()
            .flatMap(List::stream)
            .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    public void clear() {
        playerVotes.clear();
        serviceVoteCounts.clear();
        playerVoteCounts.clear();
        totalVotes = 0;
        saveToFile();
    }
    
    public int clearPlayerVotes(String playerName) {
        playerName = playerName.toLowerCase();
        List<VoteRecord> removed = playerVotes.remove(playerName);
        if (removed == null || removed.isEmpty()) {
            return 0;
        }
        
        int count = removed.size();
        totalVotes -= count;
        playerVoteCounts.remove(playerName);
        
        for (VoteRecord record : removed) {
            String service = record.getServiceName().toLowerCase();
            serviceVoteCounts.merge(service, -1, (old, delta) -> Math.max(0, old + delta));
        }
        
        saveToFile();
        return count;
    }

    public boolean isFirstVote(String playerName) {
        return !playerVotes.containsKey(playerName.toLowerCase());
    }

    public boolean isFirstVoteForService(String playerName, String serviceName) {
        List<VoteRecord> votes = playerVotes.get(playerName.toLowerCase());
        if (votes == null) {
            return true;
        }
        return votes.stream().noneMatch(record -> record.getServiceName().equalsIgnoreCase(serviceName));
    }

    private void saveToFile() {
        try {
            if (!dataFile.getParentFile().exists()) {
                dataFile.getParentFile().mkdirs();
            }
            
            File tempFile = new File(dataFile.getParentFile(), "votes.csv.tmp");
            
            try (BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8))) {
                
                writer.write("# 投票记录文件 - 请勿手动编辑");
                writer.newLine();
                writer.write("# 格式: 玩家名,网站名,IP地址,时间戳");
                writer.newLine();
                
                for (List<VoteRecord> records : playerVotes.values()) {
                    for (VoteRecord record : records) {
                        writer.write(String.format("%s,%s,%s,%d",
                            record.getPlayerName(),
                            record.getServiceName(),
                            record.getAddress(),
                            record.getTimestamp()));
                        writer.newLine();
                    }
                }
            }
            
            if (dataFile.exists()) {
                dataFile.delete();
            }
            tempFile.renameTo(dataFile);
            
        } catch (IOException e) {
            plugin.getLogger().warning("保存投票记录失败: " + e.getMessage());
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
                if (parts.length >= 4) {
                    String playerName = parts[0].toLowerCase();
                    String serviceName = parts[1];
                    String address = parts[2];
                    long timestamp;
                    
                    try {
                        timestamp = Long.parseLong(parts[3]);
                    } catch (NumberFormatException e) {
                        timestamp = System.currentTimeMillis();
                    }
                    
                    VoteRecord record = new VoteRecord(playerName, serviceName, address, timestamp);
                    playerVotes.computeIfAbsent(playerName, k -> new ArrayList<>()).add(record);
                    serviceVoteCounts.merge(serviceName.toLowerCase(), 1, Integer::sum);
                    playerVoteCounts.merge(playerName, 1, Integer::sum);
                    totalVotes++;
                    loadedCount++;
                }
            }
            
            plugin.getLogger().info("已加载 " + loadedCount + " 条投票记录");
        } catch (IOException e) {
            plugin.getLogger().warning("加载投票记录失败: " + e.getMessage());
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("加载投票记录失败: 格式错误");
        }
    }

    public static class VoteRecord {
        private final String playerName;
        private final String serviceName;
        private final String address;
        private final long timestamp;

        public VoteRecord(String playerName, String serviceName, String address, long timestamp) {
            this.playerName = playerName;
            this.serviceName = serviceName;
            this.address = address;
            this.timestamp = timestamp;
        }

        public String getPlayerName() {
            return playerName;
        }

        public String getServiceName() {
            return serviceName;
        }

        public String getAddress() {
            return address;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
}
