package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;

/** Admin panelga kirish sessiyasi (7 kun amal qiladi). */
@Entity
@Table(name = "admin_sessions")
public class AdminSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 48)
    private String token;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "staff_id")
    private StaffUser staff;

    @Column(nullable = false)
    private Instant expiresAt;

    public Long getId() { return id; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public StaffUser getStaff() { return staff; }
    public void setStaff(StaffUser staff) { this.staff = staff; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
}
