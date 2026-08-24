package com.demeter.backend.bill.api;

import java.util.List;

public record DeleteBillsResponse(boolean ok, int count, List<Long> ids) {
}
