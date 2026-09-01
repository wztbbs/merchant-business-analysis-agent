package com.merchant.analysis.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * 依赖归一化：把显式声明的流程依赖（depends_on）与 inputs 携带的数据依赖合并去重，
 * 得到步骤最终的依赖边。
 */
final class DependencyNormalizer {

    private DependencyNormalizer() {
    }

    static List<String> mergeControlAndDataDependencies(List<String> controlDependencies,
                                                        List<String> dataDependencies) {
        List<String> merged = new ArrayList<>(controlDependencies);
        for (String dep : dataDependencies) {
            if (!merged.contains(dep)) {
                merged.add(dep);
            }
        }
        return merged;
    }
}
