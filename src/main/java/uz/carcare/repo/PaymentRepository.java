package uz.carcare.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.carcare.model.Car;
import uz.carcare.model.Payment;
import uz.carcare.model.ServiceCenter;
import uz.carcare.model.ServiceRecord;

import java.util.List;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findAllByRecordOrderByCreatedAtDesc(ServiceRecord record);

    /** Bosh sahifadagi tushum va kunlik grafik uchun. */
    List<Payment> findAllByCenterAndCreatedAtGreaterThanEqual(ServiceCenter center, java.time.Instant from);

    /** Bitta mashinaning shu servisdagi barcha to'lovlari (mijoz profili uchun). */
    @Query("""
            select p from Payment p join fetch p.record r
            where p.center = :center and r.car = :car
            order by p.createdAt desc
            """)
    List<Payment> findAllForCar(@Param("center") ServiceCenter center, @Param("car") Car car);

    /** Har bir mashinaning shu servisda haqiqiy pul bilan to'lagan summasi: [carId, summa]. Sadoqat darajasi uchun. */
    @Query("""
            select r.car.id, sum(p.amount) from Payment p join p.record r
            where p.center = :center and p.method <> :bonus
            group by r.car.id
            """)
    List<Object[]> sumMoneyByCar(@Param("center") ServiceCenter center, @Param("bonus") Payment.Method bonus);
}
