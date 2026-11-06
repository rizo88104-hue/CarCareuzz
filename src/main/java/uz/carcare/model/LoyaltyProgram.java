package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Servisning sadoqat dasturi sozlamalari (har bir servisda bittadan, direktor boshqaradi).
 * 1 ball = 1 so'm. Ball haqiqiy pul bilan to'langan summadan keshbek sifatida yoziladi
 * va keyingi ishlar uchun to'lovda ishlatiladi.
 */
@Entity
@Table(name = "loyalty_programs", uniqueConstraints = @UniqueConstraint(name = "ux_loyalty_center", columnNames = "center_id"))
public class LoyaltyProgram {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "center_id")
    private ServiceCenter center;

    @Column(nullable = false)
    private boolean enabled = false;

    /** Daraja chegaralari: shu summadan (so'm) boshlab. Bronza — 0 dan. */
    @Column(nullable = false)
    private long silverFrom = 3_000_000;
    @Column(nullable = false)
    private long goldFrom = 10_000_000;
    @Column(nullable = false)
    private long platinumFrom = 25_000_000;

    /** Keshbek foizi har bir daraja uchun. */
    @Column(nullable = false)
    private int bronzeCashback = 3;
    @Column(nullable = false)
    private int silverCashback = 5;
    @Column(nullable = false)
    private int goldCashback = 7;
    @Column(nullable = false)
    private int platinumCashback = 10;

    /** Doimiy chegirma foizi har bir daraja uchun. */
    @Column(nullable = false)
    private int bronzeDiscount = 0;
    @Column(nullable = false)
    private int silverDiscount = 3;
    @Column(nullable = false)
    private int goldDiscount = 5;
    @Column(nullable = false)
    private int platinumDiscount = 10;

    /** Bitta ish narxining necha foizini ball bilan to'lash mumkin. */
    @Column(nullable = false)
    private int maxRedeemPct = 50;

    /** Karta ochilganda beriladigan ball. */
    @Column(nullable = false)
    private long welcomeBonus = 10_000;

    /** Tug'ilgan kunda beriladigan ball. */
    @Column(nullable = false)
    private long birthdayBonus = 20_000;

    /** Mijoz shuncha kun kelmasa ballari kuyadi (0 — hech qachon kuymaydi). */
    @Column(nullable = false)
    private int expiryDays = 365;

    @Column(length = 80)
    private String updatedBy;

    private Instant updatedAt;

    protected LoyaltyProgram() {
    }

    public LoyaltyProgram(ServiceCenter center) {
        this.center = center;
    }

    public long levelFrom(LoyaltyLevel l) {
        return switch (l) {
            case BRONZE -> 0;
            case SILVER -> silverFrom;
            case GOLD -> goldFrom;
            case PLATINUM -> platinumFrom;
        };
    }

    public int cashbackPct(LoyaltyLevel l) {
        return switch (l) {
            case BRONZE -> bronzeCashback;
            case SILVER -> silverCashback;
            case GOLD -> goldCashback;
            case PLATINUM -> platinumCashback;
        };
    }

    public int discountPct(LoyaltyLevel l) {
        return switch (l) {
            case BRONZE -> bronzeDiscount;
            case SILVER -> silverDiscount;
            case GOLD -> goldDiscount;
            case PLATINUM -> platinumDiscount;
        };
    }

    /** Jami xarajatga mos daraja. */
    public LoyaltyLevel levelFor(long spend) {
        if (spend >= platinumFrom) return LoyaltyLevel.PLATINUM;
        if (spend >= goldFrom) return LoyaltyLevel.GOLD;
        if (spend >= silverFrom) return LoyaltyLevel.SILVER;
        return LoyaltyLevel.BRONZE;
    }

    public void setLevel(LoyaltyLevel l, long from, int cashback, int discount) {
        switch (l) {
            case BRONZE -> { bronzeCashback = cashback; bronzeDiscount = discount; }
            case SILVER -> { silverFrom = from; silverCashback = cashback; silverDiscount = discount; }
            case GOLD -> { goldFrom = from; goldCashback = cashback; goldDiscount = discount; }
            case PLATINUM -> { platinumFrom = from; platinumCashback = cashback; platinumDiscount = discount; }
        }
    }

    public Long getId() { return id; }
    public ServiceCenter getCenter() { return center; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getMaxRedeemPct() { return maxRedeemPct; }
    public void setMaxRedeemPct(int maxRedeemPct) { this.maxRedeemPct = maxRedeemPct; }
    public long getWelcomeBonus() { return welcomeBonus; }
    public void setWelcomeBonus(long welcomeBonus) { this.welcomeBonus = welcomeBonus; }
    public long getBirthdayBonus() { return birthdayBonus; }
    public void setBirthdayBonus(long birthdayBonus) { this.birthdayBonus = birthdayBonus; }
    public int getExpiryDays() { return expiryDays; }
    public void setExpiryDays(int expiryDays) { this.expiryDays = expiryDays; }
    public String getUpdatedBy() { return updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void touch(String by) {
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }
}
