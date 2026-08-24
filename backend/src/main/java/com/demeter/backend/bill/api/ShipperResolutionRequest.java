package com.demeter.backend.bill.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShipperResolutionRequest(@NotBlank @Size(max = 120) String name) {
}
