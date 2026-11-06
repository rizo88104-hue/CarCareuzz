package uz.carcare.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import uz.carcare.model.LoyaltyProgram;
import uz.carcare.model.ServiceCenter;

import java.util.List;
import java.util.Optional;

public interface LoyaltyProgramRepository extends JpaRepository<LoyaltyProgram, Long> {

    Optional<LoyaltyProgram> findByCenter(ServiceCenter center);

    @Query("select p from LoyaltyProgram p join fetch p.center where p.enabled = true")
    List<LoyaltyProgram> findAllEnabled();
}
