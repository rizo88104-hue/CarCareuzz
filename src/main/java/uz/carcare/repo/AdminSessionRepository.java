package uz.carcare.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.carcare.model.AdminSession;
import uz.carcare.model.StaffUser;

import java.time.Instant;
import java.util.Optional;

public interface AdminSessionRepository extends JpaRepository<AdminSession, Long> {

    @Query("select s from AdminSession s join fetch s.staff st left join fetch st.center where s.token = :token")
    Optional<AdminSession> findWithStaff(@Param("token") String token);

    @Modifying
    @Query("delete from AdminSession s where s.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    @Modifying
    @Query("delete from AdminSession s where s.staff = :staff")
    int deleteAllByStaff(@Param("staff") StaffUser staff);

    @Modifying
    @Query("delete from AdminSession s where s.token = :token")
    int deleteByToken(@Param("token") String token);
}
