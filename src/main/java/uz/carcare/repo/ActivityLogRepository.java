package uz.carcare.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import uz.carcare.model.ActivityLog;
import uz.carcare.model.ServiceCenter;

import java.util.List;

public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    @Query("select l from ActivityLog l left join fetch l.center order by l.createdAt desc, l.id desc")
    List<ActivityLog> findRecent(Pageable page);

    @Query("select l from ActivityLog l left join fetch l.center where l.center = :center order by l.createdAt desc, l.id desc")
    List<ActivityLog> findRecentForCenter(@Param("center") ServiceCenter center, Pageable page);
}
