package com.prism.gateway.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.prism.gateway.config.ConfigFiles.PriceTable;
import com.prism.gateway.config.ModelPrice;

class CostCalculatorTest {

    private final CostCalculator calculator = new CostCalculator(new PriceTable(Map.of(
            "alpha-small", new ModelPrice("alpha-small", new BigDecimal("0.15"), new BigDecimal("0.60")),
            "alpha-large", new ModelPrice("alpha-large", new BigDecimal("3.00"), new BigDecimal("15.00")))));

    @Test
    void costUsesSeparateInputAndOutputPricesPerMillionTokens() {
        // 10 x 0.15/1M + 25 x 0.60/1M = 0.0000015 + 0.000015
        assertThat(calculator.cost("alpha-small", 10, 25)).isEqualByComparingTo("0.0000165");
        assertThat(calculator.cost("alpha-large", 1_000_000, 1_000_000)).isEqualByComparingTo("18");
    }

    @Test
    void zeroTokensCostNothing() {
        assertThat(calculator.cost("alpha-small", 0, 0)).isEqualByComparingTo("0");
    }

    @Test
    void sumOfFormattedHeadersEqualsExactTotal() {
        // The load test sums x-prism-cost-usd headers; they must add up to the stored total exactly.
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal fromHeaders = BigDecimal.ZERO;
        for (int i = 1; i <= 50; i++) {
            BigDecimal c = calculator.cost("alpha-small", 7 + i, 13 + 2 * i);
            total = total.add(c);
            fromHeaders = fromHeaders.add(new BigDecimal(CostCalculator.format(c)));
        }
        assertThat(fromHeaders).isEqualByComparingTo(total);
    }

    @Test
    void headerFormatIsPlainDecimal() {
        assertThat(CostCalculator.format(new BigDecimal("0.000016500000"))).isEqualTo("0.0000165");
        assertThat(CostCalculator.format(BigDecimal.ZERO)).isEqualTo("0");
        assertThat(CostCalculator.format(new BigDecimal("1E-7"))).isEqualTo("0.0000001");
    }

    @Test
    void unknownModelIsAnError() {
        assertThatThrownBy(() -> calculator.cost("nope", 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
