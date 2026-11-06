package uz.carcare.sms;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import uz.carcare.config.SmsProperties;

import java.time.Duration;

/**
 * Eskiz.uz orqali SMS yuborish.
 * Eslatma: Eskiz yangi akkauntlarda SMS matn shablonlarini moderatsiyadan
 * o'tkazishni talab qiladi — README dagi ko'rsatmaga qarang.
 */
@Service
public class SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final SmsProperties props;
    private final RestClient http;
    private volatile String token;

    public SmsService(SmsProperties props) {
        this.props = props;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.http = RestClient.builder().baseUrl(props.eskizUrl()).requestFactory(factory).build();
    }

    public boolean enabled() {
        return props.enabled();
    }

    /** phone formati: 998901234567. Muvaffaqiyatli bo'lsa true. */
    public boolean send(String phone, String text) {
        if (!enabled()) {
            log.info("[SMS o'chiq] {} ga: {}", phone, text);
            return false;
        }
        try {
            return trySend(phone, text);
        } catch (HttpClientErrorException.Unauthorized e) {
            token = null; // token eskirgan — qayta login qilib yana urinamiz
            try {
                return trySend(phone, text);
            } catch (RestClientException e2) {
                log.warn("SMS yuborilmadi ({}): {}", phone, e2.getMessage());
                return false;
            }
        } catch (RestClientException e) {
            log.warn("SMS yuborilmadi ({}): {}", phone, e.getMessage());
            return false;
        }
    }

    private boolean trySend(String phone, String text) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("mobile_phone", phone);
        form.add("message", text);
        form.add("from", props.from());

        JsonNode res = http.post().uri("/message/sms/send")
                .header("Authorization", "Bearer " + token())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
        log.info("SMS yuborildi {}: {}", phone, res);
        return true;
    }

    private String token() {
        String t = token;
        if (t != null) return t;
        synchronized (this) {
            if (token == null) {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("email", props.eskizEmail());
                form.add("password", props.eskizPassword());
                JsonNode res = http.post().uri("/auth/login")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(form)
                        .retrieve()
                        .body(JsonNode.class);
                token = res == null ? null : res.path("data").path("token").asText(null);
                if (token == null) throw new IllegalStateException("Eskiz login javobida token yo'q: " + res);
            }
            return token;
        }
    }
}
