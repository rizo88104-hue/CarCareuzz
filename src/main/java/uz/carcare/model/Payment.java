package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Bitta to'lov. Ish kiritilganda darhol to'langan summa ham, keyinroq qarzni so'ndirish ham
 * alohida to'lov bo'lib yoziladi. To'lovlar o'zgartirilmaydi va o'chirilmaydi (moliyaviy tarix).
 */
@Entity
@Table(name = "payments", indexes = {
        @Index(name = "ix_pay_record", columnList = "record_id"),
        @Index(name = "ix_pay_center", columnList = "center_id,createdAt")
})
public class Payment {

    public enum Method {
        CASH("Naqd"), CARD("Karta"), TRANSFER("O'tkazma"),
        /** Sadoqat ballari bilan to'lov — haqiqiy tushum emas, keshbek berilmaydi. */
        BONUS("Bonus ball");

        private final String label;

        Method(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Haqiqiy pul (naqd, karta, o'tkazma). */
        public boolean isMoney() {
            return this != BONUS;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id")
    private ServiceRecord record;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "center_id")
    private ServiceCenter center;

    /** So'mda. */
    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Method method;

    /** Kim qabul qildi. */
    @Column(length = 80)
    private String receivedBy;

    @Column(length = 200)
    private String note;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected Payment() {
    }

    public Payment(ServiceRecord record, ServiceCenter center, long amount, Method method, String receivedBy, String note) {
        this.record = record;
        this.center = center;
        this.amount = amount;
        this.method = method;
        this.receivedBy = receivedBy;
        this.note = note;
    }

    public Long getId() { return id; }
    public ServiceRecord getRecord() { return record; }
    public ServiceCenter getCenter() { return center; }
    public long getAmount() { return amount; }
    public Method getMethod() { return method; }
    public String getReceivedBy() { return receivedBy; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
