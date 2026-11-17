package f1.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * 全局共用的 JSON 工具(仅基础设施层使用,UI 不碰)。
 * - 属性名大小写不敏感:适应 Ergast/Jolpica 的 "MRData"/"StandingsTable" 等命名
 * - 忽略未知字段:第三方接口新增字段不影响我们
 */
public final class Json {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 序列化失败", e);
        }
    }
}
