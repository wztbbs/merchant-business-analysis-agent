package com.merchant.analysis.react;

import java.util.List;
import java.util.Map;

public record ReactResult(ReactStatus status, String answer, Map<String, Map<String, Object>> stepResults,
                          List<ReactState.Coverage> coverage, Map<String, Object> stats) {}
