package com.qqmu.jargus.service.version;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;

/** 两个发布源共用的 JSON 读取与路径段编码。 */
final class ReleaseSupport {

    private ReleaseSupport() {
    }

    /** 读取字符串字段；缺失、null 或空白都返回 null。 */
    static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    /** URL 路径段编码：空格用 %20 而不是 +。 */
    static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
