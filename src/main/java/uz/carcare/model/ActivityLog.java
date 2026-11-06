package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Faollik jurnali: kim, qachon, nima qildi.
 * Masalan: "Akmal Yo'ldoshev (Usta, Avto Lider) — 01 A 777 BC: Ballon (shina) kiritdi".
 * Yozuvlar o'zgartirilmaydi va o'chirilmaydi.
 */
@Entity
@Table(name = "activity_log", indexes = {
        @Index(name = "ix_log_created", columnList = "createdAt"),
        @Index(name = "ix_log_center", columnList = "center_id,createdAt")
})
public class ActivityLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Xodim bo'lsa uning id si; tizim yoki mijoz bo'lsa null. */
    private Long staffId;

    @Column(nullable = false, length = 80)
    private String actorName;

    /** "Usta", "Super admin", "Mijoz", "Bot" ... */
    @Column(nullable = false, length = 30)
    private String actorRole;

    /** Qaysi servisga tegishli (null — umumiy). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "center_id")
    private ServiceCenter center;

    @Column(nullable = false, length = 300)
    private String action;

    protected ActivityLog() {
    }

    public ActivityLog(Long staffId, String actorName, String actorRole, ServiceCenter center, String action) {
        this.staffId = staffId;
        this.actorName = cut(actorName, 80);
        this.actorRole = cut(actorRole, 30);
        this.center = center;
        this.action = cut(action, 300);
    }

    private static String cut(String s, int max) {
        if (s == null || s.isBlank()) return "—";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    public Long getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getStaffId() { return staffId; }
    public String getActorName() { return actorName; }
    public String getActorRole() { return actorRole; }
    public ServiceCenter getCenter() { return center; }
    public String getAction() { return action; }
}
