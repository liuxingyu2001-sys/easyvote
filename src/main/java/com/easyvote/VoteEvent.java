package com.easyvote;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class VoteEvent extends Event {

    private static final HandlerList handlers = new HandlerList();
    private final String playerName;
    private final String serviceName;
    private final String address;
    private final long timestamp;

    public VoteEvent(String playerName, String serviceName, String address, long timestamp) {
        super(true);
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

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    public static HandlerList getHandlerList() {
        return handlers;
    }
}
