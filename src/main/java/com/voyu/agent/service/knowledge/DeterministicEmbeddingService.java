package com.voyu.agent.service.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class DeterministicEmbeddingService implements EmbeddingService {

    @Value("${voyu.rag.embedding-dim:64}")
    private int dimension;

    public int dimension() {
        return dimension;
    }

    @Override
    public List<Float> embed(String text) {
        float[] vector = new float[dimension];
        List<String> tokens = tokenize(text == null ? "" : text.toLowerCase(Locale.ROOT));

        for (String token : tokens) {
            int hash = token.hashCode();
            int index = Math.floorMod(hash, dimension);
            float weight = 1.0f + Math.min(token.length(), 6) * 0.08f;
            float sign = ((hash >>> 1) & 1) == 0 ? 1.0f : -1.0f;
            vector[index] += sign * weight;
        }

        float norm = 0.0f;
        for (float value : vector) {
            norm += value * value;
        }
        norm = (float) Math.sqrt(norm);
        if (norm == 0.0f) {
            norm = 1.0f;
        }

        List<Float> result = new ArrayList<>(dimension);
        for (float value : vector) {
            result.add(value / norm);
        }
        return result;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        List<Character> compactChars = new ArrayList<>();

        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isLetterOrDigit(current)) {
                word.append(current);
                compactChars.add(current);
                if (isCjk(current)) {
                    tokens.add(String.valueOf(current));
                }
            } else {
                flushWord(tokens, word);
            }
        }
        flushWord(tokens, word);

        for (int i = 0; i < compactChars.size() - 1; i++) {
            char left = compactChars.get(i);
            char right = compactChars.get(i + 1);
            tokens.add(new String(new char[]{left, right}));
        }

        return tokens;
    }

    private void flushWord(List<String> tokens, StringBuilder word) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private boolean isCjk(char value) {
        Character.UnicodeScript script = Character.UnicodeScript.of(value);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA;
    }
}
