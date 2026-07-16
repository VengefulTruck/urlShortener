package com.schwab.urlShortener.repository;

import com.schwab.urlShortener.domain.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {

    long countByShortCode(String shortCode);

    @Query("select count(c) from ClickEvent c where c.shortCode = :code and c.clickedAt >= :since")
    long countByShortCodeSince(@Param("code") String code, @Param("since") Instant since);

    @Query("select count(distinct c.clientIpHash) from ClickEvent c where c.shortCode = :code")
    long countUniqueVisitors(@Param("code") String code);
}