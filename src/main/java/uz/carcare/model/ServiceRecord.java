package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Bitta servis yozuvi: masalan "Motor moyi 04.11.2026 da almashtirildi,
 * keyingisi 04.02.2027 da". Bir turdagi yangi yozuv qo'shilsa eskisi
 * arxivga o'tadi (active=false) va tarixda ko'rinadi.
 */
@Entity
@Table(name = "service_records", indexes = {
        @Index(name = "ix_records_next", columnList = "active,nextDate")
})
public class ServiceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "car_id")
    private Car car;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ServiceType type;

    /** type=OTHER bo'lganda foydalanuvchi yozgan nom. */
    @Column(length = 80)
    private String customName;

    /** Qachon almashtirildi. */
    @Column(nullable = false)
    private LocalDate doneDate;

    /** Keyingi safar qachon almashtirish kerak (egasining o'zi belgilaydi). */
    @Column(nullable = false)
    private LocalDate nextDate;

    /** Almashtirilgan paytdagi probeg (ixtiyoriy). */
    private Integer mileage;

    /** Keyingi almashtirish probegi (ixtiyoriy). */
    private Integer nextMileage;

    @Column(length = 300)
    private String note;

    @Column(nullable = false)
    private boolean active = true;

    /** "1 haftadan keyin" eslatmasi yuborildimi. */
    @Column(nullable = false)
    private boolean weekReminderSent = false;

    /** "Bugun almashtirish kuni" eslatmasi yuborildimi. */
    @Column(nullable = false)
    private boolean dueReminderSent = false;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Qaysi avtoservis kiritgan (null — mashina egasining o'zi kiritgan). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "center_id")
    private ServiceCenter center;

    /** Shu turdagi keyingi servis qachon qilingan (null — hali qilinmagan). */
    private Instant replacedAt;

    /** Keyingi servisni qaysi avtoservis qilgan (null + replacedAt bor — egasi o'zi kiritgan). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "replaced_by_center_id")
    private ServiceCenter replacedByCenter;

    /** "Tayyor, keling" xabari qachon yuborilgan. */
    private Instant readyNotifiedAt;

    /** Ishni qaysi usta qilgan (servis kiritganda). */
    @Column(length = 80)
    private String masterName;

    public String getMasterName() { return masterName; }
    public void setMasterName(String masterName) { this.masterName = masterName; }

    // ---------- Narx va to'lov (so'mda) ----------

    /** Ish narxi. null — narx kiritilmagan. */
    private Long price;

    /** Shu ish uchun jami to'langan (to'lovlar yig'indisi, tez hisoblash uchun saqlanadi). */
    private Long paidAmount;

    /** Qarzni to'lash muddati (qarz bo'lsa). */
    private LocalDate payDueDate;

    /** true — ish qarzga qilingan (qarz daftarida ko'rinadi, to'liq to'langandan keyin ham tarix uchun qoladi). */
    private Boolean credit;

    /** Sadoqat chegirmasi (so'mda). price — chegirmadan keyingi, to'lanadigan narx. */
    private Long discount;

    /** Shu ish uchun ball bilan to'langan summa. */
    private Long bonusPaid;

    public long getDiscount() { return discount == null ? 0 : discount; }
    public void setDiscount(long discount) { this.discount = discount == 0 ? null : discount; }
    public long getBonusPaid() { return bonusPaid == null ? 0 : bonusPaid; }
    public void addBonusPaid(long amount) { this.bonusPaid = getBonusPaid() + amount; }

    /** Qarz eslatmasi oxirgi marta qachon yuborilgan. */
    private LocalDate debtRemindedOn;

    public LocalDate getDebtRemindedOn() { return debtRemindedOn; }
    public void setDebtRemindedOn(LocalDate d) { this.debtRemindedOn = d; }

    public Long getPrice() { return price; }
    public void setPrice(Long price) { this.price = price; }
    public long getPaid() { return paidAmount == null ? 0 : paidAmount; }
    public void addPaid(long amount) { this.paidAmount = getPaid() + amount; }
    public LocalDate getPayDueDate() { return payDueDate; }
    public void setPayDueDate(LocalDate payDueDate) { this.payDueDate = payDueDate; }
    public boolean isCredit() { return Boolean.TRUE.equals(credit); }
    public void setCredit(boolean credit) { this.credit = credit; }

    /** To'lanmagan qoldiq (narx kiritilmagan bo'lsa 0). */
    public long getRemaining() {
        return price == null ? 0 : Math.max(0, price - getPaid());
    }

    /** Yozuvni arxivga o'tkazadi: shu turdagi yangi servis qilindi. */
    public void markReplaced(ServiceCenter byCenter) {
        this.active = false;
        this.replacedAt = Instant.now();
        this.replacedByCenter = byCenter;
    }

    public ServiceCenter getCenter() { return center; }
    public void setCenter(ServiceCenter center) { this.center = center; }
    public Instant getReplacedAt() { return replacedAt; }
    public ServiceCenter getReplacedByCenter() { return replacedByCenter; }
    public Instant getReadyNotifiedAt() { return readyNotifiedAt; }
    public void setReadyNotifiedAt(Instant readyNotifiedAt) { this.readyNotifiedAt = readyNotifiedAt; }

    public String displayName() {
        if (type == ServiceType.OTHER && customName != null && !customName.isBlank()) {
            return customName;
        }
        return type.getLabel();
    }

    public Long getId() { return id; }
    public Car getCar() { return car; }
    public void setCar(Car car) { this.car = car; }
    public ServiceType getType() { return type; }
    public void setType(ServiceType type) { this.type = type; }
    public String getCustomName() { return customName; }
    public void setCustomName(String customName) { this.customName = customName; }
    public LocalDate getDoneDate() { return doneDate; }
    public void setDoneDate(LocalDate doneDate) { this.doneDate = doneDate; }
    public LocalDate getNextDate() { return nextDate; }
    public void setNextDate(LocalDate nextDate) { this.nextDate = nextDate; }
    public Integer getMileage() { return mileage; }
    public void setMileage(Integer mileage) { this.mileage = mileage; }
    public Integer getNextMileage() { return nextMileage; }
    public void setNextMileage(Integer nextMileage) { this.nextMileage = nextMileage; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isWeekReminderSent() { return weekReminderSent; }
    public void setWeekReminderSent(boolean weekReminderSent) { this.weekReminderSent = weekReminderSent; }
    public boolean isDueReminderSent() { return dueReminderSent; }
    public void setDueReminderSent(boolean dueReminderSent) { this.dueReminderSent = dueReminderSent; }
    public Instant getCreatedAt() { return createdAt; }
}
