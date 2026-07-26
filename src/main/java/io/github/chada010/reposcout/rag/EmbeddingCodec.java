package io.github.chada010.reposcout.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 向量与 JSON 文本互转:embedding 以 float[] 的 JSON 数组文本存入 doc_chunk.embedding
 * (维度不硬编码进表结构,便于换模型)。序列化/反序列化都走 Jackson,失败即抛
 * IllegalStateException——库里的向量文本本应始终合法,损坏属不可恢复的数据错误。
 */
@Component
public class EmbeddingCodec {

    private final ObjectMapper objectMapper;

    public EmbeddingCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(float[] vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("向量序列化失败", e);
        }
    }

    public float[] fromJson(String json) {
        try {
            return objectMapper.readValue(json, float[].class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("向量反序列化失败(embedding 文本损坏)", e);
        }
    }
}
