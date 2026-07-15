package com.mengying.fqnovel.config;

import com.mengying.fqnovel.utils.Texts;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

public class LenientIntegerDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctxt) throws JacksonException {
        JsonToken token = p.currentToken();
        if (token == null) {
            token = p.nextToken();
        }

        if (token == JsonToken.VALUE_NUMBER_INT) {
            return p.getIntValue();
        }
        if (token == JsonToken.VALUE_NUMBER_FLOAT) {
            return (int) p.getDoubleValue();
        }
        if (token == JsonToken.VALUE_TRUE) {
            return 1;
        }
        if (token == JsonToken.VALUE_FALSE) {
            return 0;
        }
        if (token == JsonToken.VALUE_STRING) {
            String s = Texts.trimToNull(p.getValueAsString());
            if (s == null || "null".equalsIgnoreCase(s)) {
                return null;
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                try {
                    return (int) Double.parseDouble(s);
                } catch (NumberFormatException ignored2) {
                    return null;
                }
            }
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }

        JsonNode node = p.readValueAsTree();
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.intValue();
        }
        if (node.isString()) {
            String s = Texts.trimToNull(node.asString(""));
            if (s == null || "null".equalsIgnoreCase(s)) return null;
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        if (node.isBoolean()) {
            return node.booleanValue() ? 1 : 0;
        }
        return null;
    }
}
