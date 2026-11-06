package uz.carcare.model;

import jakarta.persistence.*;

import java.time.Instant;

/** Ro'yxatdan o'tgan mashina va uning egasi. */
@Entity
@Table(name = "cars", indexes = {
        @Index(name = "ix_cars_chat", columnList = "telegramChatId")
})
public class Car {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Mashina sahifasiga kirish uchun maxfiy kalit (URL da ishlatiladi). */
    @Column(nullable = false, unique = true, length = 32)
    private String token;

    @Column(nullable = false, length = 60)
    private String firstName;

    @Column(nullable = false, length = 60)
    private String lastName;

    /** Format: 998901234567 */
    @Column(nullable = false, length = 12)
    private String phone;

    /** Format: 01A123BC (bo'sh joy va chiziqlarsiz, katta harflarda) */
    @Column(nullable = false, unique = true, length = 12)
    private String plateNumber;

    /** Telegram botga ulangan chat. null bo'lsa hali ulanmagan. */
    private Long telegramChatId;

    @Column(nullable = false)
    private boolean smsEnabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // ---------- Mijoz profili (ixtiyoriy) ----------

    public enum Gender { MALE, FEMALE }

    private java.time.LocalDate birthDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Gender gender;

    /** Mijoz qayerdan kelgan: Instagram, tanish tavsiyasi va h.k. */
    @Column(length = 60)
    private String source;

    /** Qaysi yili tug'ilgan kun tabrigi yuborilgan (bir yilda bir marta). */
    private Integer birthdayGreetedYear;

    public Integer getBirthdayGreetedYear() { return birthdayGreetedYear; }
    public void setBirthdayGreetedYear(Integer year) { this.birthdayGreetedYear = year; }

    public java.time.LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(java.time.LocalDate birthDate) { this.birthDate = birthDate; }
    public Gender getGender() { return gender; }
    public void setGender(Gender gender) { this.gender = gender; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public Long getId() { return id; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }
    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getPlateNumber() { return plateNumber; }
    public void setPlateNumber(String plateNumber) { this.plateNumber = plateNumber; }
    public Long getTelegramChatId() { return telegramChatId; }
    public void setTelegramChatId(Long telegramChatId) { this.telegramChatId = telegramChatId; }
    public boolean isSmsEnabled() { return smsEnabled; }
    public void setSmsEnabled(boolean smsEnabled) { this.smsEnabled = smsEnabled; }
    public Instant getCreatedAt() { return createdAt; }

    public String getFullName() {
        return firstName + " " + lastName;
    }
}
