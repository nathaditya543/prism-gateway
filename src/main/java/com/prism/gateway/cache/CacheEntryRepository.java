package com.prism.gateway.cache;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CacheEntryRepository extends JpaRepository<CacheEntryEntity, String> {
}
