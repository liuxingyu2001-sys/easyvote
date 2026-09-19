package com.easyvote;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EasyVotePlugin extends JavaPlugin {

    private static EasyVotePlugin instance;
    private DatabaseManager db;
    private VotifierServer votifierServer;
    private RSAKeyManager keyManager;
    private VoteHistory voteHistory;
    private VoteListener voteListener;
    private MilestoneTracker milestoneTracker;
    private boolean votifierEnabled;
    private boolean cumulativeEnabled;
    private boolean debugEnabled;

    @Override
    public void onEnable() {
        instance = this;
        
        saveDefaultConfig();
        updateConfig();
        loadConfig();
        
        try {
            startVotifier();
        } catch (Exception e) {
            getLogger().severe("无法启动 Votifier 服务器: " + e.getMessage());
        }
        
        getCommand("easyvote").setExecutor(new EasyVoteCommand());
        getCommand("easyvote").setTabCompleter(new EasyVoteTabCompleter());
        
        getLogger().info("EasyVote 插件已启用!");
    }

    @Override
    public void onDisable() {
        if (votifierServer != null) {
            votifierServer.stop();
        }
        if (db != null) {
            db.close();
        }
        getLogger().info("EasyVote 插件已禁用!");
    }

    private void updateConfig() {
        boolean needsSave = false;
        
        if (!getConfig().contains("votifier")) {
            getConfig().set("votifier.enabled", true);
            getConfig().set("votifier.host", "0.0.0.0");
            getConfig().set("votifier.port", 10022);
            getConfig().set("votifier.public-key", "");
            getConfig().set("votifier.private-key", "");
            getConfig().set("votifier.max-threads", 10);
            getConfig().set("votifier.verbose", true);
            needsSave = true;
        }
        
        var rewardsSection = getConfig().getConfigurationSection("votifier.rewards");

        if (rewardsSection == null) {
            Map<String, Object> rewardsMap = new LinkedHashMap<>();

            rewardsMap.put("first-vote", List.of(
                "msg %player% &6&l感谢你的首次投票！",
                "money give %player% 3000",
                "give %player% diamond 3"
            ));

            rewardsMap.put("vote", List.of(
                "money give %player% 2000",
                "msg %player% &a感谢你为服务器投票！"
            ));

            getConfig().set("votifier.rewards", rewardsMap);
            needsSave = true;
            getLogger().info("已生成投票奖励配置");
        } else {
            getLogger().info("已加载投票奖励配置");
        }

        if (!getConfig().contains("votifier.join-reward-delay-seconds")) {
            getConfig().set("votifier.join-reward-delay-seconds", 5);
            needsSave = true;
        }
        
        if (needsSave) {
            saveConfig();
            getLogger().info("配置文件已更新");
        }
    }

    private void loadConfig() {
        votifierEnabled = getConfig().getBoolean("votifier.enabled", true);
        cumulativeEnabled = getConfig().getBoolean("votifier.cumulative.enabled", true);
        debugEnabled = getConfig().getBoolean("debug", false);
    }

    public void reloadPluginConfig() {
        saveDefaultConfig();
        reloadConfig();
        updateConfig();
        loadConfig();
        if (voteListener != null) {
            voteListener.reloadRewards();
            voteListener.retryOnlineRewards();
        }
    }

    private void startVotifier() throws Exception {
        if (!votifierEnabled) {
            getLogger().info("Votifier 服务器已禁用");
            return;
        }
        
        String publicKey = getConfig().getString("votifier.public-key", "");
        String privateKey = getConfig().getString("votifier.private-key", "");
        
        if (publicKey.isEmpty() || privateKey.isEmpty()) {
            getLogger().info("生成 RSA 密钥对...");
            keyManager = new RSAKeyManager();
            keyManager.generateKeyPair();
            publicKey = keyManager.getPublicKeyString();
            privateKey = keyManager.getPrivateKeyString();
            getConfig().set("votifier.public-key", publicKey);
            getConfig().set("votifier.private-key", privateKey);
            saveConfig();
        } else {
            keyManager = new RSAKeyManager(publicKey, privateKey);
        }
        
        getLogger().info("========================================");
        getLogger().info("Votifier 公钥 (复制到投票网站):");
        getLogger().info("----------------------------------------");
        String pemKey = keyManager.getPublicKeyPEM();
        for (String line : pemKey.split("\n")) {
            getLogger().info(line);
        }
        getLogger().info("----------------------------------------");
        getLogger().info("========================================");
        
        db = new DatabaseManager(this);
        db.initialize();
        
        milestoneTracker = new MilestoneTracker(db);
        voteHistory = new VoteHistory(db);
        voteListener = new VoteListener(this, voteHistory, milestoneTracker);
        getServer().getPluginManager().registerEvents(voteListener, this);
        voteListener.retryOnlineRewards();
        
        String host = getConfig().getString("votifier.host", "0.0.0.0");
        int port = getConfig().getInt("votifier.port", 10022);
        int maxThreads = Math.max(1, getConfig().getInt("votifier.max-threads", 10));
        
        votifierServer = new VotifierServer(this, keyManager, host, port, maxThreads);
        votifierServer.start();
    }

    public static EasyVotePlugin getInstance() {
        return instance;
    }

    public VoteHistory getVoteHistory() {
        return voteHistory;
    }
    
    public RSAKeyManager getKeyManager() {
        return keyManager;
    }
    
    public MilestoneTracker getMilestoneTracker() {
        return milestoneTracker;
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }
}
