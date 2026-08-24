package com.demeter.backend.bill.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "bill_code_sequences")
public class BillCodeSequence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "sequence_name", nullable = false, length = 40)
    private String name;

    @Column(name = "next_value", nullable = false)
    private long value;

    protected BillCodeSequence() {
    }

    public BillCodeSequence(Long tenantId, String name, long initialValue) {
        this.tenantId = tenantId;
        this.name = name;
        this.value = initialValue;
    }

    public long incrementAndGet() {
        value += 1;
        return value;
    }
}
