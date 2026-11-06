package uz.carcare.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.carcare.model.LoyaltyAccount;
import uz.carcare.model.LoyaltyTransaction;
import uz.carcare.model.ServiceCenter;

import java.time.Instant;
import java.util.List;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, Long> {

    List<LoyaltyTransaction> findAllByAccountOrderByCreatedAtDescIdDesc(LoyaltyAccount account);

    @Query("""
            select t from LoyaltyTransaction t join fetch t.account a join fetch a.car
            where t.center = :center order by t.createdAt desc, t.id desc
            """)
    List<LoyaltyTransaction> findRecent(@Param("center") ServiceCenter center, Pageable page);

    List<LoyaltyTransaction> findAllByCenterAndCreatedAtGreaterThanEqual(ServiceCenter center, Instant from);
}
