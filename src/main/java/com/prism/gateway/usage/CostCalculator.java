package com.prism.gateway.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.prism.gateway.config.ConfigFiles.PriceTable;
import com.prism.gateway.config.ModelPrice;

/**
 * Cost = prompt_tokens x input price + completion_tokens x output price, prices per 1M tokens.
 * Uses exact decimal math at 12 places (a millionth of a micro-dollar), so the per-request
 * {@code x-prism-cost-usd} values sum exactly to what the usage API reports.
 */
@Component
public class CostCalculator {

    public static final int SCALE = 12;
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    private final PriceTable prices;

    public CostCalculator(PriceTable prices) {
        this.prices = prices;
    }

    public BigDecimal cost(String model, long promptTokens, long completionTokens) {
        ModelPrice price = prices.get(model);
        if (price == null) {
            throw new IllegalArgumentException("No price for model " + model);
        }
        return compute(price, promptTokens, completionTokens);
    }

    static BigDecimal compute(ModelPrice price, long promptTokens, long completionTokens) {
        BigDecimal input = price.inputPer1m().multiply(BigDecimal.valueOf(promptTokens));
        BigDecimal output = price.outputPer1m().multiply(BigDecimal.valueOf(completionTokens));
        return input.add(output).divide(ONE_MILLION, SCALE, RoundingMode.HALF_UP);
    }

    /** Header form: plain decimal, no exponent, no trailing zeros ({@code 0.0000165}, or {@code 0}). */
    public static String format(BigDecimal cost) {
        if (cost.signum() == 0) {
            return "0";
        }
        return cost.stripTrailingZeros().toPlainString();
    }
}
