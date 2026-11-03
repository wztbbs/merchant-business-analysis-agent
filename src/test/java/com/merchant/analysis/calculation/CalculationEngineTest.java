package com.merchant.analysis.calculation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CalculationEngineTest {
    private final CalculationEngine engine = new CalculationEngine();
    private Map<String, Object> call(String name, Map<String, Object> input) { return engine.execute(name, Map.of("input", input)); }
    private Map<String, Object> values(String name, Object... values) { return call(name, Map.of("kind", "values", "values", Arrays.asList(values))); }

    @Test void exactDecimalSumAndDifference() {
        assertEquals("0.3", values("sum", 0.1, "0.2").get("value"));
        assertEquals("0.1", call("difference", Map.of("left", "0.3", "right", "0.2")).get("value"));
        assertEquals("9007199254740994", values("sum", "9007199254740993", 1).get("value"));
    }
    @Test void averageUsesDecimal128AndEmitsCount() {
        var out = values("avg", 1, 0, 0);
        assertEquals("0.3333333333333333333333333333333333", out.get("value"));
        assertEquals(3, out.get("count"));
    }
    @ParameterizedTest @CsvSource({"min,-5,0", "max,-2,1"})
    void negativeExtremesAndTiesKeepFirst(String name, String expected, int index) {
        var result = values(name, -5, -2, -2);
        assertEquals(expected, result.get("value"));
        assertEquals(index, result.get("index"));
    }
    @Test void fieldModeRetainsActualExtremeRow() {
        var rows = List.of(Map.of("sku_id", "a", "delta", -5), Map.of("sku_id", "b", "delta", 10));
        var result = call("max", Map.of("kind", "field", "collection", rows, "field", "delta"));
        assertEquals(rows.get(1), result.get("item"));
        assertEquals("10", result.get("value"));
    }
    @Test void pairwiseDirectionsAreDistinct() {
        assertEquals("20", call("difference", Map.of("left", 120, "right", 100)).get("value"));
        assertEquals("1.2", call("ratio", Map.of("left", 120, "right", 100)).get("value"));
        assertEquals("20", call("relative_change", Map.of("current", 120, "baseline", 100)).get("percent"));
        assertEquals("equal", call("compare", Map.of("left", "1.00", "right", 1)).get("relation"));
        assertEquals("-1", call("compare", Map.of("left", -10, "right", -5)).get("value"));
        assertEquals("-20", call("difference", Map.of("left", 100, "right", 120)).get("value"));
    }
    @ParameterizedTest @ValueSource(strings = {"NaN", "Infinity", "1%", "1元", "1+2", "1,000", "1e1001"})
    void rejectsInvalidNumericValues(String value) { assertThrows(IllegalArgumentException.class, () -> values("sum", value)); }
    @Test void rejectsNullMissingEmptyAndOversizedCollections() {
        assertThrows(IllegalArgumentException.class, () -> values("sum", new Object[]{null}));
        assertThrows(IllegalArgumentException.class, () -> values("avg"));
        assertThrows(IllegalArgumentException.class, () -> call("sum", Map.of("kind", "values", "values", Collections.nCopies(10001, 1))));
        assertThrows(IllegalArgumentException.class, () -> call("avg", Map.of("kind", "field", "collection", List.of(Map.of("other", 1)), "field", "value")));
        assertThrows(IllegalArgumentException.class, () -> call("ratio", Map.of("left", 1, "right", 0)));
        assertThrows(IllegalArgumentException.class, () -> call("relative_change", Map.of("current", 1, "baseline", -1)));
        assertThrows(IllegalArgumentException.class, () -> call("ratio", Map.of("left", 1, "right", 2, "extra", 3)));
        assertEquals(-0.5, DecimalMath.quotient(1, -2)); // 业务贡献率允许带符号分母。
    }
}
