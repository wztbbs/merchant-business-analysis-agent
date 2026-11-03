package com.merchant.analysis.llm;

/**
 * LLM 访问统一入口。demo 默认走 {@link MockLlmClient}（离线可跑）；
 * 切换 llm.provider=openai 后启用 {@link OpenAiCompatibleClient}（一切 OpenAI 兼容服务通用）。
 */
public interface LlmClient {

    /** 单轮对话：输入渲染好的 prompt，输出模型文本 */
    String chat(String prompt);

    /** 运行时仍设置外层硬时限；网络实现应同时使用此剩余预算设置连接和读取时限。 */
    default String chat(String prompt, java.time.Duration timeout) { return chat(prompt); }
}
