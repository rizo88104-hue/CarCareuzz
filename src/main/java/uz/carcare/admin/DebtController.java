package uz.carcare.admin;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import uz.carcare.admin.AdminDtos.PaymentDto;
import uz.carcare.admin.AdminDtos.PaymentRequest;
import uz.carcare.admin.DebtDtos.*;

import java.util.List;

/**
 * Qarz daftari va to'lovlar API (direktor va administrator).
 *   GET   /api/admin/center/debts?filter=&period=&q=
 *   POST  /api/admin/center/records/{id}/payments   — to'lov qabul qilish (qarzni so'ndirish)
 *   GET   /api/admin/center/records/{id}/payments   — ish bo'yicha to'lovlar tarixi
 *   PATCH /api/admin/center/records/{id}/price      — narx qo'yish yoki o'zgartirish
 */
@RestController
@RequestMapping("/api/admin/center")
public class DebtController {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;

    private final AdminAuthService auth;
    private final DebtService service;

    public DebtController(AdminAuthService auth, DebtService service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/debts")
    public DebtList debts(@RequestHeader(value = AUTH, required = false) String h,
                          @RequestParam(required = false) DebtFilter filter,
                          @RequestParam(required = false) Period period,
                          @RequestParam(required = false) String q) {
        return service.list(auth.requireContact(h), filter, period, q);
    }

    @PostMapping("/records/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public DebtRow pay(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id,
                       @Valid @RequestBody PaymentRequest req) {
        return service.pay(auth.requireContact(h), id, req);
    }

    @GetMapping("/records/{id}/payments")
    public List<PaymentDto> payments(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id) {
        return service.paymentsOf(auth.requireContact(h), id);
    }

    @PatchMapping("/records/{id}/price")
    public DebtRow price(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id,
                         @Valid @RequestBody PriceRequest req) {
        return service.setPrice(auth.requireContact(h), id, req);
    }
}
