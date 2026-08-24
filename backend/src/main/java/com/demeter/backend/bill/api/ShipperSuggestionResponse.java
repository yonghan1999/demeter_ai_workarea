package com.demeter.backend.bill.api;

public record ShipperSuggestionResponse(
        String id,
        String value,
        String label,
        String meta) {
}
