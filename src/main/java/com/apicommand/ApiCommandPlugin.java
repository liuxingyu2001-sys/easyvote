package com.apicommand;

import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;

public class ApiCommandPlugin extends JavaPlugin {

    private static ApiCommandPlugin instance;
    private ApiServer apiServer;
    private Object skinsRestorer;
    private String apiKey;
    private int apiPort;
    private boolean apiEnabled;
    private int maxConnections;
    private int requestTimeout;
    private boolean enableRateLimit;
    private int maxRequestsPerMinute;
    private VotifierServer votifierServer;
    private RSAKeyManager keyManager;
    private VoteHistory voteHistory;
    private VoteListener voteListener;
    private boolean votifierEnabled;
    private boolean debugEnabled;

    @Override
    public void onEnable() {
        instance = this;
        
        saveDefaultConfig();
        updateConfig();
        loadConfig();
        
        try {
            Class<?> providerClass = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider");
            Object api = providerClass.getMethod("get").invoke(null);
            skinsRestorer = api;
            getLogger().info("已加载 SkinsRestorer API");
        } catch (ClassNotFoundException e) {
            getLogger().warning("未找到 SkinsRestorer 插件，皮肤功能将不可用");
        } catch (NoClassDefFoundError e) {
            getLogger().warning("未找到 SkinsRestorer 插件，皮肤功能将不可用");
        } catch (Exception e) {
            getLogger().warning("SkinsRestorer API 不可用: " + e.getMessage());
            getLogger().warning("原因: 在 Velocity 代理模式下，子服务器需要配置数据库才能使用 SkinsRestorer API");
            getLogger().warning("解决方案: 在子服务器的 SkinsRestorer config.yml 中配置 MySQL 数据库");
            getLogger().warning("或者: 在子服务器的 SkinsRestorer config.yml 中设置 server.proxyMode.detection: DISABLED");
            getLogger().warning("ApiCommand 插件的其他功能（API 服务器）仍可正常使用");
        }
        
        try {
            startApiServer();
        } catch (Exception e) {
            getLogger().severe("无法启动 API 服务器: " + e.getMessage());
            e.printStackTrace();
        }
        
        try {
            startVotifier();
        } catch (Exception e) {
            getLogger().severe("无法启动 Votifier 服务器: " + e.getMessage());
        }
        
        getCommand("apicommand").setExecutor(new ApiCommand());
        getCommand("apicommand").setTabCompleter(new ApiCommandTabCompleter());
        
        getLogger().info("ApiCommand 插件已启用!");
    }

    @Override
    public void onDisable() {
        if (apiServer != null) {
            apiServer.stop();
        }
        if (votifierServer != null) {
            votifierServer.stop();
        }
        getLogger().info("ApiCommand 插件已禁用!");
    }

    private void updateConfig() {
        boolean needsSave = false;
        
        if (!getConfig().contains("votifier")) {
            getConfig().set("votifier.enabled", true);
            getConfig().set("votifier.host", "0.0.0.0");
            getConfig().set("votifier.port", 8192);
            getConfig().set("votifier.public-key", "");
            getConfig().set("votifier.private-key", "");
            getConfig().set("votifier.max-threads", 10);
            getConfig().set("votifier.verbose", true);
            needsSave = true;
        }
        
        var rewardsSection = getConfig().getConfigurationSection("votifier.rewards");
        
        if (rewardsSection == null) {
            java.util.Map<String, Object> rewardsMap = new java.util.LinkedHashMap<>();
            
            rewardsMap.put("default", List.of(
                "msg %player% &a感谢你的投票！",
                "money give %player% 1000"
            ));
            
            rewardsMap.put("first-vote-mczfw", List.of(
                "msg %player% &6&l恭喜你！这是你在找服网的第一次投票！",
                "money give %player% 3000",
                "give %player% diamond 3",
                "titlemsg %player% &6&l首次投票 &a&l+3000金币"
            ));
            
            rewardsMap.put("mczfw", List.of(
                "msg %player% &a感谢在找服网投票！",
                "money give %player% 2000",
                "give %player% diamond 1"
            ));
            
            rewardsMap.put("first-vote-wdsjfwq", List.of(
                "msg %player% &6&l恭喜你！这是你在服务器站的第一次投票！",
                "money give %player% 2500",
                "give %player% diamond 2",
                "titlemsg %player% &6&l首次投票 &a&l+2500金币"
            ));
            
            rewardsMap.put("wdsjfwq", List.of(
                "msg %player% &a感谢在服务器站投票！",
                "money give %player% 1500"
            ));
            
            getConfig().set("votifier.rewards", rewardsMap);
            needsSave = true;
            getLogger().info("已生成投票奖励配置");
        } else {
            getLogger().info("已加载投票奖励配置 (共 " + rewardsSection.getKeys(false).size() + " 个网站)");
        }
        
        if (getConfig().getString("api-key", "your-secret-api-key-change-this").equals("your-secret-api-key-change-this")) {
            getConfig().set("api-key", UUID.randomUUID().toString());
            needsSave = true;
        }
        
        if (needsSave) {
            saveConfig();
            getLogger().info("配置文件已更新");
        }
    }

    private void loadConfig() {
        apiKey = getConfig().getString("api-key", "your-secret-api-key-change-this");
        apiPort = getConfig().getInt("api-port", 28888);
        apiEnabled = getConfig().getBoolean("api-enabled", true);
        maxConnections = getConfig().getInt("max-connections", 10);
        requestTimeout = getConfig().getInt("request-timeout", 15000);
        enableRateLimit = getConfig().getBoolean("enable-rate-limit", true);
        maxRequestsPerMinute = getConfig().getInt("max-requests-per-minute", 600);
        
        votifierEnabled = getConfig().getBoolean("votifier.enabled", true);
        debugEnabled = getConfig().getBoolean("debug", false);
    }

    public void reloadPluginConfig() {
        reloadConfig();
        loadConfig();
        if (voteListener != null) {
            voteListener.reloadRewards();
        }
    }

    private void startApiServer() throws Exception {
        if (!apiEnabled) {
            getLogger().info("API 服务器已禁用");
            return;
        }
        
        apiServer = new ApiServer(apiPort, apiKey, maxConnections, requestTimeout, enableRateLimit, maxRequestsPerMinute);
        apiServer.start();
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
        
        voteHistory = new VoteHistory(this);
        voteListener = new VoteListener(this, voteHistory);
        getServer().getPluginManager().registerEvents(voteListener, this);
        
        String host = getConfig().getString("votifier.host", "0.0.0.0");
        int port = getConfig().getInt("votifier.port", 8192);
        int maxThreads = getConfig().getInt("votifier.max-threads", 10);
        
        votifierServer = new VotifierServer(this, keyManager, host, port, maxThreads);
        votifierServer.start();
    }

    public static ApiCommandPlugin getInstance() {
        return instance;
    }

    public String getApiKey() {
        return apiKey;
    }

    public int getApiPort() {
        return apiPort;
    }

    public boolean isApiEnabled() {
        return apiEnabled;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public int getRequestTimeout() {
        return requestTimeout;
    }

    public boolean isRateLimitEnabled() {
        return enableRateLimit;
    }

    public int getMaxRequestsPerMinute() {
        return maxRequestsPerMinute;
    }

    public Object getSkinsRestorer() {
        return skinsRestorer;
    }

    public VoteHistory getVoteHistory() {
        return voteHistory;
    }
    
    public RSAKeyManager getKeyManager() {
        return keyManager;
    }
    
    public boolean isDebugEnabled() {
        return debugEnabled;
    }
}
