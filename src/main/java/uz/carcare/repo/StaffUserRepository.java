package uz.carcare.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.carcare.model.ServiceCenter;
import uz.carcare.model.StaffUser;

import java.util.List;
import java.util.Optional;

public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    Optional<StaffUser> findByUsername(String username);

    boolean existsByUsername(String username);

    boolean existsByRole(StaffUser.Role role);

    List<StaffUser> findAllByCenterOrderByIdAsc(ServiceCenter center);

    Optional<StaffUser> findFirstByCenterAndRoleOrderByIdAsc(ServiceCenter center, StaffUser.Role role);

    long countByCenter(ServiceCenter center);

    Optional<StaffUser> findByIdAndCenter(Long id, ServiceCenter center);

    /** Eski versiyadagi rol nomlarini yangisiga o'tkazish uchun. */
    @Modifying
    @Query("update StaffUser s set s.role = :to where s.role = :from")
    int migrateRole(@Param("from") StaffUser.Role from, @Param("to") StaffUser.Role to);
}
