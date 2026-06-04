package com.longcli.memory;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 简单的查询分词器
 *
 * 用于记忆检索时的关键词匹配。
 * 将文本分割成有意义的词语，过滤单字和标点。
 */
final class MemoryQueryTokenizer {

    private MemoryQueryTokenizer() {
    }

    /**
     * 对查询文本进行分词，返回用于检索匹配的 token 集合。
     * 简单实现：按空格和标点分割，中文单字也保留。
     */
    static Set<String> tokenize(String query) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        if (query == null || query.isBlank()) {
            return tokens;
        }

        // 简单分词：按非字母数字字符分割
        String[] words = query.toLowerCase().split("[^\\p{L}\\p{N}]+");
        for (String word : words) {
            String trimmed = word.trim();
            if (trimmed.isEmpty()) continue;

            // 对于中文，允许保留单字；对于英文，仍然保留至少2个字符
            boolean isChinese = trimmed.codePoints().anyMatch(cp -> (cp >= 0x4E00 && cp <= 0x9FFF));
            if (isChinese || trimmed.length() >= 2) {
                tokens.add(trimmed);
            }

            // 对于中文，还添加单个字到 tokens 中，增加匹配机会
            if (isChinese) {
                for (int i = 0; i < trimmed.length(); i++) {
                    tokens.add(String.valueOf(trimmed.charAt(i)));
                }
            }
        }
        return tokens;
    }

    /**
     * 检查文本中是否包含任意一个 query token（子串匹配）
     */
    static boolean matches(String text, Set<String> queryTokens) {
        if (text == null || text.isBlank() || queryTokens.isEmpty()) {
            return false;
        }

        String normalizedText = text.toLowerCase();
        for (String token : queryTokens) {
            if (normalizedText.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
