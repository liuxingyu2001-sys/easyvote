package com.easyvote;

import com.liuxingyu.liulib.LiuLib;
import org.bukkit.inventory.ItemStack;

/**
 * 配置字符串 → 物品：优先走 LiuLib 1.0.5 的完整物品规格。
 *
 * <p>支持 {@code DIAMOND} / {@code minecraft:diamond} / {@code DIAMOND:3} / {@code DIAMOND*3} /
 * {@code ce:ns:id} / {@code craftengine:ns:id} / 裸 CE ID {@code ns:id} /
 * {@code base64:...} / {@code nbt:...} / {@code bytes:...} / {@code item:base64:...} /
 * {@code lib:物品库ID}（由 liuitemlib 提供）。</p>
 *
 * <p>只做解析：LiuLib 未启用、规格非法或反序列化失败时返回 {@code null}，
 * 由调用方回退原有 {@code Material.matchMaterial} 逻辑，保证老配置行为不变。</p>
 */
final class LiuLibItems {

    private LiuLibItems() {}

    /** 解析失败返回 null（不抛异常） */
    static ItemStack buildOrNull(String raw) {
        if (raw == null || raw.isBlank() || !LiuLib.isReady()) return null;
        try {
            ItemStack stack = LiuLib.items().buildOrNull(raw.trim());
            return stack == null ? null : stack.clone();
        } catch (RuntimeException error) {
            return null;
        }
    }
}
