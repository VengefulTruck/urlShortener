package com.schwab.urlShortener.service;

public class ShortLinkNotFoundException extends RuntimeException {
    public ShortLinkNotFoundException(String shortCode) {
        super("No active link for code: " + shortCode);
    }
}