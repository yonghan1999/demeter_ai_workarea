package com.demeter.backend.payment.infrastructure;

import com.demeter.backend.payment.domain.Payment;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByTenantIdAndIdempotencyKey(long tenantId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select payment
            from Payment payment
            where payment.id = :id and payment.tenantId = :tenantId
            """)
    Optional<Payment> findByIdAndTenantIdForUpdate(
            @Param("id") long id,
            @Param("tenantId") long tenantId);

    List<Payment> findAllByBillIdAndTenantIdOrderByPaidAtDescIdDesc(long billId, long tenantId);

    Page<Payment> findAllByBillIdAndTenantId(long billId, long tenantId, Pageable pageable);

    @Query("""
            select new com.demeter.backend.payment.infrastructure.PaymentLedgerDiscrepancy(
                bill.id,
                bill.tenantId,
                bill.paidAmount,
                coalesce(sum(payment.amount), :zeroAmount))
            from Bill bill
            left join Payment payment
              on payment.billId = bill.id
             and payment.tenantId = bill.tenantId
             and payment.status = com.demeter.backend.payment.domain.PaymentStatus.ACTIVE
            group by bill.id, bill.tenantId, bill.paidAmount
            having bill.paidAmount <> coalesce(sum(payment.amount), :zeroAmount)
            order by bill.id
            """)
    List<PaymentLedgerDiscrepancy> findLedgerDiscrepancies(
            @Param("zeroAmount") BigDecimal zeroAmount,
            Pageable pageable);

    @Query("""
            select coalesce(sum(payment.amount), :zeroAmount)
            from Payment payment
            where payment.tenantId = :tenantId
              and payment.billId = :billId
              and payment.status = com.demeter.backend.payment.domain.PaymentStatus.ACTIVE
            """)
    BigDecimal sumActiveAmount(
            @Param("tenantId") long tenantId,
            @Param("billId") long billId,
            @Param("zeroAmount") BigDecimal zeroAmount);
}
