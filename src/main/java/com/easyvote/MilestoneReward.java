package com.easyvote;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.logging.Logger;

/** Commands stay compatible with older configurations; display follows LiuInvite's format. */
record MilestoneReward(int count, List<String> commands, String description, Map<?, ?> display) {
    static List<MilestoneReward> load(List<?> entries, Logger logger) {
        Map<Integer, MilestoneReward> rewards = new TreeMap<>();
        if (entries == null) return List.of();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> map) || !(map.get("count") instanceof Number number)
                    || number.intValue() <= 0 || number.doubleValue() != number.intValue()) {
                logger.warning("忽略无效里程碑：count 必须是正整数");
                continue;
            }
            int count = number.intValue();
            Map<Object, Object> displayValues = new HashMap<>();
            if (map.get("display") instanceof Map<?, ?> value) {
                value.forEach((key, item) -> { if (key != null && item != null) displayValues.put(key, item); });
            }
            Map<?, ?> display = Map.copyOf(displayValues);
            String description = string(map, "description", "");
            if (description.isBlank()) description = String.join(" / ", strings(display.get("lore")));
            if (description.isBlank()) description = string(display, "name", "累计投票 " + count + " 次奖励");
            MilestoneReward reward = new MilestoneReward(count, strings(map.get("commands")), description, display);
            if (rewards.putIfAbsent(count, reward) != null) {
                logger.warning("忽略重复的里程碑 count: " + count);
            }
        }
        return List.copyOf(rewards.values());
    }

    static String string(Map<?, ?> map, String key, String fallback) {
        return map.get(key) instanceof String text ? text : fallback;
    }

    static List<String> strings(Object value) {
        List<String> result = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof String text && !text.isBlank()) result.add(text.trim());
            }
        }
        return List.copyOf(result);
    }

    static List<String> lore(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
}
