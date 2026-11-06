package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;

/** Ball harakati (o'zgartirilmaydi va o'chirilmaydi). points: + yozildi, − yechildi. */
@Entity
@Table(name = "loyalty_transactions", indexes = {
        @Index(name = "ix_ltx_account", columnList = "account_id,createdAt"),
        @Index(name = "ix_ltx_center", columnList = "center_id,createdAt")
})
public class LoyaltyTransaction {

    public enum Type {
        WELCOME("Karta ochilishi bonusi"),
        CASHBACK("Keshbek"),
        REDEEM("Ball bilan to'lov"),
        BIRTHDAY("Tug'ilgan kun bonusi"),
        MANUAL_ADD("Qo'lda qo'shildi"),
        MANUAL_SUB("Qo'lda ayirildi"),
        EXPIRE("Ballar kuydi");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id")
    private LoyaltyAccount account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "center_id")
    private ServiceCenter center;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type;

    @Column(nullable = false)
    private long points;

    @Column(nullable = false)
    private long balanceAfter;

    /** Qaysi ish bilan bog'liq (bo'lsa). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "record_id")
    private ServiceRecord record;

    @Column(length = 200)
    private String note;

    @Column(length = 80)
    private String actorName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected LoyaltyTransaction() {
    }

    public LoyaltyTransaction(LoyaltyAccount account, Type type, long points, ServiceRecord record, String note, String actorName) {
        this.account = account;
        this.center = account.getCenter();
        this.type = type;
        this.points = points;
        this.balanceAfter = account.getBalance();
        this.record = record;
        this.note = note;
        this.actorName = actorName;
    }

    public Long getId() { return id; }
    public LoyaltyAccount getAccount() { return account; }
    public ServiceCenter getCenter() { return center; }
    public Type getType() { return type; }
    public long getPoints() { return points; }
    public long getBalanceAfter() { return balanceAfter; }
    public ServiceRecord getRecord() { return record; }
    public String getNote() { return note; }
    public String getActorName() { return actorName; }
    public Instant getCreatedAt() { return createdAt; }
}
