package com.example.processautomation.change;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Compares master fields without interpreting blank wastewater/waste inputs as deletion or zero. */
public final class MasterFieldChangePolicy {
    private static final Set<String> FLOW_FIELDS = Set.of(
            "폐기물종류", "폐기물량톤일", "폐기물이동처", "폐기물이동",
            "폐수종류", "폐수량톤일", "폐수이동처", "폐수이동량처");

    private MasterFieldChangePolicy() { }

    public static Result compare(Map<String, String> before, Map<String, String> uploaded) {
        Map<String, FieldValue> oldFields = normalize(before);
        Map<String, FieldValue> newFields = normalize(uploaded);
        Set<String> normalizedKeys = new LinkedHashSet<>(oldFields.keySet());
        normalizedKeys.addAll(newFields.keySet());
        Map<String, ChangeDetectionResult.FieldChange> changes = new LinkedHashMap<>();
        Map<String, String> reviewItems = new LinkedHashMap<>();
        for (String key : normalizedKeys) {
            FieldValue oldField = oldFields.get(key);
            FieldValue newField = newFields.get(key);
            String oldValue = oldField == null ? "" : oldField.value();
            String newValue = newField == null ? "" : newField.value();
            String displayName = newField != null ? newField.header() : oldField.header();
            if (FLOW_FIELDS.contains(key) && !oldValue.isBlank() && newValue.isBlank()) {
                reviewItems.put(displayName, "Uploaded input is blank; existing baseline value '" + oldValue
                        + "' was retained. Blank is not treated as zero or deletion; explicit confirmation is required.");
                continue;
            }
            if (!oldValue.equals(newValue)) {
                changes.put(displayName, new ChangeDetectionResult.FieldChange(oldValue, newValue));
            }
        }
        return new Result(Map.copyOf(changes), Map.copyOf(reviewItems));
    }

    private static Map<String, FieldValue> normalize(Map<String, String> source) {
        Map<String, FieldValue> normalized = new LinkedHashMap<>();
        if (source == null) return normalized;
        source.forEach((header, value) -> {
            String key = normalizeHeader(header);
            if (!key.isBlank()) normalized.putIfAbsent(key, new FieldValue(header,
                    value == null ? "" : value.trim()));
        });
        return normalized;
    }

    private static String normalizeHeader(String header) {
        return header == null ? "" : header.toLowerCase(Locale.ROOT).replaceAll("[\\s._()（）·&/\\-]", "");
    }

    private record FieldValue(String header, String value) { }

    public record Result(Map<String, ChangeDetectionResult.FieldChange> changedFields,
                         Map<String, String> preservedBlankFlowInputs) { }
}
