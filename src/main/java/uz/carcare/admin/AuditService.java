package uz.carcare.admin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.carcare.model.ActivityLog;
import uz.carcare.model.ServiceCenter;
import uz.carcare.repo.ActivityLogRepository;

/** Faollik jurnaliga yozish. Jurnal xatosi asosiy ishni hech qachon to'xtatmaydi. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final ActivityLogRepository repo;

    public AuditService(ActivityLogRepository repo) {
        this.repo = repo;
    }

    /** Xodim qilgan ish. center — ish qaysi servisga tegishli (super admin uchun ham beriladi). */
    public void staff(AdminPrincipal p, ServiceCenter center, String action) {
        save(new ActivityLog(p.staffId(), p.fullName(), p.role().label(), center, action));
    }

    /** Tizim yoki mijoz qilgan ish (bot, ro'yxatdan o'tish ...). */
    public void system(String actorName, String actorRole, ServiceCenter center, String action) {
        save(new ActivityLog(null, actorName, actorRole, center, action));
    }

    private void save(ActivityLog entry) {
        try {
            repo.save(entry);
        } catch (RuntimeException e) {
            log.warn("Jurnalga yozib bo'lmadi: {}", e.getMessage());
        }
    }
}
