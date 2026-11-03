package com.merchant.analysis.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.execution.CapabilityCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ToolCatalogTest {
    private final ToolCatalog catalog = new ToolCatalog(new CapabilityCatalog());
    @Test void lightweightDirectoryHasNoSchemasAndAllDefinitionsAreValid() {
        assertEquals(18, catalog.summaries().size());
        var mapper = new ObjectMapper();
        catalog.summaries().forEach(summary -> {
            assertFalse(mapper.valueToTree(summary).has("input_schema"));
            var definition = catalog.definition(summary.name());
            assertEquals(summary.kind(), definition.kind());
            assertTrue(definition.inputSchema().has("properties"));
            assertTrue(definition.outputSchema().has("properties"));
            assertFalse(definition.inputSchema().toString().contains("common:"));
        });
    }
    @Test void definitionSnapshotsCannotMutateCachedSchemas() {
        var first = catalog.definition("sum");
        ((com.fasterxml.jackson.databind.node.ObjectNode) first.inputSchema()).removeAll();
        assertTrue(catalog.definition("sum").inputSchema().has("properties"));
        assertThrows(IllegalArgumentException.class, () -> catalog.definition("../common"));
    }
}
