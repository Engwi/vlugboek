package za.co.vlugboek.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

@Service
public class StructuredLogService {
    private final ObjectMapper objectMapper;

    public StructuredLogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void info(Logger logger, String event, Map<String, ?> fields) {
        logger.info(toJson("INFO", event, fields));
    }

    public void warn(Logger logger, String event, Map<String, ?> fields) {
        logger.warn(toJson("WARN", event, fields));
    }

    public Map<String, Object> fields(Object... values) {
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            Object key = values[index];
            Object value = values[index + 1];
            if (key != null && value != null) {
                fields.put(String.valueOf(key), value);
            }
        }
        return fields;
    }

    private String toJson(String level, String event, Map<String, ?> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("time", Instant.now().toString());
        payload.put("level", level);
        payload.put("event", event);
        if (fields != null) {
            payload.putAll(fields);
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            return "{\"level\":\"" + level + "\",\"event\":\"" + event + "\"}";
        }
    }
}
