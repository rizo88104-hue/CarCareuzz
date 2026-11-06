package uz.carcare.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.carcare.model.Car;
import uz.carcare.model.LoyaltyAccount;
import uz.carcare.model.ServiceCenter;

import java.util.List;
import java.util.Optional;

public interface LoyaltyAccountRepository extends JpaRepository<LoyaltyAccount, Long> {

    Optional<LoyaltyAccount> findByCenterAndCar(ServiceCenter center, Car car);

    Optional<LoyaltyAccount> findByCenterAndCardNumber(ServiceCenter center, String cardNumber);

    boolean existsByCardNumber(String cardNumber);

    @Query("select a from LoyaltyAccount a join fetch a.car where a.center = :center order by a.balance desc, a.id asc")
    List<LoyaltyAccount> findAllForCenter(@Param("center") ServiceCenter center);

    /** Mijoz sahifasi va bot uchun: mashinaning barcha servislardagi kartalari. */
    @Query("select a from LoyaltyAccount a join fetch a.center where a.car = :car order by a.createdAt asc")
    List<LoyaltyAccount> findAllForCar(@Param("car") Car car);

    /** Kuyish tekshiruvi uchun: balansi bor kartalar. */
    @Query("select a from LoyaltyAccount a join fetch a.car join fetch a.center where a.center = :center and a.balance > 0")
    List<LoyaltyAccount> findWithBalance(@Param("center") ServiceCenter center);

    /** Tug'ilgan kun bonusi uchun: tug'ilgan sanasi kiritilgan mijozlarning kartalari. */
    @Query("select a from LoyaltyAccount a join fetch a.car c where a.center = :center and c.birthDate is not null")
    List<LoyaltyAccount> findWithBirthDate(@Param("center") ServiceCenter center);
}
