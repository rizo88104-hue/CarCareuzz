package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Servisning mijoz haqidagi ichki ma'lumoti: izoh va qora ro'yxat.
 * Har bir servis o'zinikini ko'radi, boshqa servislar va mijozning o'zi ko'rmaydi.
 * Shu yozuv bo'lsa, mashina ish kiritilmagan bo'lsa ham servisning mijozlar bazasida turadi
 * (masalan, mijozni qo'lda qo'shganda).
 */
@Entity
@Table(name = "customer_notes", uniqueConstraints = {
        @UniqueConstraint(name = "uq_note_center_car", columnNames = {"center_id", "car_id"})
})
public class CustomerNote {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "center_id")
    private ServiceCenter center;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "car_id")
    private Car car;

    @Column(length = 500)
    private String text;

    @Column(length = 80)
    private String updatedBy;

    private Instant updatedAt;

    // ---------- Qora ro'yxat ----------

    private Boolean blacklisted;

    @Column(length = 200)
    private String blacklistReason;

    @Column(length = 80)
    private String blacklistedBy;

    private Instant blacklistedAt;

    /** Qachon bazaga qo'shilgan (qo'lda qo'shilgan mijozlar uchun). */
    private Instant createdAt;

    protected CustomerNote() {
    }

    public CustomerNote(ServiceCenter center, Car car) {
        this.center = center;
        this.car = car;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public ServiceCenter getCenter() { return center; }
    public Car getCar() { return car; }
    public String getText() { return text; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getCreatedAt() { return createdAt; }

    public void update(String text, String by) {
        this.text = text;
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public boolean isBlacklisted() { return Boolean.TRUE.equals(blacklisted); }
    public String getBlacklistReason() { return blacklistReason; }
    public String getBlacklistedBy() { return blacklistedBy; }
    public Instant getBlacklistedAt() { return blacklistedAt; }

    public void blacklist(String reason, String by) {
        this.blacklisted = true;
        this.blacklistReason = reason;
        this.blacklistedBy = by;
        this.blacklistedAt = Instant.now();
    }

    public void unblacklist() {
        this.blacklisted = false;
        this.blacklistReason = null;
        this.blacklistedBy = null;
        this.blacklistedAt = null;
    }
}
