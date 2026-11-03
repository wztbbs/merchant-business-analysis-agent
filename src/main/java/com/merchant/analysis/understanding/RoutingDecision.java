package com.merchant.analysis.understanding;

/** 模型只提议匹配；最终路由由领域、覆盖范围和上下文校验共同确定。 */
public record RoutingDecision(Mode route, String matched_playbook, String matched_react_scene, String reason) {
    public enum Mode { PLAYBOOK, REACT, HISTORY, CLARIFY, OUT_OF_DOMAIN }
}
