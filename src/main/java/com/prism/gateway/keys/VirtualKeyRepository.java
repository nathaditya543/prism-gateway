package com.prism.gateway.keys;

import org.springframework.data.jpa.repository.JpaRepository;

public interface VirtualKeyRepository extends JpaRepository<VirtualKeyEntity, String> {
}
