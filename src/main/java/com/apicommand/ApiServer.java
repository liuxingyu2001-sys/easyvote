package com.apicommand;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class ApiServer {

    private HttpServer server;
    private int port;
    private String apiKey;
    private ExecutorService executorService;
    private int maxConnections;
    private int requestTimeout;
    private boolean enableRateLimit;
    private int maxRequestsPerMinute;
    private Map<String, Long> requestTimestamps;
    private AtomicInteger totalRequests;
    private AtomicInteger failedRequests;
    private AtomicInteger activeConnections;
    private AtomicLong totalExecutionTime;
    private AtomicInteger requestCounter;

    public ApiServer(int port, String apiKey, int maxConnections, int requestTimeout, boolean enableRateLimit, int maxRequestsPerMinute) throws IOException {
        this.port = port;
        this.apiKey = apiKey;
        this.maxConnections = maxConnections;
        this.requestTimeout = requestTimeout;
        this.enableRateLimit = enableRateLimit;
        this.maxRequestsPerMinute = maxRequestsPerMinute;
        this.requestTimestamps = new ConcurrentHashMap<>();
        this.totalRequests = new AtomicInteger(0);
        this.failedRequests = new AtomicInteger(0);
        this.activeConnections = new AtomicInteger(0);
        this.totalExecutionTime = new AtomicLong(0);
        this.requestCounter = new AtomicInteger(0);
        this.executorService = Executors.newFixedThreadPool(maxConnections);
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        
        server.createContext("/api/command", new CommandHandler());
        server.createContext("/api/health", new HealthHandler());
        server.createContext("/api/stats", new StatsHandler());
        server.createContext("/api/vote/stats", new VoteStatsHandler());
        server.createContext("/api/vote/history", new VoteHistoryHandler());
        server.createContext("/api/skin", new SkinHandler());
        server.createContext("/api/skin/clear", new SkinClearHandler());
        
        server.setExecutor(executorService);
        server.start();
        
        ApiCommandPlugin.getInstance().getLogger().info("API 服务器已启动 (端口: " + port + ", 超时: " + requestTimeout + "ms)");
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
    }

    private String getClientIp(HttpExchange exchange) {
        String forwardedFor = exchange.getRequestHeaders().getFirst("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isEmpty()) {
            String[] ips = forwardedFor.split(",");
            return ips[0].trim();
        }
        return exchange.getRemoteAddress().getAddress().getHostAddress();
    }

    private boolean checkRateLimit(HttpExchange exchange) {
        if (!enableRateLimit) {
            return true;
        }
        
        String clientIp = getClientIp(exchange);
        long currentTime = System.currentTimeMillis();
        long oneMinuteAgo = currentTime - 60000;
        
        requestTimestamps.entrySet().removeIf(entry -> entry.getValue() < oneMinuteAgo);
        
        long requestCount = requestTimestamps.values().stream()
            .filter(time -> time > oneMinuteAgo)
            .count();
        
        if (requestCount >= maxRequestsPerMinute) {
            return false;
        }
        
        requestTimestamps.put(clientIp + "_" + requestCounter.incrementAndGet(), currentTime);
        return true;
    }

    private boolean validateKey(HttpExchange exchange) {
        String authHeader = exchange.getRequestHeaders().getFirst("Authorization");
        if (authHeader == null) {
            return false;
        }
        String expectedAuth = "Bearer " + apiKey;
        return expectedAuth.equals(authHeader);
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String response) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    private String readRequestBody(InputStream is) throws IOException {
        byte[] bytes = is.readAllBytes();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    class CommandHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!validateKey(exchange)) {
                    sendResponse(exchange, 401, "{\"error\":\"Unauthorized\",\"message\":\"Invalid or missing API key\"}");
                    return;
                }
                
                if (!checkRateLimit(exchange)) {
                    sendResponse(exchange, 429, "{\"error\":\"Rate Limited\",\"message\":\"Too many requests\"}");
                    return;
                }
                
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\",\"message\":\"Use POST method\"}");
                    return;
                }
                
                String requestBody = readRequestBody(exchange.getRequestBody());
                
                totalRequests.incrementAndGet();
                activeConnections.incrementAndGet();
                long startTime = System.currentTimeMillis();
                
                try {
                    String command = requestBody;
                    if (command.startsWith("command=")) {
                        command = java.net.URLDecoder.decode(command.substring(8), StandardCharsets.UTF_8);
                    }
                    
                    final String finalCommand = command;
                    Bukkit.getScheduler().callSyncMethod(ApiCommandPlugin.getInstance(), () -> {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), finalCommand);
                        return true;
                    }).get(requestTimeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                    
                    String response = "{\"success\":true,\"message\":\"Command executed\"}";
                    sendResponse(exchange, 200, response);
                } finally {
                    activeConnections.decrementAndGet();
                    totalExecutionTime.addAndGet(System.currentTimeMillis() - startTime);
                }
            } catch (Exception e) {
                failedRequests.incrementAndGet();
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }

    class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String response = "{\"status\":\"healthy\",\"uptime\":\"" + (System.currentTimeMillis() / 1000) + "\"}";
            sendResponse(exchange, 200, response);
        }
    }

    class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"totalRequests\":").append(totalRequests.get()).append(",");
            sb.append("\"failedRequests\":").append(failedRequests.get()).append(",");
            sb.append("\"activeConnections\":").append(activeConnections.get()).append(",");
            sb.append("\"totalExecutionTime\":").append(totalExecutionTime.get()).append(",");
            sb.append("\"averageExecutionTime\":").append(totalRequests.get() > 0 ? totalExecutionTime.get() / totalRequests.get() : 0);
            sb.append("}");
            sendResponse(exchange, 200, sb.toString());
        }
    }

    class VoteStatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            VoteHistory history = ApiCommandPlugin.getInstance().getVoteHistory();
            if (history == null) {
                sendResponse(exchange, 503, "{\"error\":\"Votifier not enabled\"}");
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            sb.append("\"totalVotes\":").append(history.getTotalVotes()).append(",");
            sb.append("\"serviceVoteCounts\":{");
            
            Map<String, Integer> serviceCounts = history.getServiceVoteCounts();
            boolean first = true;
            for (Map.Entry<String, Integer> entry : serviceCounts.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
                first = false;
            }
            sb.append("},");
            sb.append("\"playerVoteCounts\":{");
            
            Map<String, Integer> playerCounts = history.getPlayerVoteCounts();
            first = true;
            for (Map.Entry<String, Integer> entry : playerCounts.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":").append(entry.getValue());
                first = false;
            }
            sb.append("}");
            sb.append("}");
            sendResponse(exchange, 200, sb.toString());
        }
    }

    class VoteHistoryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            VoteHistory history = ApiCommandPlugin.getInstance().getVoteHistory();
            if (history == null) {
                sendResponse(exchange, 503, "{\"error\":\"Votifier not enabled\"}");
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("{\"votes\":[");
            
            java.util.List<VoteHistory.VoteRecord> votes = history.getRecentVotes(100);
            boolean first = true;
            for (VoteHistory.VoteRecord record : votes) {
                if (!first) sb.append(",");
                sb.append("{");
                sb.append("\"playerName\":\"").append(escapeJson(record.getPlayerName())).append("\",");
                sb.append("\"serviceName\":\"").append(escapeJson(record.getServiceName())).append("\",");
                sb.append("\"address\":\"").append(escapeJson(record.getAddress())).append("\",");
                sb.append("\"timestamp\":").append(record.getTimestamp());
                sb.append("}");
                first = false;
            }
            sb.append("]}");
            sendResponse(exchange, 200, sb.toString());
        }
    }
    
    class SkinHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!validateKey(exchange)) {
                    sendResponse(exchange, 401, "{\"error\":\"Unauthorized\",\"message\":\"Invalid or missing API key\"}");
                    return;
                }
                
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\",\"message\":\"Use POST method\"}");
                    return;
                }
                
                String requestBody = readRequestBody(exchange.getRequestBody());
                
                String player = null;
                String skinUrl = null;
                String skinType = "classic";
                
                if (requestBody.startsWith("{")) {
                    org.json.simple.JSONObject json = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(requestBody);
                    player = (String) json.get("player");
                    skinUrl = (String) json.get("skinUrl");
                    if (json.get("skinType") != null) {
                        skinType = (String) json.get("skinType");
                    }
                } else {
                    String[] params = requestBody.split("&");
                    for (String param : params) {
                        String[] keyValue = param.split("=", 2);
                        if (keyValue.length == 2) {
                            String key = java.net.URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
                            String value = java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                            switch (key) {
                                case "player":
                                    player = value;
                                    break;
                                case "skinUrl":
                                    skinUrl = value;
                                    break;
                                case "skinType":
                                    skinType = value;
                                    break;
                            }
                        }
                    }
                }
                
                if (player == null || player.isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\":\"Bad Request\",\"message\":\"Missing player parameter\"}");
                    return;
                }
                
                if (skinUrl == null || skinUrl.isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\":\"Bad Request\",\"message\":\"Missing skinUrl parameter\"}");
                    return;
                }
                
                Object skinsRestorer = ApiCommandPlugin.getInstance().getSkinsRestorer();
                if (skinsRestorer == null) {
                    sendResponse(exchange, 503, "{\"error\":\"Service Unavailable\",\"message\":\"SkinsRestorer not installed\"}");
                    return;
                }
                
                final String finalPlayer = player;
                final String finalSkinUrl = skinUrl;
                final String finalSkinType = skinType;
                
                Bukkit.getScheduler().runTask(ApiCommandPlugin.getInstance(), () -> {
                    try {
                        org.bukkit.entity.Player onlinePlayer = Bukkit.getPlayerExact(finalPlayer);
                        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                            ApiCommandPlugin.getInstance().getLogger().warning("玩家 " + finalPlayer + " 不在线，无法设置皮肤");
                            return;
                        }
                        
                        String command = "skin url " + finalSkinUrl;
                        onlinePlayer.performCommand(command);
                        
                        ApiCommandPlugin.getInstance().getLogger().info("为 " + finalPlayer + " 设置皮肤: " + finalSkinUrl);
                        
                    } catch (Exception e) {
                        ApiCommandPlugin.getInstance().getLogger().warning("设置皮肤失败: " + e.getMessage());
                    }
                });
                
                sendResponse(exchange, 200, "{\"success\":true,\"message\":\"Skin set for " + escapeJson(player) + "\"}");
                
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }
    
    class SkinClearHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!validateKey(exchange)) {
                    sendResponse(exchange, 401, "{\"error\":\"Unauthorized\",\"message\":\"Invalid or missing API key\"}");
                    return;
                }
                
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendResponse(exchange, 405, "{\"error\":\"Method Not Allowed\",\"message\":\"Use POST method\"}");
                    return;
                }
                
                String requestBody = readRequestBody(exchange.getRequestBody());
                
                String player = null;
                
                if (requestBody.startsWith("{")) {
                    org.json.simple.JSONObject json = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(requestBody);
                    player = (String) json.get("player");
                } else {
                    String[] params = requestBody.split("&");
                    for (String param : params) {
                        String[] keyValue = param.split("=", 2);
                        if (keyValue.length == 2 && "player".equals(keyValue[0])) {
                            player = java.net.URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8);
                            break;
                        }
                    }
                }
                
                if (player == null || player.isEmpty()) {
                    sendResponse(exchange, 400, "{\"error\":\"Bad Request\",\"message\":\"Missing player parameter\"}");
                    return;
                }
                
                Object skinsRestorer = ApiCommandPlugin.getInstance().getSkinsRestorer();
                if (skinsRestorer == null) {
                    sendResponse(exchange, 503, "{\"error\":\"Service Unavailable\",\"message\":\"SkinsRestorer not installed\"}");
                    return;
                }
                
                final String finalPlayer = player;
                
                Bukkit.getScheduler().runTask(ApiCommandPlugin.getInstance(), () -> {
                    try {
                        org.bukkit.entity.Player onlinePlayer = Bukkit.getPlayerExact(finalPlayer);
                        if (onlinePlayer == null || !onlinePlayer.isOnline()) {
                            ApiCommandPlugin.getInstance().getLogger().warning("玩家 " + finalPlayer + " 不在线，无法清除皮肤");
                            return;
                        }
                        
                        onlinePlayer.performCommand("skin clear");
                        
                        ApiCommandPlugin.getInstance().getLogger().info("已清除玩家 " + finalPlayer + " 的皮肤");
                    } catch (Exception e) {
                        ApiCommandPlugin.getInstance().getLogger().warning("清除皮肤失败: " + e.getMessage());
                        e.printStackTrace();
                    }
                });
                
                String response = "{\"success\":true,\"message\":\"Skin cleared for " + finalPlayer + "\"}";
                sendResponse(exchange, 200, response);
                
            } catch (Exception e) {
                sendResponse(exchange, 500, "{\"error\":\"Internal Server Error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
            }
        }
    }
}
