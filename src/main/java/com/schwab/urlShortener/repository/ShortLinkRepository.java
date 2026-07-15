package com.schwab.urlShortener.repository;

import com.schwab.urlShortener.domain.ShortLink;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShortLinkRepository extends JpaRepository<ShortLink, Long> {

    Optional<ShortLink> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);
}