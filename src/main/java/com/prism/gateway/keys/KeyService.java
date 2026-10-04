package com.prism.gateway.keys;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * Serves key lookups from memory so authentication costs no database round trip.
 * The database stays the source of truth; {@link #reload()} refreshes the snapshot.
 */
@Service
public class KeyService {

    private final VirtualKeyRepository repository;
    private volatile Map<String, VirtualKey> keys = Map.of();

    public KeyService(VirtualKeyRepository repository) {
        this.repository = repository;
    }

    public void reload() {
        Map<String, VirtualKey> fresh = new ConcurrentHashMap<>();
        for (VirtualKeyEntity e : repository.findAll()) {
            fresh.put(e.getVirtualKey(), e.toDomain());
        }
        keys = fresh;
    }

    public Optional<VirtualKey> find(String key) {
        return key == null ? Optional.empty() : Optional.ofNullable(keys.get(key));
    }

    public List<VirtualKey> all() {
        Collection<VirtualKey> values = keys.values();
        return values.stream().sorted(Comparator.comparing(VirtualKey::team)).toList();
    }
}
