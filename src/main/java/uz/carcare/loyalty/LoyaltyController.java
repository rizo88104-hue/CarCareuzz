package uz.carcare.loyalty;

import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import uz.carcare.admin.AdminAuthService;
import uz.carcare.loyalty.LoyaltyDtos.*;
import uz.carcare.model.LoyaltyLevel;

/**
 * Sadoqat dasturi API.
 *   GET  /api/admin/center/loyalty?q=&level=             — bo'lim: statistika, kartalar, oxirgi harakatlar
 *   GET  /api/admin/center/loyalty/settings              — sozlamalar
 *   PUT  /api/admin/center/loyalty/settings              — sozlamalarni saqlash (direktor)
 *   POST /api/admin/center/loyalty/issue-all             — kartasi yo'q mijozlarga karta ochish (direktor)
 *   GET  /api/admin/center/customers/{id}/loyalty        — mijoz kartasi va ball tarixi
 *   POST /api/admin/center/customers/{id}/loyalty/card   — karta ochish
 *   POST /api/admin/center/customers/{id}/loyalty/adjust — ball qo'shish/ayirish (direktor)
 */
@RestController
@RequestMapping("/api/admin/center")
public class LoyaltyController {

    private static final String AUTH = HttpHeaders.AUTHORIZATION;

    private final AdminAuthService auth;
    private final LoyaltyService service;

    public LoyaltyController(AdminAuthService auth, LoyaltyService service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/loyalty")
    public Overview overview(@RequestHeader(value = AUTH, required = false) String h,
                             @RequestParam(required = false) String q,
                             @RequestParam(required = false) LoyaltyLevel level) {
        return service.overview(auth.requireContact(h), q, level);
    }

    @GetMapping("/loyalty/settings")
    public ProgramDto settings(@RequestHeader(value = AUTH, required = false) String h) {
        return service.settings(auth.requireContact(h));
    }

    @PutMapping("/loyalty/settings")
    public ProgramDto save(@RequestHeader(value = AUTH, required = false) String h, @Valid @RequestBody ProgramRequest req) {
        return service.updateSettings(auth.requireDirector(h), req);
    }

    @PostMapping("/loyalty/issue-all")
    public IssueAllResult issueAll(@RequestHeader(value = AUTH, required = false) String h, @RequestBody IssueAllRequest req) {
        return service.issueAll(auth.requireDirector(h), req);
    }

    @GetMapping("/customers/{id}/loyalty")
    public CustomerLoyalty customer(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id) {
        return service.customer(auth.requireContact(h), id);
    }

    @PostMapping("/customers/{id}/loyalty/card")
    public CustomerLoyalty issue(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id) {
        return service.issue(auth.requireContact(h), id);
    }

    @PostMapping("/customers/{id}/loyalty/adjust")
    public CustomerLoyalty adjust(@RequestHeader(value = AUTH, required = false) String h, @PathVariable Long id,
                                  @Valid @RequestBody AdjustRequest req) {
        return service.adjust(auth.requireDirector(h), id, req);
    }
}
