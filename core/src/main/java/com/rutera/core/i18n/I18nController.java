package com.rutera.core.i18n;

import com.rutera.common.i18n.LangService;
import com.rutera.common.i18n.SupportedLanguages;
import com.rutera.core.web.ApiException;
import com.rutera.core.web.ErrorCodes;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Map;

/**
 * {@code GET /app/i18n/{lang}}: the whole dictionary of one language as a flat JSON object, the same keys as
 * {@code messages_xx.properties} (missing keys fall back to English). Public, because the login screen needs it.
 */
@RestController
@RequestMapping("/app/i18n")
public class I18nController {

    private final LangService lang;

    public I18nController(LangService lang) {
        this.lang = lang;
    }

    @GetMapping("/{lang}")
    public ResponseEntity<Map<String, String>> dictionary(@PathVariable("lang") String code) {
        String normalized = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (!SupportedLanguages.isSupported(normalized)) {
            throw ApiException.notFound(ErrorCodes.NOT_FOUND);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .body(lang.dictionary(normalized));
    }
}
