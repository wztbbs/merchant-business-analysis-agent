package com.merchant.analysis.calculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import java.util.regex.Pattern;

/** 业务工具和通用算子共享的十进制运算；舍入由具体业务输出边界决定。 */
public final class DecimalMath {
    public static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private DecimalMath() {}

    public static BigDecimal decimal(Object value) {
        if (!(value instanceof Number || value instanceof String))
            throw new IllegalArgumentException("需要数值或无单位十进制字符串");
        String text = value.toString();
        if (text.length() > 2048 || !NUMBER.matcher(text).matches())
            throw new IllegalArgumentException("不接受表达式、单位、百分号、NaN 或 Infinity");
        BigDecimal number = new BigDecimal(text);
        if (number.precision() > 100 || Math.abs((long) number.scale()) > 1000)
            throw new IllegalArgumentException("数值超出范围：最多100位有效数字，scale绝对值不超过1000");
        return number;
    }
    public static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
    public static BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) throw new IllegalArgumentException("不能计算空集合均值");
        return divide(sum(values), BigDecimal.valueOf(values.size()));
    }
    public static BigDecimal subtract(BigDecimal left, BigDecimal right) { return left.subtract(right); }
    public static BigDecimal divide(BigDecimal left, BigDecimal right) {
        if (right.signum() == 0) throw new IllegalArgumentException("分母不能为零");
        return left.divide(right, PRECISION);
    }
    public static String plain(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }
    public static double difference(double left, double right) { return subtract(decimal(left), decimal(right)).doubleValue(); }
    public static double quotient(double left, double right) { return divide(decimal(left), decimal(right)).doubleValue(); }
}
