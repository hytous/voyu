package com.voyu.agent.service.knowledge;

import java.util.List;

public interface EmbeddingService {

    int dimension();

    List<Float> embed(String text);

    default List<List<Float>> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }
}
