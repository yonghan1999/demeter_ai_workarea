package com.demeter.backend.bill.infrastructure;

import com.demeter.backend.bill.domain.Bill;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;

public interface BillRepository extends JpaRepository<Bill, Long>, JpaSpecificationExecutor<Bill> {

    Optional<Bill> findByIdAndTenantIdAndDeletedAtIsNull(long id, long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select bill from Bill bill where bill.id = :id and bill.tenantId = :tenantId")
    Optional<Bill> findAnyByIdAndTenantIdForUpdate(
            @Param("id") long id,
            @Param("tenantId") long tenantId);

    Optional<Bill> findByTenantIdAndCreationIdempotencyKey(long tenantId, String creationIdempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bill
            from Bill bill
            where bill.tenantId = :tenantId
              and bill.creationIdempotencyKey = :creationIdempotencyKey
            """)
    Optional<Bill> findByTenantIdAndCreationIdempotencyKeyForUpdate(
            @Param("tenantId") long tenantId,
            @Param("creationIdempotencyKey") String creationIdempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bill
            from Bill bill
            where bill.id = :id
              and bill.tenantId = :tenantId
              and bill.deletedAt is null
            """)
    Optional<Bill> findByIdAndTenantIdForUpdate(
            @Param("id") long id,
            @Param("tenantId") long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select bill
            from Bill bill
            where bill.tenantId = :tenantId
              and bill.id in :ids
              and bill.deletedAt is null
            order by bill.id
            """)
    List<Bill> findAllByTenantIdAndIdsForUpdate(
            @Param("tenantId") long tenantId,
            @Param("ids") Collection<Long> ids);

    Optional<Bill> findFirstByTenantIdAndShipperNormalizedAndDeletedAtIsNullOrderByUpdatedAtDesc(
            long tenantId,
            String shipperNormalized);

    long countByTenantIdAndShipperAndDeletedAtIsNull(long tenantId, String shipper);

    @Query("""
            select distinct bill.shipper
            from Bill bill
            where bill.tenantId = :tenantId
              and bill.deletedAt is null
              and (:keyword = '' or lower(bill.shipper) like lower(concat('%', :keyword, '%')))
            order by bill.shipper
            """)
    List<String> findShipperNames(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            Pageable pageable);

    @Query("""
            select distinct concat(bill.origin, ' → ', bill.destination)
            from Bill bill
            where bill.tenantId = :tenantId
              and bill.deletedAt is null
              and (lower(bill.origin) like lower(concat('%', :keyword, '%'))
                or lower(bill.destination) like lower(concat('%', :keyword, '%'))
                or lower(bill.shipper) like lower(concat('%', :keyword, '%')))
            order by concat(bill.origin, ' → ', bill.destination)
            """)
    List<String> findRoutes(
            @Param("tenantId") long tenantId,
            @Param("keyword") String keyword,
            Pageable pageable);
}
