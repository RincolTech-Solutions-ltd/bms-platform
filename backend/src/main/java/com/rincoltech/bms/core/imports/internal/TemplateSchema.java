package com.rincoltech.bms.core.imports.internal;

import com.rincoltech.bms.core.imports.ImportTemplate;
import com.rincoltech.bms.core.imports.ImportTemplate.EntitySet;
import com.rincoltech.bms.core.imports.ImportTemplate.Field;
import com.rincoltech.bms.core.imports.ImportTemplate.Kind;
import com.rincoltech.bms.core.imports.ImportTemplate.RowRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schema (draft 2020-12) of a mapping for one template version (issue #129, spec section 4.2), built from the
 * template, as plain maps and lists. The shared shape is the same for every template; each entity set narrows a source that
 * names it to its own fields, its enum values and the row rules it offers, through {@code if}/{@code then}. It uses only the
 * keywords {@link SchemaCheck} interprets, so the document the service publishes is exactly the one it checks with. Plain
 * Java, no Spring.
 */
final class TemplateSchema {

    static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";

    /** The date conventions of spec section 4.2. */
    static final List<String> DATE_CONVENTIONS = List.of("day_first", "month_first", "iso");

    /** A source's key in the mapping: lower case, as a template key. */
    static final String SOURCE_KEY = "^[a-z][a-z0-9_]{0,39}$";

    /** The fixed header rows a mapping may name: the rows a header is looked for in (spec section 3.4.1). */
    static final int MAX_HEADER_ROW = Parsing.HEADER_SEARCH_ROWS;

    private TemplateSchema() {}

    static Map<String, Object> of(ImportTemplate template) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("$schema", DRAFT);
        schema.put("$id", "urn:bms:imports:mapping:" + template.id());
        schema.put("title", "Mapping for the template " + template.id());
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put(
                "required", List.of("template", "template_version", "date_convention", "time_zone", "currency", "sources"));
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("template", Map.of("const", template.key()));
        properties.put("template_version", Map.of("const", template.version()));
        properties.put("date_convention", Map.of("enum", DATE_CONVENTIONS));
        properties.put("time_zone", text(1, 64));
        properties.put("currency", Map.of("type", "string", "pattern", "^[A-Z]{3}$"));
        properties.put(
                "unit_maps", Map.of("type", "object", "propertyNames", text(1, 100), "additionalProperties", text(1, 40)));
        properties.put(
                "sources",
                Map.of(
                        "type", "object",
                        "minProperties", 1,
                        "propertyNames", Map.of("type", "string", "pattern", SOURCE_KEY),
                        "additionalProperties", Map.of("$ref", "#/$defs/source")));
        schema.put("properties", properties);
        Map<String, Object> defs = new LinkedHashMap<>();
        defs.put("source", source(template));
        defs.put("ignored", ignored());
        defs.put("source_ref", sourceRef());
        for (EntitySet set : template.entitySets()) {
            defs.put("entity_" + set.key(), entitySet(set));
        }
        schema.put("$defs", defs);
        return schema;
    }

    /** The shape every source shares; the entity set it names narrows it. */
    private static Map<String, Object> source(ImportTemplate template) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "object");
        source.put("additionalProperties", false);
        source.put("required", List.of("entity_set", "header_row", "columns", "source_ref"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(
                "entity_set",
                Map.of("enum", template.entitySets().stream().map(EntitySet::key).toList()));
        p.put("file", text(1, 255));
        p.put("sheet", text(1, 100));
        p.put(
                "header_row",
                Map.of(
                        "anyOf",
                        List.of(
                                Map.of("const", "auto"),
                                Map.of("type", "integer", "minimum", 1, "maximum", MAX_HEADER_ROW))));
        p.put(
                "columns",
                Map.of(
                        "type", "object",
                        "minProperties", 1,
                        "propertyNames", text(1, 200),
                        "additionalProperties",
                                Map.of(
                                        "anyOf",
                                        List.of(
                                                Map.of(
                                                        "type", "object",
                                                        "additionalProperties", false,
                                                        "required", List.of("field"),
                                                        "properties", Map.of("field", text(1, 64))),
                                                Map.of("$ref", "#/$defs/ignored")))));
        Map<String, Object> labelMap =
                Map.of("type", "object", "propertyNames", text(1, 200), "additionalProperties", text(0, 200));
        p.put("value_maps", Map.of("type", "object", "additionalProperties", labelMap));
        p.put("enum_maps", Map.of("type", "object", "additionalProperties", labelMap));
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put(RowRule.SUBHEADER_MONTH.key(), Map.of("type", "boolean"));
        rules.put(RowRule.TOTALS_ROW.key(), Map.of("enum", List.of("first", "last")));
        rules.put(
                RowRule.PLACEHOLDER_ROWS.key(),
                Map.of("type", "array", "minItems", 1, "maxItems", 50, "items", text(1, 100)));
        p.put("row_rules", Map.of("type", "object", "additionalProperties", false, "properties", rules));
        p.put("source_ref", Map.of("$ref", "#/$defs/source_ref"));
        source.put("properties", p);
        List<Object> narrowing = new ArrayList<>();
        for (EntitySet set : template.entitySets()) {
            narrowing.add(Map.of(
                    "if", Map.of("required", List.of("entity_set"), "properties", Map.of("entity_set", Map.of("const", set.key()))),
                    "then", Map.of("$ref", "#/$defs/entity_" + set.key())));
        }
        source.put("allOf", narrowing);
        return source;
    }

    /** What one entity set allows in a source that names it. */
    private static Map<String, Object> entitySet(EntitySet set) {
        List<String> fields = set.fields().stream().map(Field::name).toList();
        Map<String, Object> enumMaps = new LinkedHashMap<>();
        for (Field f : set.fields()) {
            if (f.type().kind() == Kind.ENUM) {
                enumMaps.put(
                        f.name(),
                        Map.of(
                                "type", "object",
                                "propertyNames", text(1, 200),
                                "additionalProperties", Map.of("enum", f.type().values())));
            }
        }
        Map<String, Object> p = new LinkedHashMap<>();
        p.put(
                "columns",
                Map.of(
                        "additionalProperties",
                        Map.of(
                                "anyOf",
                                List.of(
                                        Map.of(
                                                "type", "object",
                                                "required", List.of("field"),
                                                "properties", Map.of("field", Map.of("enum", fields))),
                                        Map.of("$ref", "#/$defs/ignored")))));
        p.put("value_maps", Map.of("propertyNames", Map.of("enum", fields)));
        p.put(
                "enum_maps",
                Map.of(
                        "propertyNames", Map.of("enum", List.copyOf(enumMaps.keySet())),
                        "properties", enumMaps));
        p.put(
                "row_rules",
                Map.of("propertyNames", Map.of("enum", set.rowRules().stream().map(RowRule::key).sorted().toList())));
        Map<String, Object> narrowed = new LinkedHashMap<>();
        narrowed.put("properties", p);
        return narrowed;
    }

    private static Map<String, Object> ignored() {
        return Map.of(
                "type", "object",
                "additionalProperties", false,
                "required", List.of("ignore", "reason"),
                "properties", Map.of("ignore", Map.of("const", true), "reason", text(3, 200)));
    }

    private static Map<String, Object> sourceRef() {
        return Map.of(
                "anyOf",
                List.of(
                        Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "required", List.of("rule"),
                                "properties", Map.of("rule", Map.of("const", "row_hash"))),
                        Map.of(
                                "type", "object",
                                "additionalProperties", false,
                                "required", List.of("rule", "column"),
                                "properties", Map.of("rule", Map.of("const", "column"), "column", text(1, 200)))));
    }

    private static Map<String, Object> text(int min, int max) {
        return Map.of("type", "string", "minLength", min, "maxLength", max);
    }
}
