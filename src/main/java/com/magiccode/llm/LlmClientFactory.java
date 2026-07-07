package com.magiccode.llm;

import com.magiccode.config.ProviderConfig;

public class LlmClientFactory {

    public static LlmClient create(ProviderConfig cfg, String systemPrompt) {
        return LlmClient.create(cfg, systemPrompt);
    }
}
