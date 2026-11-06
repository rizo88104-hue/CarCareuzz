# CarCare — mashina servis eslatmalari

Foydalanuvchi ism, familiya, telefon va mashina raqamini kiritadi → mashina uchun
shaxsiy sahifa ochiladi → u yerda moy (va boshqa servislar) qachon almashtirilgani
va keyingi sana belgilanadi → **muddatga 1 hafta qolganda** va **muddat kuni**
Telegram bot (va ixtiyoriy SMS) orqali eslatma keladi.

## Texnologiyalar

- **Backend:** Java 21, Spring Boot 3.5 (Web, Data JPA, Validation, Scheduling)
- **Baza:** H2 (lokal, sozlash shart emas) yoki PostgreSQL (server)
- **Frontend:** oddiy HTML + CSS + JavaScript (`src/main/resources/static`)
- **Telegram:** Bot API, long polling (domen/HTTPS shart emas)
- **SMS:** Eskiz.uz

## Loyiha tuzilmasi

```
src/main/java/uz/carcare/
├── CarCareApplication.java      ← kirish nuqtasi
├── config/                      ← sozlamalar (app, telegram, sms)
├── model/                       ← Car, ServiceRecord, ServiceType
├── repo/                        ← baza bilan ishlash
├── dto/                         ← API so'rov/javoblari
├── web/                         ← REST API, CarService, xatoliklar
├── telegram/                    ← TelegramClient, TelegramBotPoller (bot)
├── sms/                         ← SmsService (Eskiz)
├── reminder/                    ← ReminderService (har kuni 09:00), NotificationService
└── util/                        ← telefon/raqam formatlash
src/main/resources/
├── application.properties
└── static/  index.html (ro'yxatdan o'tish), car.html (mashina sahifasi), app.css
```

## 1. Telegram bot yaratish

1. Telegramda **@BotFather** ga yozing → `/newbot`
2. Nom bering (masalan `CarCare`) va username (masalan `CarCareUzBot`, oxiri `bot` bilan tugashi shart)
3. BotFather bergan **token** ni saqlab qo'ying (`123456:ABC...`)
4. Ixtiyoriy: `/setcommands` → botingizni tanlab quyidagini yuboring:
   ```
   holat - Mashinalarim va yaqin servislar
   ```

## 2. Lokal ishga tushirish

Kerak: **JDK 21** va **Maven 3.9+** (yoki IntelliJ IDEA — u Maven ni o'zi yuklaydi).

```bash
# Linux / macOS
export TELEGRAM_BOT_TOKEN="123456:ABC..."
export TELEGRAM_BOT_USERNAME="CarCareUzBot"
mvn spring-boot:run
```

```powershell
# Windows PowerShell
$env:TELEGRAM_BOT_TOKEN="123456:ABC..."
$env:TELEGRAM_BOT_USERNAME="CarCareUzBot"
mvn spring-boot:run
```

Brauzerda oching: **http://localhost:8080**

IntelliJ da: loyihani oching → `CarCareApplication` ni Run qiling → Run Configuration
ichida *Environment variables* ga yuqoridagi ikki o'zgaruvchini qo'shing.

> Token berilmasa ham sayt ishlaydi — bot o'chiq turadi, xabarlar faqat konsolga yoziladi.

## 3. Qanday ishlaydi

1. `/` — foydalanuvchi ma'lumot kiritadi → `/car/{maxfiy-kalit}` sahifasi ochiladi.
   Shu raqam + telefon bilan qayta kirsa, o'sha sahifaga qaytadi.
2. Sahifada **"Telegramga ulash"** → `t.me/Bot?start=KALIT` ochiladi → START → bot
   **"📱 Raqamni yuborish"** tugmasini ko'rsatadi. Telegram raqami saytdagi raqamga mos
   kelsagina mashina ulanadi — bu SMS kodsiz, **bepul tasdiqlash**.
   **Bitta bot hamma foydalanuvchiga xizmat qiladi.**
3. **"+ Qo'shish"** → servis turi, almashtirilgan sana, keyingi sana (+1/+3/+6 oy tugmalari),
   probeg va izoh. Shu turdagi yangi yozuv qo'shilsa, eskisi **Tarix** ga o'tadi.
   Mavjud yozuvdagi **"Almashtirdim"** tugmasi formani tayyor holda ochadi.
4. **Har kuni 09:00 (Toshkent)** da tekshiriladi:
   - muddatga ≤ 7 kun qolgan → "N kun qoldi" eslatmasi (bir marta);
   - muddat kuni (yoki o'tib ketgan) → "Bugun almashtirish kuni" (bir marta).
   Yangi yozuv qo'shilganda ham darhol tekshiriladi. Xabar yetkazilmasa
   (masalan bot hali ulanmagan), ertasi kuni yana urinadi.
5. Botdagi `/holat` buyrug'i — barcha mashinalar va servislar ro'yxati (🟢🟡🔴).

Necha kun oldin eslatishni o'zgartirish: `REMINDER_DAYS=7`.
Tekshirish vaqti: `application.properties` dagi `app.reminder-cron`.

## Admin panel: `/admin`

Uchta rol bor: **mashina egasi** (saytdagi sahifa), **avtoservis admini** va **super admin**.

### Super admin
Birinchi ishga tushishda avtomatik yaratiladi. Login: `SUPERADMIN_USERNAME` (standart `superadmin`),
parol: `SUPERADMIN_PASSWORD`. Parol berilmasa, tasodifiy parol yaratilib **konsolga bir marta**
chiqariladi. Kirgandan keyin "Parol" tugmasi orqali o'zgartiring.

Super admin:
- avtoservis qo'shadi (nomi, manzil, telefon) va uning **direktori** uchun login yaratadi.
  Qolgan xodimlarni direktor o'zi qo'shadi;
- **Faollik jurnali** — barcha servislarda kim, qachon, nima qilgani (servis bo'yicha filtr bilan);
- har bir servis holatini ko'radi. Muammolilar ro'yxat boshida turadi:
  - **Faol** — oxirgi 14 kun ichida ish kiritgan;
  - **Faol emas** — 14 kundan beri hech narsa kiritmagan;
  - **Ishlatmayapti** — qo'shilgandan beri bitta ham ish kiritmagan;
- har bir servis mijozlarini kuzatadi: vaqti kelgan → **qaytib kelgan / boshqa joyga ketgan / kelmagan**;
- **"Kelmaganlar va sababi"** tugmasi har bir mijoz uchun sababni ko'rsatadi:
  - *Boshqa servisga borgan* — keyingi ishni boshqa servis kiritgan;
  - *Telegram ulanmagan* — eslatma bormagan, qo'ng'iroq qilish kerak;
  - *Eslatma borgan, lekin kelmagan*;
  - *Eslatma yetkazilmagan* — mijoz botni bloklagan bo'lishi mumkin;
- servisni o'chiradi/yoqadi (o'chirilgan servis darhol tizimdan chiqariladi), parolini tiklaydi.

### Avtoservis xodimlari: rollar

| Imkoniyat | Direktor | Administrator | Usta |
|---|---|---|---|
| Ish kiritish | ✓ (ustani tanlaydi) | ✓ (ustani tanlaydi) | ✓ (o'zi nomidan) |
| Mijoz telefoni, «Tayyor, keling», qo'ng'iroq ro'yxati | ✓ | ✓ | — |
| Statistika | ✓ | ✓ | — |
| Xodim qo'shish, bloklash, parolini tiklash | ✓ | — | — |
| Servis faollik jurnali | ✓ | — | — |

Usta panelga kirganda faqat ish kiritish formasi va «Mening ishlarim» ro'yxatini ko'radi.
Bloklangan xodim darhol barcha qurilmalardan chiqariladi. Oldingi versiyadagi servis
loginlari dastur ishga tushganda avtomatik **direktor**ga aylantiriladi.

**Faollik jurnaliga** yoziladi: tizimga kirish, ish kiritish (qaysi usta qilgani bilan),
«Tayyor, keling», yangi mijoz, xodim qo'shish va bloklash, parol tiklash, servisni o'chirish,
mijozning ro'yxatdan o'tishi va botga ulanishi, kunlik eslatmalar natijasi.

### Mijozlar bazasi

Direktor va administrator panelida **«Mijozlar bazasi»** bo'limi bor. Unda servisga kamida bir
marta kelgan barcha mashinalar ko'rinadi (boshqa servislarning mijozlari ko'rinmaydi).

- **Qidiruv:** mashina raqami, ism, telefon yoki izoh bo'yicha.
- **Filtrlar** (yonida soni): Muddati o'tgan · 7 kun ichida · Telegram yo'q · Boshqa joyga ketgan · Yangi (30 kun).
- **Jadval:** egasi, telefon, tashriflar soni (bir kundagi bir nechta ish = 1 tashrif), oxirgi tashrif,
  keyingi servis va holat.
- **Mijoz kartasi** (qatorni bosing): ism, familiya, telefonni tahrirlash; **ichki izoh** (faqat shu servis
  ko'radi, masalan «doimiy mijoz, 10% chegirma»); hozirgi servislar (sizda yoki boshqa joyda qilingani);
  shu servisdagi to'liq tarix (sana, ish, usta, probeg).
- **Excel'ga yuklab olish** (faqat direktor): `.csv` fayl, Excel to'g'ridan-to'g'ri ochadi.

Super admin **«Mijozlar»** bo'limida butun tizimdagi mashinalarni qidiradi va har biri qaysi
servislarda xizmat olganini ko'radi. Ma'lumot o'zgarishi, izoh yozish va yuklab olish faollik jurnaliga yoziladi.

### Narx, to'lov va qarz daftari

- **Ish kiritishda** (direktor, administrator): narx, hozir to'langan summa va to'lov turi
  (naqd, karta, o'tkazma). To'langan summa narxdan kam bo'lsa, qolgani **qarzga** yoziladi va
  to'lash muddati so'raladi. «Hozir to'landi» bo'sh qoldirilsa — to'liq to'landi deb hisoblanadi.
  Usta narx kiritmaydi; uning ishiga administrator keyin **«Narx qo'yish»** orqali narx qo'yadi.
- **Qarz daftari** bo'limi:
  - kartalar: qarzdorlar soni (muddati o'tgan, to'lanmagan, qisman, to'langan), qarzlar summasi,
    to'lovlar summasi, qarzlar qoldig'i;
  - davr (barchasi, joriy yil, oy, hafta, bugun — ish sanasi bo'yicha), holat filtrlari va qidiruv;
  - **«To'lash»** — to'liq yoki qisman to'lov qabul qilinadi, to'lovlar tarixi shu oynada ko'rinadi.
- To'lov qabul qilinganda mijozning Telegramiga **kvitansiya** boradi: qancha to'landi, qoldiq qancha.
- Barcha to'lovlar alohida saqlanadi (`payments` jadvali), o'zgartirilmaydi va jurnalga yoziladi.

### Mijoz profili

«Mijozlar bazasi»da qatorni yoki «Qarz daftari»da mijoz ismini bossangiz, to'liq profil ochiladi:

- **Chapda:** ism, telefon, ro'yxatdan o'tgan sana, tug'ilgan kun, jinsi, Telegram bot holati
  (ulanmagan bo'lsa **«Ulash»** — mijozga yuboriladigan havola), mashina raqami, mijoz qayerdan
  kelgan (Instagram, tanish tavsiyasi ...), tashriflar. **«Tahrirlash»** shu ma'lumotlarni o'zgartiradi.
- **O'rtada:** umumiy qarz, to'langan, qoldiq, kechikish kunlari; **«Qarzni so'ndirish»** — kiritilgan
  summa mijozning ochiq qarzlariga avtomatik taqsimlanadi (avval muddati eng erta bo'lganiga);
  ichki izoh.
- **O'ngda:** jami xarajat, o'rtacha chek, oxirgi tashrif, hozirgi servislar.
- **Pastda ikki tab:** «Servislar tarixi» (narx va to'lov bilan) va «Qarzlar va to'lovlar»
  (qarzga qilingan ishlar + tranzaksiyalar tarixi).

### Bosh sahifa (direktor va administrator)

Panelga kirganda birinchi ochiladi: bugungi ishlar va tushum, qarzlar qoldig'i, 7 kun ichida
keladiganlar; shu oyning **kunlik tushum grafigi** (to'lovlar bo'yicha); mijozlar (jami, yangi,
qarzdorlar, tug'ilganlar — bosilsa tegishli ro'yxat ochiladi); eng katta qarzdorlar va
**shu oy tug'ilgan kunlar** («Tabriklash» tugmasi bilan).

### Mijozlar ro'yxati: qo'shimchalar

- Kartalar: **jami, faol** (oxirgi 180 kunda kelgan), **nofaol, qayta kelganlar** (2+ tashrif).
- Filtrlar: + Faol, Nofaol, Qayta kelgan, **Qora ro'yxat**; alohida **«🎂 Bu oy tug'ilgan kunlar»** tugmasi.
- Har bir qatorda 📞 qo'ng'iroq va ✉️ Telegram xabar tugmalari.
- **«+ Qo'shish»** — mijozni (ish kiritmasdan) qo'lda bazaga qo'shish.

### Qora ro'yxat

Direktor mijoz profilida **«⛔ Qora ro'yxatga»** tugmasi bilan sababini yozib qo'shadi
(har bir servisning o'z qora ro'yxati). Keyin bu mashina raqami ish kiritishda yozilsa,
qizil ogohlantirish chiqadi. Ro'yxatda va profilda belgi turadi. Chiqarish ham direktorda.

### Telegram xabar

Direktor va administrator mijozga bot orqali xabar yozadi (tayyor shablonlar: mashina tayyor,
aksiya, tug'ilgan kun tabrigi, qarz haqida). Xabar boshiga servis nomi, oxiriga telefoni qo'shiladi.

### Avtomatik bot xabarlari (har kuni 09:00)

| Xabar | Qachon |
|---|---|
| Servis eslatmasi | Muddatga 7 kun qolganda va muddat kuni |
| **Qarz eslatmasi** | To'lash muddatidan 1 kun oldin, muddat kuni, keyin har 7 kunda (bir servisdagi barcha qarzlar bitta xabarda) |
| **Tug'ilgan kun tabrigi** | Tug'ilgan kuni, yiliga bir marta (29-fevral — kabisa bo'lmagan yilda 28-fevralda) |
| To'lov kvitansiyasi | To'lov qabul qilinganda darhol (keshbek va balans bilan) |
| **Tug'ilgan kun bonusi** | Sadoqat dasturi yoqilgan servislar — tabrik xabariga qo'shiladi |
| **Ballar kuyishi** | 7 kun oldin ogohlantirish, muddat o'tgach ballar kuyadi |

### Sadoqat dasturi (jamg'arma karta, bonus ballar, darajalar)

Har bir servis o'zi yoqadi va sozlaydi (**🎁 Sadoqat** bo'limi, sozlamalarni faqat direktor o'zgartiradi).
Dastur o'chiq bo'lsa, hech narsa o'zgarmaydi.

- **Jamg'arma karta** — mijozga 8 xonali karta raqami beriladi (masalan `4021 3377`). Karta birinchi
  ishda avtomatik ochiladi; profildan qo'lda yoki «Kartasi yo'qlarga karta ochish» tugmasi bilan hammaga ochish mumkin.
  Har bir servisda karta alohida.
- **Bonus ball (keshbek)** — 1 ball = 1 so'm. Har **pul bilan** to'lovdan (naqd, karta, o'tkazma) daraja foizicha ball yoziladi.
  Qarzga qilingan ish uchun ball qarz to'langanda yoziladi. Ball bilan to'langan qismdan ball yozilmaydi.
- **Ball bilan to'lash** — ish kiritishda («Ball bilan to'lash» maydoni) yoki to'lov oynasida «🎁 Bonus ball» usuli.
  Bitta ish narxining ko'pi bilan belgilangan foizini (odatda 50%) ball bilan to'lash mumkin.
  Ball bilan to'lov **tushumga qo'shilmaydi** (bosh sahifa grafigida ko'rinmaydi).
- **Darajalar** — mijozning shu servisda pul bilan to'lagan jami summasiga qarab avtomatik oshadi:

  | Daraja | Jami xarid (standart) | Keshbek | Doimiy chegirma |
  |---|---|---|---|
  | 🥉 Bronza | 0 dan | 3% | 0% |
  | 🥈 Kumush | 3 000 000 so'mdan | 5% | 3% |
  | 🥇 Oltin | 10 000 000 so'mdan | 7% | 5% |
  | 💎 Platina | 25 000 000 so'mdan | 10% | 10% |

  Raqamlarni direktor o'zgartiradi. Chegirma ish kiritishda narxdan avtomatik ayiriladi (belgini olib tashlab, qo'llamaslik mumkin).
- **Bonuslar** — karta ochilganda (standart 10 000 ball) va tug'ilgan kunda (20 000 ball, yiliga bir marta).
- **Kuyish** — mijoz belgilangan kun (standart 365) davomida kelmasa, ballari kuyadi; 7 kun oldin botdan ogohlantirish boradi.
  Har qanday tashrif yoki to'lov muddatni yangilaydi. 0 — ballar hech qachon kuymaydi.
- **Qo'lda tuzatish** — direktor profilda ball qo'shishi yoki ayirishi mumkin (sabab majburiy, jurnalga yoziladi, mijozga xabar boradi).
- **Qora ro'yxatdagi mijozga** ball yozilmaydi va chegirma berilmaydi.
- **Mijoz tomoni** — mashina sahifasida «Jamg'arma kartalarim» bloki; botda `/ball` buyrug'i.
  Karta ochilishi, daraja oshishi, ball qo'shilishi va kuyishi haqida Telegramga xabar keladi.
- **Dastur to'xtatilsa** — ballar saqlanib qoladi (kuymaydi ham), lekin yozilmaydi va ishlatilmaydi.
- Ikki xodim bir vaqtda bitta kartadan ball yechsa, ikkinchisi rad etiladi (ball ikki marta sarflanmaydi).

Sadoqat API (direktor va administrator; `PUT settings`, `issue-all`, `adjust` — faqat direktor):

| Metod | Manzil | Vazifasi |
|---|---|---|
| GET | `/api/admin/center/loyalty?q=&level=` | Statistika, kartalar, oxirgi harakatlar |
| GET/PUT | `/api/admin/center/loyalty/settings` | Dastur sozlamalari |
| POST | `/api/admin/center/loyalty/issue-all` | Kartasi yo'qlarga karta ochish |
| GET | `/api/admin/center/customers/{id}/loyalty` | Mijoz kartasi va ball tarixi |
| POST | `/api/admin/center/customers/{id}/loyalty/card` | Karta ochish |
| POST | `/api/admin/center/customers/{id}/loyalty/adjust` | Ball qo'shish/ayirish |

### Ish kiritish jarayoni
- **Bajarilgan ishni kiritadi:** mashina raqamini yozadi → tizim egasini o'zi topadi. Mashina
  ro'yxatda bo'lmasa, egasining ism va telefonini kiritadi, mijoz avtomatik yaratiladi.
  Keyin nima qilingani (masalan **Ballon**), sana va keyingi muddat kiritiladi.
  Ish mijoz sahifasida darhol paydo bo'ladi, eslatma ham shunga qarab ishlaydi.
- **"Tayyor, keling"** — mijozning Telegramiga xabar boradi: mashina tayyor, servis nomi, manzili, telefoni.
  Telegram ulanmagan bo'lsa, mijozning telefoni ko'rsatiladi. Telefonda bosilsa, qo'ng'iroq boshlanadi.
- **Qo'ng'iroq ro'yxati** — muddati 7 kun ichida keladigan va o'tib ketgan mijozlar, telefonlari bilan.
- Shu servis kiritgan ishlar bo'yicha eslatmalarda servis nomi va telefoni ham ko'rsatiladi.

## Demo ma'lumotlar bilan ishga tushirish

Panelni demo sahifadagidek to'ldirib ko'rish uchun muhit o'zgaruvchisini qo'shing:

```
DEMO_DATA=true;SUPERADMIN_PASSWORD=Parolingiz123
```

Bazada hali birorta servis bo'lmasa, ishga tushganda quyidagilar yaratiladi:
«Avto Lider» (to'liq ma'lumot bilan), «Shina Plus» va «Motor Servis 24» servislari, 10 ta mijoz, ishlar,
qarzlar, qora ro'yxat, izohlar, sadoqat kartalari va jurnal yozuvlari.

| Login | Rol | Parol |
|---|---|---|
| `sardor` | Direktor | `demo12345` (yoki `DEMO_PASSWORD`) |
| `dilnoza` | Administrator | `demo12345` |
| `akmal` | Usta | `demo12345` |

Mavjud ma'lumotlarga tegmaydi. Bazani tozalab qayta boshlash uchun server to'xtatilgan holda `data` papkasini o'chiring.
Demo mijozlar Telegramga ulanmagan (begona odamlarga xabar ketmasligi uchun). **Haqiqiy ishda DEMO_DATA ni yoqmang.**

## Spamdan himoya

| Qayerda | Cheklov | Oshib ketsa |
|---|---|---|
| Bot, har bir foydalanuvchi | 1 daqiqada 15 xabar | Ortiqchasi javobsiz tashlanadi, 1 marta ogohlantiriladi |
| Bot, guruh/kanallar | — | E'tiborsiz qoldiriladi |
| Botdan yuborish (umumiy) | ~25 xabar/sekund | Navbatda kutadi; Telegram 429 bersa, aytgan vaqtcha kutib qayta yuboradi |
| Sayt API, har bir IP | 1 daqiqada 60 so'rov | 429 xato |
| Ro'yxatdan o'tish, har bir IP | 10 daqiqada 10 ta | 429 xato |
| "Sinov xabari", har bir IP | 10 daqiqada 3 ta | 429 xato |
| Admin login, har bir IP | 10 daqiqada 10 urinish | 429 xato (parolni taxmin qilishdan himoya) |
| "Tayyor, keling", bitta ish | 2 daqiqada 1 marta | Xato xabari |

Limitlarni `RateLimitFilter` va `TelegramBotPoller` dagi `new RateLimiter(...)` qatorlarida o'zgartirasiz.
Server Nginx orqasida bo'lsa, `X-Forwarded-For` sarlavhasini uzatishni sozlang (IP to'g'ri aniqlanishi uchun).

## 4. SMS (Eskiz.uz) ulash — ixtiyoriy

Standart holatda SMS **o'chiq**: barcha eslatmalar bepul Telegram bot orqali boradi, saytda
SMS qatori ko'rinmaydi. Keyinchalik kerak bo'lsa:

1. https://eskiz.uz da ro'yxatdan o'ting, balansni to'ldiring.
2. **Muhim:** Eskiz SMS matnlarini oldindan moderatsiyadan o'tkazadi. Kabinetda quyidagi
   shablonlarni yuboring (matnlar `ReminderService` dagi `weekSms` / `dueSms` da):
   ```
   Eslatma: {plate} mashinangizda {servis} almashtirishga {N} kun qoldi ({sana}).
   Eslatma: {plate} mashinangizda {servis} bugun almashtirish kuni ({sana}).
   Sinov xabari: eslatmalar shu raqamga keladi.
   ```
3. O'zgaruvchilar:
   ```bash
   export SMS_ENABLED=true
   export ESKIZ_EMAIL="siz@mail.uz"
   export ESKIZ_PASSWORD="..."
   export ESKIZ_FROM="4546"     # Eskiz bergan nik (alpha-name)
   ```
Foydalanuvchi o'z sahifasida SMS ni yoqib/o'chira oladi. Boshqa provayder (Play Mobile,
GetSMS) kerak bo'lsa faqat `SmsService` ni almashtirasiz.

## 5. Serverga joylash (VPS)

```bash
mvn clean package -DskipTests        # target/carcare.jar hosil bo'ladi
```

Serverda (PostgreSQL bilan):
```bash
export DB_URL="jdbc:postgresql://localhost:5432/carcare"
export DB_USER="postgres"
export DB_PASSWORD="..."
export APP_BASE_URL="https://carcare.uz"     # xabarlardagi havolalar uchun
export TELEGRAM_BOT_TOKEN="..."
export TELEGRAM_BOT_USERNAME="CarCareUzBot"
java -jar carcare.jar
```
Yoki Docker: `docker build -t carcare . && docker run -p 8080:8080 --env-file .env carcare`

Jadvallar avtomatik yaratiladi (`ddl-auto=update`). Dasturni faqat **bitta nusxada** ishga
tushiring — ikkita nusxa bir botdan xabar olishga urinib, bir-biriga xalaqit beradi.

## API

| Metod | Manzil | Vazifasi |
|---|---|---|
| GET | `/api/service-types` | Servis turlari ro'yxati |
| POST | `/api/cars` | Ro'yxatdan o'tish / qayta kirish |
| GET | `/api/cars/{token}` | Mashina va servislar |
| PATCH | `/api/cars/{token}/settings` | `{ "smsEnabled": true }` |
| POST | `/api/cars/{token}/records` | Servis qo'shish |
| DELETE | `/api/cars/{token}/records/{id}` | Servis o'chirish |
| POST | `/api/cars/{token}/test` | Sinov xabari |

## Keyingi qadamlar (g'oyalar)

- Saytga kirishni ham Telegram orqali tasdiqlash (hozir sahifa maxfiy havola orqali ochiladi)
- Admin panel: barcha mashinalar, statistika, ommaviy xabar
- Botning o'zidan servis qo'shish ("Moy almashtirdim" tugmasi)
- Servis turlari uchun standart muddatlar (moy — 6 oy / 10 000 km)
