package com.prism.gateway.cache;

/** Turns prompt text into a vector for similarity search. Swappable for a real embedding model. */
public interface TextEmbedder {

    SparseVector embed(String text);

    /** Stored with each entry; entries made by a different embedder are ignored on load. */
    String name();
}
