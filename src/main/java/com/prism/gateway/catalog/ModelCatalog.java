package com.prism.gateway.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.prism.gateway.config.ConfigFiles.PriceTable;
import com.prism.gateway.config.GatewayConfig;

/**
 * Resolves what a caller asked for ({@code fast}, {@code smart}, {@code auto} or a raw model name)
 * into an ordered chain of upstream targets. Validated at startup so a broken config fails fast
 * instead of on the first request.
 */
@Component
public class ModelCatalog {

    public static final String AUTO = "auto";

    private final GatewayConfig config;
    private final PriceTable prices;

    public ModelCatalog(GatewayConfig config, PriceTable prices) {
        this.config = config;
        this.prices = prices;
        validate();
    }

    public boolean isAuto(String requested) {
        return AUTO.equals(requested);
    }

    public boolean isAlias(String requested) {
        return config.aliases().containsKey(requested);
    }

    /** Known = an alias, {@code auto}, or a priced model that some provider serves. */
    public boolean isKnown(String requested) {
        return isAuto(requested) || isAlias(requested)
                || (prices.contains(requested) && providerFor(requested).isPresent());
    }

    /** Primary first, then fallbacks in order. */
    public List<ModelTarget> targetsFor(String aliasOrModel) {
        GatewayConfig.AliasConfig alias = config.aliases().get(aliasOrModel);
        List<String> models = new ArrayList<>();
        if (alias != null) {
            models.add(alias.primary());
            models.addAll(alias.fallbacks());
        } else {
            models.add(aliasOrModel);
        }
        return models.stream()
                .map(m -> new ModelTarget(providerFor(m).orElseThrow(), m))
                .toList();
    }

    public String simpleTierAlias() {
        return config.auto().simpleAlias();
    }

    public String complexTierAlias() {
        return config.auto().complexAlias();
    }

    public Optional<String> providerFor(String model) {
        for (GatewayConfig.ProviderConfig p : config.providers()) {
            boolean serves = p.models().isEmpty()
                    ? model.startsWith(p.name() + "-")
                    : p.models().contains(model);
            if (serves) {
                return Optional.of(p.name());
            }
        }
        return Optional.empty();
    }

    private void validate() {
        if (config.providers().isEmpty()) {
            throw new IllegalStateException("Gateway config defines no providers");
        }
        for (GatewayConfig.AliasConfig alias : config.aliases().values()) {
            List<String> chain = new ArrayList<>(alias.fallbacks());
            chain.add(0, alias.primary());
            for (String model : chain) {
                if (!prices.contains(model)) {
                    throw new IllegalStateException("Alias '" + alias.alias() + "' references model '" + model
                            + "' which has no entry in the price table");
                }
                if (providerFor(model).isEmpty()) {
                    throw new IllegalStateException("Alias '" + alias.alias() + "' references model '" + model
                            + "' which no registered provider serves");
                }
            }
        }
        for (String tier : List.of(simpleTierAlias(), complexTierAlias())) {
            if (!isAlias(tier)) {
                throw new IllegalStateException("auto routes to unknown alias '" + tier + "'");
            }
        }
    }
}
