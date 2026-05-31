package com.easyvote;

import org.bukkit.Bukkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public class VotifierServer {

    private final EasyVotePlugin plugin;
    private final RSAKeyManager keyManager;
    private final int port;
    private final String host;
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean running;

    public VotifierServer(EasyVotePlugin plugin, RSAKeyManager keyManager, String host, int port, int maxThreads) {
        this.plugin = plugin;
        this.keyManager = keyManager;
        this.host = host;
        this.port = port;
        this.threadPool = Executors.newFixedThreadPool(maxThreads);
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port, 50, InetAddress.getByName(host));
        running = true;
        
        Thread serverThread = new Thread(this::runServer, "VotifierServer");
        serverThread.start();
        
        plugin.getLogger().info("Votifier 服务器已启动 (端口: " + port + ")");
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "停止 Votifier 服务器时出错", e);
        }
        if (threadPool != null) {
            threadPool.shutdown();
        }
        plugin.getLogger().info("Votifier 服务器已停止");
    }

    private void runServer() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (SocketException e) {
                if (running) {
                    plugin.getLogger().log(Level.WARNING, "接受连接时出错", e);
                }
            } catch (IOException e) {
                if (running) {
                    plugin.getLogger().log(Level.WARNING, "无法启动 Votifier 服务器", e);
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        String clientAddress = clientSocket.getInetAddress().getHostAddress();
        
        try (InputStream inputStream = clientSocket.getInputStream();
             OutputStream outputStream = clientSocket.getOutputStream()) {
            
            clientSocket.setSoTimeout(5000);
            
            outputStream.write("VOTIFIER 1.9".getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
            
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[256];
            int bytesRead;
            long startTime = System.currentTimeMillis();
            int maxWaitTime = 5000;
            
            while (System.currentTimeMillis() - startTime < maxWaitTime) {
                if (inputStream.available() > 0) {
                    bytesRead = inputStream.read(buffer);
                    if (bytesRead == -1) {
                        break;
                    }
                    baos.write(buffer, 0, bytesRead);
                    
                    if (baos.size() > 4 && baos.toString(StandardCharsets.UTF_8).endsWith("\n")) {
                        break;
                    }
                } else {
                    Thread.sleep(10);
                }
            }
            
            byte[] rawBytes = baos.toByteArray();
            
            if (rawBytes.length == 0) {
                plugin.getLogger().warning("[" + clientAddress + "] 未收到投票数据");
                return;
            }
            
            String rawDataString = new String(rawBytes, StandardCharsets.UTF_8).trim();
            
            if (rawDataString.startsWith("SSH-")) {
                return;
            }
            
            if (rawDataString.startsWith("{")) {
                handleVotifierV2(rawDataString, clientAddress);
                return;
            }
            
            String decryptedData;
            try {
                decryptedData = keyManager.decryptBytes(rawBytes);
            } catch (Exception e) {
                plugin.getLogger().warning("[" + clientAddress + "] 解密失败: " + e.getMessage());
                return;
            }
            
            decryptedData = decryptedData.replaceAll("[\r\n]+", " ").trim();
            
            if (decryptedData.startsWith("VOTIFIER") && !decryptedData.startsWith("VOTIFIER ") && !decryptedData.startsWith("VOTE ")) {
                handleCustomVotifierFormat(decryptedData, clientAddress);
                return;
            }
            
            if (!decryptedData.startsWith("VOTE ")) {
                plugin.getLogger().warning("[" + clientAddress + "] 无效的投票格式");
                return;
            }
            
            String voteData = decryptedData.substring(5).trim();
            String[] parts = voteData.split("\\s+");
            
            if (parts.length < 4) {
                plugin.getLogger().warning("[" + clientAddress + "] 投票数据格式错误");
                return;
            }
            
            String serviceName = parts[0];
            String username = parts[1];
            String address = parts[2];
            long timestamp;
            
            try {
                timestamp = Long.parseLong(parts[3]);
            } catch (NumberFormatException e) {
                timestamp = System.currentTimeMillis();
            }
            
            plugin.getLogger().info("收到投票: " + username + " 从 " + serviceName);
            
            VoteEvent event = new VoteEvent(username, serviceName, address, timestamp);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Bukkit.getPluginManager().callEvent(event);
            });
            
        } catch (SocketException e) {
            // 忽略连接重置
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("解密投票数据失败: " + e.getMessage());
        } catch (Exception e) {
            plugin.getLogger().warning("处理来自 " + clientAddress + " 的投票时出错: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                // 忽略关闭错误
            }
        }
    }
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 64); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        if (bytes.length > 64) sb.append("...");
        return sb.toString();
    }
    
    private void handleVotifierV2(String jsonData, String clientAddress) {
        try {
            org.json.simple.JSONObject json = (org.json.simple.JSONObject) new org.json.simple.parser.JSONParser().parse(jsonData);
            
            String serviceName = (String) json.get("serviceName");
            String username = (String) json.get("username");
            String address = (String) json.get("address");
            Long timestamp = (Long) json.get("timestamp");
            
            if (serviceName == null || username == null) {
                plugin.getLogger().warning("Votifier v2 数据缺少必要字段: " + jsonData);
                return;
            }
            
            if (timestamp == null) {
                timestamp = System.currentTimeMillis();
            }
            
            plugin.getLogger().info("收到 Votifier v2 投票: " + username + " 从 " + serviceName + " (" + address + ")");
            
            VoteEvent event = new VoteEvent(username, serviceName, address != null ? address : clientAddress, timestamp);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.getPluginManager().callEvent(event);
            });
            
        } catch (Exception e) {
            plugin.getLogger().warning("解析 Votifier v2 JSON 失败: " + e.getMessage());
        }
    }
    
    private void handleCustomVotifierFormat(String data, String clientAddress) {
        try {
            if (data.length() < 8 + 32 + 32 + 13) {
                plugin.getLogger().warning("[" + clientAddress + "] 自定义投票数据太短");
                return;
            }
            
            String content = data.substring(8);
            
            int i = 0;
            
            String ip = "";
            while (i < content.length()) {
                char c = content.charAt(i);
                if (Character.isDigit(c) || c == '.') {
                    ip += c;
                    i++;
                } else {
                    break;
                }
            }
            
            String port = "";
            while (i < content.length()) {
                char c = content.charAt(i);
                if (Character.isDigit(c)) {
                    port += c;
                    i++;
                } else {
                    break;
                }
            }
            
            String serviceName = content.substring(i, i + 32).trim();
            i += 32;
            
            String uuid = content.substring(i, i + 32);
            i += 32;
            
            String remaining = content.substring(i);
            String username;
            String timestampStr;
            
            if (remaining.length() >= 13) {
                timestampStr = remaining.substring(remaining.length() - 13);
                username = remaining.substring(0, remaining.length() - 13);
            } else {
                timestampStr = "";
                username = remaining;
            }
            
            if (username.isEmpty()) {
                plugin.getLogger().warning("[" + clientAddress + "] 无法解析自定义投票数据: " + data);
                return;
            }
            
            long timestamp;
            try {
                timestamp = timestampStr.isEmpty() ? System.currentTimeMillis() : Long.parseLong(timestampStr);
            } catch (NumberFormatException e) {
                timestamp = System.currentTimeMillis();
            }
            
            plugin.getLogger().info("收到自定义格式投票: " + username + " 从 " + serviceName + " (" + ip + ":" + port + ")");
            
            VoteEvent event = new VoteEvent(username, serviceName, ip, timestamp);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.getPluginManager().callEvent(event);
            });
            
        } catch (Exception e) {
            plugin.getLogger().warning("解析自定义投票格式失败: " + e.getMessage());
        }
    }
}
