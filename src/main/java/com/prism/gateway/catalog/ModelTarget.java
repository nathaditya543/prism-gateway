package com.prism.gateway.catalog;

/** One concrete upstream choice: a model served by a provider. */
public record ModelTarget(String provider, String model) {

    /** The {@code x-prism-provider} header form, e.g. {@code alpha/alpha-small}. */
    public String label() {
        return provider + "/" + model;
    }
}
