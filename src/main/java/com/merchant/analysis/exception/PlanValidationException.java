package com.merchant.analysis.exception;

/** plan 结构/供给校验失败：说明 plan 生成逻辑或 playbook 配置有缺陷，应当快速失败 */
public class PlanValidationException extends RuntimeException {

    public PlanValidationException(String message) {
        super(message);
    }
}
