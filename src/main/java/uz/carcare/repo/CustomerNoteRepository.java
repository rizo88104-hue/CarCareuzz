package uz.carcare.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.carcare.model.Car;
import uz.carcare.model.CustomerNote;
import uz.carcare.model.ServiceCenter;

import java.util.List;
import java.util.Optional;

public interface CustomerNoteRepository extends JpaRepository<CustomerNote, Long> {

    Optional<CustomerNote> findByCenterAndCar(ServiceCenter center, Car car);

    @Query("select n from CustomerNote n join fetch n.car where n.center = :center")
    List<CustomerNote> findAllForCenter(@Param("center") ServiceCenter center);
}
