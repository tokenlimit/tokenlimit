package com.tokenlimit.server.enums;

import java.util.Arrays;
import java.util.List;

/**
 * 已知大模型厂商端点（PRD V5.0 §9.2 Provider）.
 * <p>当 {@code ProviderCredential.apiBaseUrl} 未配置时，以本枚举中的默认上游地址兜底；
 * 地址均为 OpenAI Compatible Base URL，可直接拼接 {@code /chat/completions}、{@code /embeddings} 等路径。</p>
 * <p>兼容性说明：{@link #isOpenAiCompatible()} 标记该厂商是否兼容 OpenAI 协议可直接 HTTP 透传；
 * 不兼容厂商（如 Anthropic）需要协议转换 Adapter，MVP 阶段不放入直接透传模板。</p>
 */
public enum LlmProvider {

    OPENAI("openai", "OpenAI", "https://api.openai.com/v1", true, false),
    DEEPSEEK("deepseek", "DeepSeek", "https://api.deepseek.com/v1", true, false),
    QWEN("qwen", "阿里云百炼（通义）", "https://dashscope.aliyuncs.com/compatible-mode/v1", true, false,
            "dashscope", "aliyun-bailian"),
    MOONSHOT("moonshot", "月之暗面（Kimi）", "https://api.moonshot.cn/v1", true, false),
    YI("yi", "零一万物（Yi）", "https://api.lingyiwanwu.com/v1", true, false, "lingyi"),
    BAICHUAN("baichuan", "百川智能", "https://api.baichuan-ai.com/v1", true, false),
    MINIMAX("minimax", "MiniMax", "https://api.minimax.chat/v1", true, false),
    SILICONFLOW("siliconflow", "硅基流动", "https://api.siliconflow.cn/v1", true, false),
    OPENROUTER("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", true, false),
    ZHIPU("zhipu", "智谱 AI（GLM）", "https://open.bigmodel.cn/api/paas/v4", true, false),
    /** 火山方舟（豆包）：URL 需拼接控制台创建的推理接入点 Endpoint ID，如 /api/v3/ep-xxxx */
    VOLCENGINE("volcengine", "火山方舟（豆包）", "https://ark.cn-beijing.volces.com/api/v3", true, true),
    XAI("xai", "xAI（Grok）", "https://api.x.ai/v1", true, false),
    GEMINI("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", true, false),
    MISTRAL("mistral", "Mistral AI", "https://api.mistral.ai/v1", true, false),
    GROQ("groq", "Groq", "https://api.groq.com/openai/v1", true, false),
    STEPFUN("stepfun", "阶跃星辰", "https://api.stepfun.com/v1", true, false),
    JINA("jina", "Jina AI", "https://api.jina.ai/v1", true, false),
    OLLAMA("ollama", "Ollama", "http://localhost:11434/v1", true, false),
    /** Anthropic 原生 API 不兼容 OpenAI 协议，需协议转换 Adapter，MVP 阶段不直接透传 */
    ANTHROPIC("anthropic", "Anthropic（Claude）", "https://api.anthropic.com/v1", false, false);

    private final String code;
    private final String displayName;
    private final String defaultBaseUrl;
    /** 是否兼容 OpenAI 协议，可 HTTP 直接透传 */
    private final boolean openAiCompatible;
    /** 是否需要在 Base URL 后拼接 Endpoint ID（如火山方舟） */
    private final boolean requiresEndpoint;
    /** 历史/别名编码，用于向后兼容匹配 */
    private final String[] aliases;

    LlmProvider(String code, String displayName, String defaultBaseUrl,
                boolean openAiCompatible, boolean requiresEndpoint, String... aliases) {
        this.code = code;
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.openAiCompatible = openAiCompatible;
        this.requiresEndpoint = requiresEndpoint;
        this.aliases = aliases;
    }

    public String getCode() {
        return code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public boolean isOpenAiCompatible() {
        return openAiCompatible;
    }

    public boolean isRequiresEndpoint() {
        return requiresEndpoint;
    }

    /**
     * 是否可直接透传的预设模板（OpenAI 兼容 且 无需额外拼接 Endpoint）.
     */
    public boolean isDirectPassthrough() {
        return openAiCompatible && !requiresEndpoint;
    }

    /**
     * 按 provider 编码查找（忽略大小写，兼容别名）；未知编码返回 null.
     */
    public static LlmProvider fromCode(String provider) {
        if (provider == null || provider.isBlank()) {
            return null;
        }
        String normalized = provider.trim();
        for (LlmProvider p : values()) {
            if (p.code.equalsIgnoreCase(normalized)) {
                return p;
            }
            for (String alias : p.aliases) {
                if (alias.equalsIgnoreCase(normalized)) {
                    return p;
                }
            }
        }
        return null;
    }

    /**
     * 获取默认上游地址；未知编码返回 null（由调用方决定兜底）.
     */
    public static String defaultBaseUrl(String provider) {
        LlmProvider p = fromCode(provider);
        return p == null ? null : p.defaultBaseUrl;
    }

    /**
     * 内置模板列表（供控制台下拉选择）.
     */
    public static List<LlmProvider> templates() {
        return Arrays.asList(values());
    }
}
