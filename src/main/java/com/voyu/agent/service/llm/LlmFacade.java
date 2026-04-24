package com.voyu.agent.service.llm;

public interface LlmFacade {

    String complete(String systemPrompt, String userPrompt);
}
