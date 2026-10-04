package com.prism.gateway.config;

import java.math.BigDecimal;

/** USD per one million tokens. */
public record ModelPrice(String model, BigDecimal inputPer1m, BigDecimal outputPer1m) {
}
