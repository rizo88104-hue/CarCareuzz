package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;

/** Admin panelga kiradigan xodim: super admin yoki avtoservis xodimi. */
@Entity
@Table(name = "staff_users")
public class StaffUser {

    public enum Role {
        /** Loyiha egasi: barcha servislarni boshqaradi. */
        SUPER_ADMIN("Super admin"),
        /** Servis egasi: hamma bo'lim, xodim qo'shadi va bloklaydi. */
        DIRECTOR("Direktor"),
        /** Ish kiritadi, "Tayyor, keling" yuboradi, qo'ng'iroq qiladi. */
        ADMINISTRATOR("Administrator"),
        /** Faqat o'zi qilgan ishni kiritadi. */
        MASTER("Usta"),
        /** Eski versiyadan qolgan nom. Dastur ishga tushganda DIRECTOR ga o'tkaziladi. */
        @Deprecated
        CENTER_ADMIN("Direktor");

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean isCenterRole() {
            return this == DIRECTOR || this == ADMINISTRATOR || this == MASTER || this == CENTER_ADMIN;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 40)
    private String username;

    @Column(nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /** Ism familiya (jurnalda va panelda ko'rinadi). */
    @Column(length = 80)
    private String fullName;

    /** Servis xodimlari uchun. Super admin uchun null. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "center_id")
    private ServiceCenter center;

    /** true — bloklangan. null yoki false — faol (eski yozuvlar bilan mos bo'lishi uchun Boolean). */
    private Boolean blocked;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public String displayName() {
        return fullName == null || fullName.isBlank() ? username : fullName;
    }

    public boolean isBlocked() {
        return Boolean.TRUE.equals(blocked);
    }

    public void setBlocked(boolean blocked) {
        this.blocked = blocked;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public ServiceCenter getCenter() { return center; }
    public void setCenter(ServiceCenter center) { this.center = center; }
    public Instant getCreatedAt() { return createdAt; }
}
