package com.tokenlimit.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Service;

/**
 * Token 预估服务（V5.0）.
 * <p>统一使用 jtokkit 作为 token 预估基准，用于：
 * <ul>
 *   <li>请求发出时估算 prompt_tokens；</li>
 *   <li>流式中断时估算已转发的 completion 内容；</li>
 *   <li>厂商未返回 usage 时估算 prompt + completion。</li>
 * </ul>
 * 不追求精确，追求"接近真实值"。</p>
 */
@Service
public class TokenEstimationService {

    private static final EncodingType FALLBACK_ENCODING = EncodingType.O200K_BASE;
    private static final EncodingType LEGACY_ENCODING = EncodingType.CL100K_BASE;

    private final EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();

    /**
     * 估算文本 token 数（按模型选择编码，未知模型回退 O200K_BASE）.
     */
    public long estimateTokens(String model, String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return encodingFor(model).countTokens(text);
        } catch (Exception e) {
            // jtokkit 计数异常时按字符数/4 兜底
            return Math.max(text.length() / 4L, 1);
        }
    }

    /**
     * 估算请求的 prompt_tokens.
     * <p>chat/completions：遍历 messages 的 content（支持字符串与多模态数组）；
     * embeddings：估算 input；其余请求按整请求体估算。</p>
     */
    public long estimatePromptTokens(String model, JsonNode requestJson) {
        if (requestJson == null) {
            return 0;
        }
        JsonNode messages = requestJson.path("messages");
        if (messages.isArray()) {
            long sum = 0;
            for (JsonNode msg : messages) {
                sum += countMessageTokens(model, msg);
            }
            // 每条 message 的 role/分隔符近似开销
            sum += messages.size() * 4L;
            return sum;
        }
        JsonNode input = requestJson.path("input");
        if (input.isTextual()) {
            return estimateTokens(model, input.asText());
        }
        if (input.isArray()) {
            long sum = 0;
            for (JsonNode item : input) {
                sum += estimateTokens(model, item.isTextual() ? item.asText() : item.toString());
            }
            return sum;
        }
        return estimateTokens(model, requestJson.toString());
    }

    /**
     * 估算 completion_tokens（流式中断 / 厂商未返回 usage 时兜底）.
     */
    public long estimateCompletionTokens(String model, String content) {
        return estimateTokens(model, content);
    }

    private long countMessageTokens(String model, JsonNode msg) {
        JsonNode content = msg.path("content");
        if (content.isTextual()) {
            return estimateTokens(model, content.asText());
        }
        if (content.isArray()) {
            long sum = 0;
            for (JsonNode part : content) {
                JsonNode text = part.path("text");
                if (text.isTextual()) {
                    sum += estimateTokens(model, text.asText());
                }
                // 图片等非文本部分按固定开销近似
                if (part.has("image_url") || part.has("image")) {
                    sum += 85;
                }
            }
            return sum;
        }
        return 0;
    }

    private Encoding encodingFor(String model) {
        if (model != null && !model.isBlank()) {
            try {
                return registry.getEncodingForModel(model)
                        .orElseGet(() -> registry.getEncoding(FALLBACK_ENCODING));
            } catch (Exception ignore) {
                // 模型名无法识别，回退默认编码
            }
        }
        try {
            return registry.getEncoding(FALLBACK_ENCODING);
        } catch (Exception e) {
            return registry.getEncoding(LEGACY_ENCODING);
        }
    }
}
