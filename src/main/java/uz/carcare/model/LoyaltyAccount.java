package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Mijozning bitta servisdagi jamg'arma kartasi: karta raqami, ball balansi va darajasi.
 * Bir mashina har bir servisda alohida kartaga ega bo'ladi.
 * {@code @Version} — bir vaqtda ikki xodim ball yechsa, ikkinchisi rad etiladi (ikki marta sarflanmaydi).
 */
@Entity
@Table(name = "loyalty_accounts",
        uniqueConstraints = {
                @UniqueConstraint(name = "ux_loyalty_acc", columnNames = {"center_id", "car_id"}),
                @UniqueConstraint(name = "ux_loyalty_card", columnNames = "cardNumber")
        })
public class LoyaltyAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "center_id")
    private ServiceCenter center;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "car_id")
    private Car car;

    /** 8 xonali karta raqami (masalan 40213377). */
    @Column(nullable = false, length = 8)
    private String cardNumber;

    @Column(nullable = false)
    private long balance;

    /** Jami yozilgan ballar (keshbek + bonuslar). */
    @Column(nullable = false)
    private long earnedTotal;

    /** Jami sarflangan ballar. */
    @Column(nullable = false)
    private long redeemedTotal;

    /** Kuygan ballar. */
    @Column(nullable = false)
    private long expiredTotal;

    /** Haqiqiy pul bilan to'langan jami summa (daraja shundan hisoblanadi). */
    @Column(nullable = false)
    private long spendTotal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private LoyaltyLevel level = LoyaltyLevel.BRONZE;

    /** Oxirgi faollik (tashrif, to'lov, ball ishlatish) — ballarning kuyish muddati shundan. */
    @Column(nullable = false)
    private LocalDate lastActivity;

    /** Kuyish haqida ogohlantirish qachon yuborilgan. */
    private LocalDate expiryWarnedOn;

    /** Qaysi yili tug'ilgan kun bonusi berilgan. */
    private Integer birthdayBonusYear;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(length = 80)
    private String createdBy;

    protected LoyaltyAccount() {
    }

    public LoyaltyAccount(ServiceCenter center, Car car, String cardNumber, LocalDate today, String createdBy) {
        this.center = center;
        this.car = car;
        this.cardNumber = cardNumber;
        this.lastActivity = today;
        this.createdBy = createdBy;
    }

    /** Ball qo'shadi (musbat) yoki yechadi (manfiy). Balans manfiy bo'lmaydi. */
    public void change(long points) {
        if (balance + points < 0) throw new IllegalStateException("Ball yetarli emas");
        balance += points;
    }

    public void addEarned(long p) { earnedTotal += p; }
    public void addRedeemed(long p) { redeemedTotal += p; }
    public void addExpired(long p) { expiredTotal += p; }
    public void addSpend(long amount) { spendTotal += amount; }

    public Long getId() { return id; }
    public ServiceCenter getCenter() { return center; }
    public Car getCar() { return car; }
    public String getCardNumber() { return cardNumber; }
    public long getBalance() { return balance; }
    public long getEarnedTotal() { return earnedTotal; }
    public long getRedeemedTotal() { return redeemedTotal; }
    public long getExpiredTotal() { return expiredTotal; }
    public long getSpendTotal() { return spendTotal; }
    public void setSpendTotal(long spendTotal) { this.spendTotal = spendTotal; }
    public LoyaltyLevel getLevel() { return level; }
    public void setLevel(LoyaltyLevel level) { this.level = level; }
    public LocalDate getLastActivity() { return lastActivity; }
    public void setLastActivity(LocalDate lastActivity) { this.lastActivity = lastActivity; }
    public LocalDate getExpiryWarnedOn() { return expiryWarnedOn; }
    public void setExpiryWarnedOn(LocalDate d) { this.expiryWarnedOn = d; }
    public Integer getBirthdayBonusYear() { return birthdayBonusYear; }
    public void setBirthdayBonusYear(Integer y) { this.birthdayBonusYear = y; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
