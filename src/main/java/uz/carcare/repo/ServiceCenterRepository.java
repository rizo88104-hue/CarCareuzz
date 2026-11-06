package uz.carcare.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.carcare.model.ServiceCenter;

import java.util.List;

public interface ServiceCenterRepository extends JpaRepository<ServiceCenter, Long> {

    List<ServiceCenter> findAllByOrderByNameAsc();
}
