package com.merchant.analysis.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.merchant.analysis.execution.CapabilityCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReactSceneCatalogTest {
    @Test void oneFilePerSceneLoadsLightweightSummariesAndSeparateGuidance() {
        var catalog = new ReactSceneCatalog(new CapabilityCatalog());
        assertEquals(4, catalog.summaries().size());
        var mapper = new ObjectMapper();
        for (var summary : catalog.summaries()) {
            var json = mapper.valueToTree(summary);
            assertFalse(json.has("guidance"));
            assertFalse(json.has("recommended_tools"));
            var full = catalog.get(summary.id());
            assertFalse(full.guidance().isBlank());
            assertFalse(full.recommended_tools().isEmpty());
        }
        assertFalse(catalog.contains("../../anything"));
        assertThrows(IllegalArgumentException.class, () -> catalog.get("../../anything"));
    }
}
