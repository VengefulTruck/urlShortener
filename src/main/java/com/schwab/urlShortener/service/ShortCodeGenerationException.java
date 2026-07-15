package com.schwab.urlShortener.service;

public class ShortCodeGenerationException extends RuntimeException {
    public ShortCodeGenerationException(String message) {
        super(message);
    }
}