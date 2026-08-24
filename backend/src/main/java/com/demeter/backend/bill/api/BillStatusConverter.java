package com.demeter.backend.bill.api;

import com.demeter.backend.bill.domain.BillStatus;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

@Component
public class BillStatusConverter implements Converter<String, BillStatus> {

    @Override
    public BillStatus convert(String source) {
        return BillStatus.fromValue(source);
    }
}
